package com.example.gateway.loadbalancer;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for the metadata-aware load balancer.
 *
 * <p>Bound from the {@code gateway.load-balancer} prefix in {@code application.yml}:
 *
 * <pre>
 * gateway:
 *   load-balancer:
 *     strategy: least-connections   # or: random
 * </pre>
 */
@ConfigurationProperties(prefix = "gateway.load-balancer")
public class LoadBalancerProperties {

    /**
     * Instance selection algorithm to use after metadata filtering.
     * Supported values: {@code random}, {@code least-connections}, {@code round-robin}.
     * Defaults to {@code random}.
     */
    private Strategy strategy = Strategy.RANDOM;

    public Strategy getStrategy() {
        return strategy;
    }

    public void setStrategy(Strategy strategy) {
        this.strategy = strategy;
    }

    public enum Strategy {
        RANDOM,
        LEAST_CONNECTIONS,
        ROUND_ROBIN
    }
}
