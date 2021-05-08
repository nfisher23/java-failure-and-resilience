package com.nickolasfisher.resilience;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.junit.jupiter.MockServerExtension;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockServerExtension.class)
public class CircuitBreakerTest {

    private ClientAndServer clientAndServer;

    private RestTemplate restTemplate;

    public CircuitBreakerTest(ClientAndServer clientAndServer) {
        this.clientAndServer = clientAndServer;
        this.restTemplate = new RestTemplateBuilder()
                .rootUri("http://localhost:" + clientAndServer.getPort())
                .build();
    }

    @AfterEach
    public void reset() {
        this.clientAndServer.reset();
    }

    @Test
    public void basicConfig_nothingHappensIfSlidingWindowNotFilled() {
        HttpRequest expectedFirstRequest = HttpRequest.request()
                .withMethod(HttpMethod.GET.name())
                .withPath("/some/endpoint/10");

        this.clientAndServer
                .when(expectedFirstRequest)
                .respond(HttpResponse.response().withStatusCode(500));

        CircuitBreakerConfig circuitBreakerConfig = CircuitBreakerConfig
                .custom()
                .slidingWindowSize(10)
                .build();

        CircuitBreakerRegistry circuitBreakerRegistry =
                CircuitBreakerRegistry.of(circuitBreakerConfig);

        CircuitBreaker callingEndpointCircuitBreaker = circuitBreakerRegistry.circuitBreaker("call-endpoint");

        // force the circuit to trip
        for (int i = 1; i < 11; i++) {
            try {
                callingEndpointCircuitBreaker.decorateSupplier(() ->
                        restTemplate.getForEntity("/some/endpoint/10", JsonNode.class)
                ).get();
                fail("we should never get here!");
            } catch (HttpServerErrorException e) {
                // expected
            }
        }

        // circuit is now tripped
        try {
            callingEndpointCircuitBreaker.decorateSupplier(() ->
                    restTemplate.getForEntity("/some/endpoint/10", JsonNode.class)
            ).get();
            fail("we should never get here!");
        } catch (CallNotPermittedException callNotPermittedException)  {
            assertEquals("call-endpoint", callNotPermittedException.getCausingCircuitBreakerName());
            assertSame(CircuitBreaker.State.OPEN, callingEndpointCircuitBreaker.getState());
        }
    }

    @Test
    public void clientErrorException_stillTripsTheCircuit() {
        HttpRequest expectedFirstRequest = HttpRequest.request()
                .withMethod(HttpMethod.GET.name())
                .withPath("/some/endpoint/10");

        this.clientAndServer
                .when(expectedFirstRequest)
                .respond(HttpResponse.response().withStatusCode(404));

        CircuitBreakerConfig circuitBreakerConfig = CircuitBreakerConfig
                .custom()
                .slidingWindowSize(10)
                .build();

        CircuitBreakerRegistry circuitBreakerRegistry =
                CircuitBreakerRegistry.of(circuitBreakerConfig);

        CircuitBreaker callingEndpointCircuitBreaker = circuitBreakerRegistry.circuitBreaker("call-endpoint");

        // force the circuit to trip
        for (int i = 1; i < 11; i++) {
            try {
                callingEndpointCircuitBreaker.decorateSupplier(() ->
                        restTemplate.getForEntity("/some/endpoint/10", JsonNode.class)
                ).get();
                fail("we should never get here!");
            } catch (HttpClientErrorException e) {
                // expected
            }
        }

        // circuit is now tripped, but should it be?
        try {
            callingEndpointCircuitBreaker.decorateSupplier(() ->
                    restTemplate.getForEntity("/some/endpoint/10", JsonNode.class)
            ).get();
            fail("we should never get here!");
        } catch (CallNotPermittedException callNotPermittedException)  {
            assertEquals("call-endpoint", callNotPermittedException.getCausingCircuitBreakerName());
            assertSame(CircuitBreaker.State.OPEN, callingEndpointCircuitBreaker.getState());
        }
    }

    @Test
    public void excludingClientErrorExceptions_fromTheCount() {
        HttpRequest expectedFirstRequest = HttpRequest.request()
                .withMethod(HttpMethod.GET.name())
                .withPath("/some/endpoint/10");

        this.clientAndServer
                .when(expectedFirstRequest)
                .respond(HttpResponse.response().withStatusCode(404));

        CircuitBreakerConfig circuitBreakerConfig = CircuitBreakerConfig
                .custom()
                .ignoreException(throwable -> {
                    return throwable instanceof HttpClientErrorException;
                })
                .slidingWindowSize(10)
                .build();

        CircuitBreakerRegistry circuitBreakerRegistry =
                CircuitBreakerRegistry.of(circuitBreakerConfig);

        CircuitBreaker callingEndpointCircuitBreaker = circuitBreakerRegistry.circuitBreaker("call-endpoint");

        // before we ignored the exception above, this would trip the circuit
        for (int i = 1; i < 11; i++) {
            try {
                callingEndpointCircuitBreaker.decorateSupplier(() ->
                        restTemplate.getForEntity("/some/endpoint/10", JsonNode.class)
                ).get();
                fail("we should never get here!");
            } catch (HttpClientErrorException e) {
                // expected
            }
        }

        // the circuit doesn't trip
        try {
            callingEndpointCircuitBreaker.decorateSupplier(() ->
                    restTemplate.getForEntity("/some/endpoint/10", JsonNode.class)
            ).get();
            fail("we should never get here!");
        } catch (HttpClientErrorException httpClientErrorException)  {
            assertEquals(HttpStatus.NOT_FOUND, httpClientErrorException.getStatusCode());
            assertSame(CircuitBreaker.State.CLOSED, callingEndpointCircuitBreaker.getState());
        }
    }
}
