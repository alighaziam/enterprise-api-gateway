package com.alighazi.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TrafficAccountingFilterTest {

    @Test
    void readsTheSubjectFromAnUnverifiedBearerToken() {
        String payload = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"sub\":\"alice\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertThat(TrafficAccountingFilter.subjectFromBearer("Bearer header." + payload + ".sig"))
                .isEqualTo("alice");
        assertThat(TrafficAccountingFilter.subjectFromBearer(null)).isEqualTo("anonymous");
        assertThat(TrafficAccountingFilter.subjectFromBearer("Bearer not-a-jwt")).isEqualTo("anonymous");
    }
}
