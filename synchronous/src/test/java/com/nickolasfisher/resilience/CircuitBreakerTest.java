package com.nickolasfisher.resilience;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.junit.jupiter.MockServerExtension;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.MediaType;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpMethod;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
}
