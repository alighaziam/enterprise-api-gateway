package com.alighazi.gateway.ops;

import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.stereotype.Component;

@Component
public class RedisLiveRateLimit implements LiveRateLimit {

    private final RedisRateLimiter limiter;

    public RedisLiveRateLimit(RedisRateLimiter limiter) {
        this.limiter = limiter;
    }

    @Override
    public void apply(String routeId, int replenishPerSecond, int burst) {
        RedisRateLimiter.Config existing = limiter.getConfig().get(routeId);
        int requestedTokens = existing == null ? 1 : Math.max(1, existing.getRequestedTokens());
        RedisRateLimiter.Config updated = new RedisRateLimiter.Config()
                .setReplenishRate(replenishPerSecond)
                .setBurstCapacity(burst)
                .setRequestedTokens(requestedTokens);
        limiter.getConfig().put(routeId, updated);
    }

    @Override
    public void remove(String routeId) {
        limiter.getConfig().remove(routeId);
    }
}
