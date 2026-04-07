package com.example.gateway.loadbalancer.strategy;

import org.springframework.cloud.client.ServiceInstance;
import org.springframework.web.server.ServerWebExchange;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Selects an instance using uniform random selection.
 *
 * <p>Stateless — safe to share across all requests without synchronization.
 * Activated by setting {@code gateway.load-balancer.strategy: random} in
 * {@code application.yml}.
 */
public class RandomInstanceSelectionStrategy implements InstanceSelectionStrategy {

    @Override
    public ServiceInstance select(List<ServiceInstance> instances, ServerWebExchange exchange) {
        int idx = ThreadLocalRandom.current().nextInt(instances.size());
        return instances.get(idx);
    }
}
