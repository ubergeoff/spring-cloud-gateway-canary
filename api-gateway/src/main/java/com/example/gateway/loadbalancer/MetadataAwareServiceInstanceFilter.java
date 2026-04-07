package com.example.gateway.loadbalancer;

import org.springframework.cloud.client.ServiceInstance;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.server.ServerWebExchange;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Filters the candidate service instances returned by Eureka based on the
 * {@code deployment-state} metadata key.
 *
 * <p>Routing rules:
 * <ul>
 *   <li>If the inbound request carries the header {@code x-sgb-zone: passive},
 *       only instances whose {@code deployment-state} metadata equals {@code passive}
 *       are eligible.</li>
 *   <li>All other requests are routed exclusively to {@code active} instances.</li>
 *   <li>If the filtered set is empty (e.g. no passive node is up) the filter falls
 *       back to the full unfiltered list so traffic is never black-holed.</li>
 * </ul>
 *
 * <p>This class is intentionally stateless and has no Spring dependencies so it
 * can be unit-tested without a running application context.
 */
public class MetadataAwareServiceInstanceFilter {

    /** Eureka instance metadata key written by the CI/CD pipeline. */
    public static final String METADATA_KEY_DEPLOYMENT_STATE = "deployment-state";

    /** Metadata value for nodes that are live and receiving public traffic. */
    public static final String STATE_ACTIVE  = "active";

    /** Metadata value for canary nodes under internal validation. */
    public static final String STATE_PASSIVE = "passive";

    /** Request header used by QA / automated test suites to request a canary node. */
    public static final String CANARY_HEADER = "x-sgb-zone";

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Returns the subset of {@code instances} that should receive this request.
     *
     * @param exchange   the current server exchange (used to read the inbound header)
     * @param instances  all candidate instances as returned by Eureka / the supplier
     * @return           filtered (never null, may be empty only when the fallback
     *                   is also empty — i.e. Eureka has no instances at all)
     */
    public List<ServiceInstance> filter(ServerWebExchange exchange,
                                        List<ServiceInstance> instances) {
        String requestedZone = resolveRequestedZone(exchange.getRequest());
        return doFilter(instances, requestedZone);
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Reads the {@value #CANARY_HEADER} header from the request.
     * Returns {@value #STATE_PASSIVE} when the header is present and equals
     * "passive" (case-insensitive), otherwise returns {@value #STATE_ACTIVE}.
     */
    String resolveRequestedZone(ServerHttpRequest request) {
        String headerValue = request.getHeaders().getFirst(CANARY_HEADER);
        if (STATE_PASSIVE.equalsIgnoreCase(headerValue)) {
            return STATE_PASSIVE;
        }
        return STATE_ACTIVE;
    }

    /**
     * Core filter logic — separated from the reactive layer for easy unit testing.
     */
    List<ServiceInstance> doFilter(List<ServiceInstance> instances, String requestedZone) {
        List<ServiceInstance> filtered = instances.stream()
                .filter(instance -> requestedZone.equalsIgnoreCase(deploymentState(instance)))
                .collect(Collectors.toList());

        if (filtered.isEmpty()) {
            // Safety fallback: never black-hole traffic.
            // If no passive node is registered, let active nodes handle it;
            // if somehow no active node exists, return everything.
            List<ServiceInstance> fallback = instances.stream()
                    .filter(i -> STATE_ACTIVE.equalsIgnoreCase(deploymentState(i)))
                    .collect(Collectors.toList());
            return fallback.isEmpty() ? instances : fallback;
        }

        return filtered;
    }

    /**
     * Extracts {@value #METADATA_KEY_DEPLOYMENT_STATE} from the instance metadata,
     * defaulting to {@value #STATE_ACTIVE} when absent so that instances registered
     * without the key are treated as production nodes.
     */
    private String deploymentState(ServiceInstance instance) {
        Map<String, String> metadata = instance.getMetadata();
        if (metadata == null) {
            return STATE_ACTIVE;
        }
        return metadata.getOrDefault(METADATA_KEY_DEPLOYMENT_STATE, STATE_ACTIVE);
    }
}
