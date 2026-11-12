package com.alighazi.gateway.ops;

public interface LiveRateLimit {

    void apply(String routeId, int replenishPerSecond, int burst);
}
