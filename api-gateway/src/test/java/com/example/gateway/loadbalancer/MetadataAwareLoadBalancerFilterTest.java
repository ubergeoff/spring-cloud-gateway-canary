package com.example.gateway.loadbalancer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.Response;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.example.gateway.loadbalancer.MetadataAwareServiceInstanceFilter.METADATA_KEY_DEPLOYMENT_STATE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link MetadataAwareLoadBalancerFilter}.
 *
 * Uses concrete anonymous-class fakes rather than Mockito mocks to stay
 * compatible with Java 24 (Byte Buddy 1.14.x does not officially support it).
 * The filter's package-private {@code resolveSupplier()} hook is overridden
 * inline in each test, keeping the test-scope impact minimal.
 */
class MetadataAwareLoadBalancerFilterTest {

    // -------------------------------------------------------------------------
    // Fakes & helpers
    // -------------------------------------------------------------------------

    /** Chain that records whether it was invoked. */
    private static final class RecordingChain implements GatewayFilterChain {
        boolean called = false;

        @Override
        public reactor.core.publisher.Mono<Void> filter(org.springframework.web.server.ServerWebExchange exchange) {
            called = true;
            return reactor.core.publisher.Mono.empty();
        }
    }

    /** Minimal supplier that always emits a fixed list. */
    private static ServiceInstanceListSupplier supplierOf(List<ServiceInstance> instances) {
        return new ServiceInstanceListSupplier() {
            @Override public String getServiceId() { return "user-service"; }
            @Override public Flux<List<ServiceInstance>> get() { return Flux.just(instances); }
        };
    }

    /** Builds a filter whose resolveSupplier() returns the given supplier (or null). */
    private static MetadataAwareLoadBalancerFilter filterReturning(ServiceInstanceListSupplier supplier) {
        return new MetadataAwareLoadBalancerFilter(null, new MetadataAwareServiceInstanceFilter()) {
            @Override
            ServiceInstanceListSupplier resolveSupplier(String serviceId) {
                return supplier;
            }
        };
    }

    private static ServiceInstance instance(String id, String state, String ip) {
        return new DefaultServiceInstance(id, "user-service", ip, 8080, false,
                Map.of(METADATA_KEY_DEPLOYMENT_STATE, state));
    }

    /** Exchange pre-loaded with an lb:// GATEWAY_REQUEST_URL_ATTR. */
    private static MockServerWebExchange lbExchange(String headerName, String headerValue) {
        MockServerHttpRequest.BaseBuilder<?> builder = MockServerHttpRequest.get("/users/1");
        if (headerName != null) {
            builder.header(headerName, headerValue);
        }
        MockServerWebExchange exchange = MockServerWebExchange.from(builder.build());
        exchange.getAttributes().put(
                ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR, URI.create("lb://user-service"));
        exchange.getAttributes().put(
                ServerWebExchangeUtils.GATEWAY_SCHEME_PREFIX_ATTR, "lb");
        return exchange;
    }

    private static MockServerWebExchange lbExchange() {
        return lbExchange(null, null);
    }

    // -------------------------------------------------------------------------
    // Pass-through conditions
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Pass-through — filter must not touch the exchange")
    class PassThrough {

        @Test
        @DisplayName("No GATEWAY_REQUEST_URL_ATTR → passes through")
        void noUrlAttr_passesThrough() {
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/users/1").build());
            RecordingChain chain = new RecordingChain();
            AtomicBoolean resolverCalled = new AtomicBoolean(false);

            MetadataAwareLoadBalancerFilter filter =
                    new MetadataAwareLoadBalancerFilter(null, new MetadataAwareServiceInstanceFilter()) {
                        @Override ServiceInstanceListSupplier resolveSupplier(String id) {
                            resolverCalled.set(true);
                            return null;
                        }
                    };

            StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

            assertThat(chain.called).isTrue();
            assertThat(resolverCalled).isFalse();
            assertThat(exchange.getAttributes())
                    .doesNotContainKey(ServerWebExchangeUtils.GATEWAY_LOADBALANCER_RESPONSE_ATTR);
        }

        @Test
        @DisplayName("Non-lb:// scheme (already resolved) → passes through")
        void nonLbScheme_passesThrough() {
            MockServerWebExchange exchange = MockServerWebExchange.from(
                    MockServerHttpRequest.get("/users/1").build());
            exchange.getAttributes().put(
                    ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR, URI.create("http://10.0.0.1:8080/users/1"));
            RecordingChain chain = new RecordingChain();
            AtomicBoolean resolverCalled = new AtomicBoolean(false);

            MetadataAwareLoadBalancerFilter filter =
                    new MetadataAwareLoadBalancerFilter(null, new MetadataAwareServiceInstanceFilter()) {
                        @Override ServiceInstanceListSupplier resolveSupplier(String id) {
                            resolverCalled.set(true);
                            return null;
                        }
                    };

            StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

            assertThat(chain.called).isTrue();
            assertThat(resolverCalled).isFalse();
        }

        @Test
        @DisplayName("Null supplier from factory → passes through (ReactiveLoadBalancerClientFilter handles it)")
        void nullSupplier_passesThrough() {
            RecordingChain chain = new RecordingChain();
            MetadataAwareLoadBalancerFilter filter = filterReturning(null);

            StepVerifier.create(filter.filter(lbExchange(), chain)).verifyComplete();

            assertThat(chain.called).isTrue();
        }

