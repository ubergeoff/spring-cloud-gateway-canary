package com.example.gateway.loadbalancer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import java.util.List;
import java.util.Map;

import static com.example.gateway.loadbalancer.MetadataAwareServiceInstanceFilter.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link MetadataAwareServiceInstanceFilter}.
 *
 * Uses Spring's {@link MockServerHttpRequest} / {@link MockServerWebExchange}
 * so no running application context is needed.
 */
class MetadataAwareServiceInstanceFilterTest {

    private MetadataAwareServiceInstanceFilter filter;

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static ServiceInstance instance(String id, String state) {
        return new DefaultServiceInstance(
                id, "user-service", "10.0.0." + id, 8080, false,
                state == null ? Map.of() : Map.of(METADATA_KEY_DEPLOYMENT_STATE, state));
    }

    private static MockServerWebExchange exchangeWithHeader(String headerValue) {
        MockServerHttpRequest request = headerValue == null
                ? MockServerHttpRequest.get("/users/1").build()
                : MockServerHttpRequest.get("/users/1").header(CANARY_HEADER, headerValue).build();
        return MockServerWebExchange.from(request);
    }

    @BeforeEach
    void setUp() {
        filter = new MetadataAwareServiceInstanceFilter();
    }

    // -------------------------------------------------------------------------
    // Zone resolution
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("resolveRequestedZone()")
    class ResolveZone {

        @Test
        void returnsPassive_whenHeaderIsPassive() {
            var exchange = exchangeWithHeader("passive");
            assertThat(filter.resolveRequestedZone(exchange.getRequest()))
                    .isEqualTo(STATE_PASSIVE);
        }

        @Test
        void returnsPassive_whenHeaderIsPASSIVE_caseInsensitive() {
            var exchange = exchangeWithHeader("PASSIVE");
            assertThat(filter.resolveRequestedZone(exchange.getRequest()))
                    .isEqualTo(STATE_PASSIVE);
        }

        @Test
        void returnsActive_whenHeaderAbsent() {
            var exchange = exchangeWithHeader(null);
            assertThat(filter.resolveRequestedZone(exchange.getRequest()))
                    .isEqualTo(STATE_ACTIVE);
        }

        @Test
        void returnsActive_whenHeaderHasUnknownValue() {
            var exchange = exchangeWithHeader("staging");
            assertThat(filter.resolveRequestedZone(exchange.getRequest()))
                    .isEqualTo(STATE_ACTIVE);
        }
    }

    // -------------------------------------------------------------------------
    // Filtering — normal paths
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("doFilter() — normal routing")
    class NormalRouting {

        @Test
        @DisplayName("Public request → only active instances returned")
        void publicRequest_returnsOnlyActive() {
            List<ServiceInstance> instances = List.of(
                    instance("1", STATE_ACTIVE),
                    instance("2", STATE_PASSIVE),
                    instance("3", STATE_ACTIVE));

            List<ServiceInstance> result = filter.doFilter(instances, STATE_ACTIVE);

            assertThat(result).hasSize(2)
                    .allMatch(i -> STATE_ACTIVE.equals(i.getMetadata().get(METADATA_KEY_DEPLOYMENT_STATE)));
        }

        @Test
        @DisplayName("Canary request → only passive instances returned")
        void canaryRequest_returnsOnlyPassive() {
            List<ServiceInstance> instances = List.of(
                    instance("1", STATE_ACTIVE),
                    instance("2", STATE_PASSIVE),
                    instance("3", STATE_ACTIVE));

            List<ServiceInstance> result = filter.doFilter(instances, STATE_PASSIVE);

            assertThat(result).hasSize(1)
                    .allMatch(i -> STATE_PASSIVE.equals(i.getMetadata().get(METADATA_KEY_DEPLOYMENT_STATE)));
        }

        @Test
        @DisplayName("Instance with no metadata key defaults to active")
        void missingMetadataKey_treatedAsActive() {
            ServiceInstance noMetaInstance = new DefaultServiceInstance(
                    "99", "user-service", "10.0.0.99", 8080, false, Map.of());

            List<ServiceInstance> result = filter.doFilter(List.of(noMetaInstance), STATE_ACTIVE);

            assertThat(result).containsExactly(noMetaInstance);
        }

        @Test
        @DisplayName("Instance with null metadata map defaults to active")
        void nullMetadataMap_treatedAsActive() {
            // DefaultServiceInstance with null map — simulate edge case.
            ServiceInstance nullMeta = new DefaultServiceInstance(
                    "88", "user-service", "10.0.0.88", 8080, false, null);

            List<ServiceInstance> result = filter.doFilter(List.of(nullMeta), STATE_ACTIVE);

            assertThat(result).containsExactly(nullMeta);
        }
    }

    // -------------------------------------------------------------------------
    // Filtering — fallback paths
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("doFilter() — fallback behaviour")
    class FallbackBehaviour {

        @Test
        @DisplayName("No passive node → fallback to active nodes (no black-holing)")
        void noPassiveNode_fallsBackToActive() {
            List<ServiceInstance> instances = List.of(
                    instance("1", STATE_ACTIVE),
                    instance("2", STATE_ACTIVE));

            List<ServiceInstance> result = filter.doFilter(instances, STATE_PASSIVE);

            // Must not return empty — falls back to active set.
            assertThat(result).hasSize(2)
                    .allMatch(i -> STATE_ACTIVE.equals(i.getMetadata().get(METADATA_KEY_DEPLOYMENT_STATE)));
        }

        @Test
        @DisplayName("No active OR passive node → returns all instances")
        void noActiveOrPassive_returnsAll() {
            ServiceInstance unknown = new DefaultServiceInstance(
                    "77", "user-service", "10.0.0.77", 8080, false,
                    Map.of(METADATA_KEY_DEPLOYMENT_STATE, "draining"));

            List<ServiceInstance> result = filter.doFilter(List.of(unknown), STATE_PASSIVE);

            assertThat(result).containsExactly(unknown);
        }

        @Test
        @DisplayName("Empty instance list → returns empty (Eureka is down)")
        void emptyInstanceList_returnsEmpty() {
            List<ServiceInstance> result = filter.doFilter(List.of(), STATE_ACTIVE);
            assertThat(result).isEmpty();
        }
    }

    // -------------------------------------------------------------------------
    // Integration-style: full filter() call with exchange
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("filter() — end-to-end with ServerWebExchange")
    class EndToEnd {

        @Test
        @DisplayName("Request without header routes to active nodes")
        void noHeader_routesToActive() {
            var exchange = exchangeWithHeader(null);
            List<ServiceInstance> instances = List.of(
                    instance("A", STATE_ACTIVE),
                    instance("B", STATE_PASSIVE));

            List<ServiceInstance> result = filter.filter(exchange, instances);

            assertThat(result).hasSize(1)
                    .allMatch(i -> "A".equals(i.getInstanceId()));
        }

        @Test
        @DisplayName("Request with x-sgb-zone: passive routes to passive node")
        void passiveHeader_routesToPassive() {
            var exchange = exchangeWithHeader("passive");
            List<ServiceInstance> instances = List.of(
                    instance("A", STATE_ACTIVE),
                    instance("B", STATE_PASSIVE));

            List<ServiceInstance> result = filter.filter(exchange, instances);

            assertThat(result).hasSize(1)
                    .allMatch(i -> "B".equals(i.getInstanceId()));
        }
    }
}
