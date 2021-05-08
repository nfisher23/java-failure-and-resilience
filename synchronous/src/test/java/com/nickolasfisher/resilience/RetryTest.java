package com.nickolasfisher.resilience;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
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
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

@ExtendWith(MockServerExtension.class)
public class RetryTest {
    private ClientAndServer clientAndServer;

    private RestTemplate restTemplate;

    public RetryTest(ClientAndServer clientAndServer) {
        this.clientAndServer = clientAndServer;

    }

    @AfterEach
    public void reset() {
        this.clientAndServer.reset();
    }

    @Test
    public void retryOnLatency() {
        RestTemplate restTemplate = new RestTemplateBuilder()
                .rootUri("http://localhost:" + clientAndServer.getPort())
                .setConnectTimeout(Duration.of(50, ChronoUnit.MILLIS))
                .setReadTimeout(Duration.of(80, ChronoUnit.MILLIS))
                .build();

        HttpRequest expectedFirstRequest = HttpRequest.request()
                .withMethod(HttpMethod.GET.name())
                .withPath("/some/endpoint/10");

        HttpResponse mockResponse = HttpResponse.response()
                .withBody("{\"message\": \"hello\"}")
                .withContentType(MediaType.APPLICATION_JSON)
                .withStatusCode(200);

        AtomicInteger timesCalled = new AtomicInteger(0);
        this.clientAndServer
            .when(expectedFirstRequest)
            .respond(httpRequest -> {
                    if (timesCalled.incrementAndGet() <= 2) {
                        // simulate latency
                        Thread.sleep(150);
                    }
                    return mockResponse;
                }
            );

        RetryConfig retryConfig = RetryConfig.custom()
                .maxAttempts(3)
                .waitDuration(Duration.ofMillis(100))
                .build();

        RetryRegistry retryRegistry = RetryRegistry.of(retryConfig);

        Retry retry = retryRegistry.retry("some-endpoint");

        ResponseEntity<JsonNode> jsonNodeResponseEntity = Retry.decorateCheckedSupplier(
                retry,
                () -> restTemplate
                    .getForEntity("/some/endpoint/10", JsonNode.class)
        )
                .unchecked().apply();

        assertEquals(3, timesCalled.get());
        assertEquals(200, jsonNodeResponseEntity.getStatusCode().value());
        assertEquals("hello", jsonNodeResponseEntity.getBody().get("message").asText());
    }
}