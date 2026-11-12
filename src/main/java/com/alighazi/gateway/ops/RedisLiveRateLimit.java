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
        RedisRateLimiter.Config config = limiter.getConfig().get(routeId);
        if (config == null) {
            config = new RedisRateLimiter.Config();
            limiter.getConfig().put(routeId, config);
        }
        int requestedTokens = Math.max(1, config.getRequestedTokens());
        config.setReplenishRate(replenishPerSecond)
                .setBurstCapacity(burst)
                .setRequestedTokens(requestedTokens);
    }
}
