package com.example.gateway.loadbalancer.strategy;

import org.springframework.cloud.client.ServiceInstance;
import org.springframework.web.server.ServerWebExchange;

import java.util.List;

/**
 * Strategy interface for selecting a single {@link ServiceInstance} from a
 * pre-filtered candidate list.
 *
 * <p>Implementations are wired at startup via {@code gateway.load-balancer.strategy}
 * in {@code application.yml} and injected into
 * {@link com.example.gateway.loadbalancer.MetadataAwareLoadBalancerFilter}.
 *
 * <p>The {@code instances} list is always non-empty — the filter layer upstream
 * already handles the empty case with a 503 before reaching here.
 */
public interface InstanceSelectionStrategy {

    /**
     * Selects one instance from the candidate list.
     *
     * @param instances non-empty list of candidates (already metadata-filtered)
     * @param exchange  the current request exchange (available for request-affinity strategies)
     * @return the chosen instance
     */
    ServiceInstance select(List<ServiceInstance> instances, ServerWebExchange exchange);
}
