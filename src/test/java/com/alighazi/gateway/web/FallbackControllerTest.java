package com.alighazi.gateway.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class FallbackControllerTest {

    private final FallbackController controller = new FallbackController();

    @Test
    void returnsServiceUnavailablePayload() {
        var response = controller.serviceUnavailable();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody())
                .containsEntry("status", HttpStatus.SERVICE_UNAVAILABLE.value())
                .containsEntry("error", "Service temporarily unavailable")
                .containsKey("timestamp");
    }
}