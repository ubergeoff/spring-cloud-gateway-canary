package com.example.gateway.loadbalancer.strategy;

import org.springframework.cloud.client.ServiceInstance;
import org.springframework.web.server.ServerWebExchange;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Selects instances in round-robin order across all requests.
 *
 * Activated by setting {@code gateway.load-balancer.strategy: round-robin} in
 * {@code application.yml}.
 */
public class RoundRobinInstanceSelectionStrategy implements InstanceSelectionStrategy {

    private final AtomicInteger counter = new AtomicInteger(0);

    @Override
    public ServiceInstance select(List<ServiceInstance> instances, ServerWebExchange exchange) {
        int idx = Math.abs(counter.getAndIncrement() % instances.size());
        return instances.get(idx);
    }
}