        @Test
        @DisplayName("Empty instance list → passes through without rewriting URI")
        void emptyInstances_passesThrough() {
            MockServerWebExchange exchange = lbExchange();
            RecordingChain chain = new RecordingChain();
            MetadataAwareLoadBalancerFilter filter = filterReturning(supplierOf(List.of()));

            StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

            assertThat(chain.called).isTrue();
            URI urlAttr = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR);
            assertThat(urlAttr).isEqualTo(URI.create("lb://user-service"));
            assertThat(exchange.getAttributes())
                    .doesNotContainKey(ServerWebExchangeUtils.GATEWAY_LOADBALANCER_RESPONSE_ATTR);
        }
    }

    // -------------------------------------------------------------------------
    // Successful instance resolution
    // -------------------------------------------------------------------------

    @Nested
    @DisplayName("Instance resolution — URI and attribute management")
    class InstanceResolution {

        @Test
        @DisplayName("Resolved instance: URI rewritten to http://host:port")
        void resolvesInstance_uriRewritten() {
            MockServerWebExchange exchange = lbExchange();
            MetadataAwareLoadBalancerFilter filter =
                    filterReturning(supplierOf(List.of(instance("i1", "active", "10.0.0.1"))));

            StepVerifier.create(filter.filter(exchange, new RecordingChain())).verifyComplete();

            URI resolved = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR);
            assertThat(resolved).isNotNull();
            assertThat(resolved.getScheme()).isEqualTo("http");
            assertThat(resolved.getHost()).isEqualTo("10.0.0.1");
            assertThat(resolved.getPort()).isEqualTo(8080);
        }

        @Test
        @DisplayName("Resolved instance: GATEWAY_SCHEME_PREFIX_ATTR cleared so ReactiveLoadBalancerClientFilter skips")
        void resolvesInstance_schemePrefixCleared() {
            MockServerWebExchange exchange = lbExchange();
            MetadataAwareLoadBalancerFilter filter =
                    filterReturning(supplierOf(List.of(instance("i1", "active", "10.0.0.1"))));

            StepVerifier.create(filter.filter(exchange, new RecordingChain())).verifyComplete();

            assertThat(exchange.getAttributes())
                    .doesNotContainKey(ServerWebExchangeUtils.GATEWAY_SCHEME_PREFIX_ATTR);
        }

        @Test
        @DisplayName("Resolved instance: GATEWAY_LOADBALANCER_RESPONSE_ATTR set for downstream filters (e.g. GatewayMetricsFilter)")
        void resolvesInstance_responseAttrSet() {
            MockServerWebExchange exchange = lbExchange();
            MetadataAwareLoadBalancerFilter filter =
                    filterReturning(supplierOf(List.of(instance("i1", "active", "10.0.0.1"))));

            StepVerifier.create(filter.filter(exchange, new RecordingChain())).verifyComplete();

            Object attr = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_LOADBALANCER_RESPONSE_ATTR);
            assertThat(attr).isNotNull().isInstanceOf(Response.class);

            @SuppressWarnings("unchecked")
            Response<ServiceInstance> response = (Response<ServiceInstance>) attr;
            assertThat(response.hasServer()).isTrue();
            assertThat(response.getServer().getHost()).isEqualTo("10.0.0.1");
        }

        @Test
        @DisplayName("x-sgb-zone: passive → resolves to passive instance IP")
        void passiveHeader_routesToPassiveInstance() {
            MockServerWebExchange exchange = lbExchange("x-sgb-zone", "passive");
            MetadataAwareLoadBalancerFilter filter = filterReturning(supplierOf(List.of(
                    instance("a", "active",  "10.0.0.1"),
                    instance("p", "passive", "10.0.0.2"))));

            StepVerifier.create(filter.filter(exchange, new RecordingChain())).verifyComplete();

            URI resolved = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR);
            assertThat(resolved).isNotNull();
            assertThat(resolved.getHost()).isEqualTo("10.0.0.2");
        }

        @Test
        @DisplayName("No x-sgb-zone header → resolves to active instance IP")
        void noHeader_routesToActiveInstance() {
            MockServerWebExchange exchange = lbExchange();
            MetadataAwareLoadBalancerFilter filter = filterReturning(supplierOf(List.of(
                    instance("a", "active",  "10.0.0.1"),
                    instance("p", "passive", "10.0.0.2"))));

            StepVerifier.create(filter.filter(exchange, new RecordingChain())).verifyComplete();

            URI resolved = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR);
            assertThat(resolved).isNotNull();
            assertThat(resolved.getHost()).isEqualTo("10.0.0.1");
        }
    }

    // -------------------------------------------------------------------------
    // Metadata
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("getOrder() returns 10149 (runs before ReactiveLoadBalancerClientFilter at 10150)")
    void order_is10149() {
        MetadataAwareLoadBalancerFilter filter = filterReturning(null);
        assertThat(filter.getOrder()).isEqualTo(MetadataAwareLoadBalancerFilter.ORDER);
        assertThat(filter.getOrder()).isEqualTo(10149);
    }
}
