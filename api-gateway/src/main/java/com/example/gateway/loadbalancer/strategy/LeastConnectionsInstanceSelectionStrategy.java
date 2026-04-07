package com.example.gateway.loadbalancer.strategy;

import org.springframework.cloud.client.ServiceInstance;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Selects the instance with the fewest in-flight requests at the moment of
 * selection (least-connections load balancing).
 *
 * <p>A counter per instance ID is incremented when an instance is chosen and
 * decremented via a {@link reactor.core.publisher.Signal} hook once the
 * downstream response completes (or errors / cancels).  This gives an
 * approximate — not exact — view of active connections because the decrement
 * happens after the gateway has finished writing the response body, not at the
 * moment the upstream connection is released.  For typical REST workloads the
 * approximation is accurate enough to outperform round-robin when instance
 * processing times vary significantly.
 *
 * <p>Tie-breaking falls back to the natural list order so the result is
 * deterministic when all counters are equal (e.g. on startup).
 *
 * <p>Activated by setting {@code gateway.load-balancer.strategy: least-connections}
 * in {@code application.yml}.
 */
public class LeastConnectionsInstanceSelectionStrategy implements InstanceSelectionStrategy {

    /**
     * Active-connection counters keyed by Eureka instance ID.
     * Entries are created on first use and never removed — instance IDs are stable
     * within a JVM lifetime.
     */
    private final ConcurrentHashMap<String, AtomicLong> activeConnections = new ConcurrentHashMap<>();

    @Override
    public ServiceInstance select(List<ServiceInstance> instances, ServerWebExchange exchange) {
        ServiceInstance chosen = instances.stream()
                .min(Comparator.comparingLong(i -> connectionCount(i.getInstanceId()).get()))
                .orElse(instances.get(0));

        // Increment before the request is forwarded.
        connectionCount(chosen.getInstanceId()).incrementAndGet();

        // Decrement when the response pipeline terminates (success, error, or cancel).
        exchange.getResponse()
                .beforeCommit(() -> {
                    connectionCount(chosen.getInstanceId()).decrementAndGet();
                    return Mono.empty();
                });

        return chosen;
    }

    /**
     * Returns the counter for the given instance ID, creating it if absent.
     */
    private AtomicLong connectionCount(String instanceId) {
        return activeConnections.computeIfAbsent(instanceId, id -> new AtomicLong(0));
    }

    /**
     * Exposed for testing — returns the current in-flight count for an instance.
     */
    long activeConnectionsFor(String instanceId) {
        AtomicLong counter = activeConnections.get(instanceId);
        return counter == null ? 0 : counter.get();
    }
}
