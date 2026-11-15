package com.alighazi.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TrafficAccountingFilterTest {

    @Test
    void normalizesOnlyAuthenticatedPrincipalNames() {
        assertThat(TrafficAccountingFilter.normalizePrincipal("alice")).isEqualTo("alice");
        assertThat(TrafficAccountingFilter.normalizePrincipal("  alice  ")).isEqualTo("alice");
        assertThat(TrafficAccountingFilter.normalizePrincipal(null)).isEqualTo("anonymous");
        assertThat(TrafficAccountingFilter.normalizePrincipal("   ")).isEqualTo("anonymous");
        assertThat(TrafficAccountingFilter.normalizePrincipal("x".repeat(81))).isEqualTo("anonymous");
    }
}
