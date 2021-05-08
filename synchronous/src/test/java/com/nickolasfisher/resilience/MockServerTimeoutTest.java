package com.nickolasfisher.resilience;

import com.fasterxml.jackson.databind.JsonNode;
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
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

@ExtendWith(MockServerExtension.class)
public class MockServerTimeoutTest {

    private ClientAndServer clientAndServer;

    public MockServerTimeoutTest(ClientAndServer clientAndServer) {
        this.clientAndServer = clientAndServer;

    }

    @AfterEach
    public void reset() {
        this.clientAndServer.reset();
    }

    @Test
    public void basicRestTemplateExample() {
        RestTemplate restTemplate = new RestTemplateBuilder()
                .rootUri("http://localhost:" + clientAndServer.getPort())
                .build();

        HttpRequest expectedFirstRequest = HttpRequest.request()
                .withMethod(HttpMethod.GET.name())
                .withPath("/some/endpoint/10");

        HttpResponse mockResponse = HttpResponse.response()
                .withBody("{\"message\": \"hello\"}")
                .withContentType(MediaType.APPLICATION_JSON)
                .withStatusCode(200);

        this.clientAndServer
                .when(expectedFirstRequest)
                .respond(mockResponse);

        ResponseEntity<JsonNode> responseEntity = restTemplate.getForEntity("/some/endpoint/10", JsonNode.class);

        assertEquals("hello", responseEntity.getBody().get("message").asText());
    }

    @Test
    public void latencyInMockServer() {
        RestTemplate restTemplateWithSmallTimeout = new RestTemplateBuilder()
                .rootUri("http://localhost:" + clientAndServer.getPort())
                .setConnectTimeout(Duration.of(50, ChronoUnit.MILLIS))
                .setReadTimeout(Duration.of(80, ChronoUnit.MILLIS))
                .build();

        RestTemplate restTemplateWithBigTimeout = new RestTemplateBuilder()
                .rootUri("http://localhost:" + clientAndServer.getPort())
                .setConnectTimeout(Duration.of(50, ChronoUnit.MILLIS))
                .setReadTimeout(Duration.of(250, ChronoUnit.MILLIS))
                .build();

        HttpRequest expectedFirstRequest = HttpRequest.request()
                .withMethod(HttpMethod.GET.name())
                .withPath("/some/endpoint/10");

        HttpResponse mockResponse = HttpResponse.response()
                .withBody("{\"message\": \"hello\"}")
                .withContentType(MediaType.APPLICATION_JSON)
                .withStatusCode(200);

        this.clientAndServer
                .when(expectedFirstRequest)
                .respond(httpRequest -> {
                            Thread.sleep(150);
                            return mockResponse;
                        }
                );

        try {
            restTemplateWithSmallTimeout.getForEntity("/some/endpoint/10", JsonNode.class);
            fail("We should never reach this line!");
        } catch (ResourceAccessException resourceAccessException) {
            assertEquals("Read timed out", resourceAccessException.getCause().getMessage());
        }

        ResponseEntity<JsonNode> jsonNodeResponseEntity = restTemplateWithBigTimeout
                .getForEntity("/some/endpoint/10", JsonNode.class);

        assertEquals(200, jsonNodeResponseEntity.getStatusCode().value());
        assertEquals("hello", jsonNodeResponseEntity.getBody().get("message").asText());
    }
}
