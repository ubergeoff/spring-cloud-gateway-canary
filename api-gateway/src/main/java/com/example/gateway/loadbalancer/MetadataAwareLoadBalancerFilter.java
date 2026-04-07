package com.example.gateway.loadbalancer;

import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.DefaultResponse;
import org.springframework.cloud.client.loadbalancer.reactive.ReactiveLoadBalancer;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.cloud.loadbalancer.support.LoadBalancerClientFactory;
import org.springframework.core.Ordered;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.concurrent.ThreadLocalRandom;

/**
 * {@link GlobalFilter} that replaces the default load-balancer instance selection
 * with one that is aware of {@code deployment-state} Eureka metadata.
 *
 * <p>Execution order is set to {@code 10149} so it runs just before Spring Cloud
 * Gateway's own {@code ReactiveLoadBalancerClientFilter} (order {@code 10150}).
 * After resolving the instance this filter rewrites {@code GATEWAY_REQUEST_URL_ATTR}
 * to a concrete {@code http://} URI and removes {@code GATEWAY_SCHEME_PREFIX_ATTR}
 * (which still carries {@code "lb"} from the route config).  Both conditions must
 * be cleared — {@code ReactiveLoadBalancerClientFilter} checks either one and will
 * still attempt its own resolution if only the URL is rewritten.
 *
 * <h3>How it works</h3>
 * <ol>
 *   <li>Reads the {@code lb://} scheme URI set by the route config.</li>
 *   <li>Builds a {@link MetadataAwareServiceInstanceListSupplier} that wraps the
 *       standard Eureka-backed supplier and applies header-driven metadata
 *       filtering.</li>
 *   <li>Picks an instance using a stateless hash-based selection from the filtered
 *       set.</li>
 *   <li>Rewrites the exchange URI to the chosen instance's host:port so the
 *       downstream {@code NettyRoutingFilter} can forward the request.</li>
 * </ol>
 *
 * <p>Registered as a {@code @Bean} by {@link MetadataAwareLoadBalancerConfiguration}
 * — do not also annotate this class with {@code @Component}.
 */
public class MetadataAwareLoadBalancerFilter implements GlobalFilter, Ordered {

    /**
     * Run just before Spring's built-in ReactiveLoadBalancerClientFilter (10150).
     * This filter resolves the instance and stores it so the built-in filter skips.
     */
    static final int ORDER = 10149;

    private final LoadBalancerClientFactory clientFactory;
    private final MetadataAwareServiceInstanceFilter metadataFilter;

    public MetadataAwareLoadBalancerFilter(LoadBalancerClientFactory clientFactory,
                                           MetadataAwareServiceInstanceFilter metadataFilter) {
        this.clientFactory  = clientFactory;
        this.metadataFilter = metadataFilter;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        URI url = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR);

        // Only handle lb:// URIs — let everything else pass through.
        if (url == null || !"lb".equalsIgnoreCase(url.getScheme())) {
            return chain.filter(exchange);
        }

        String serviceId = url.getHost();

        // Retrieve the Eureka-backed supplier from the LoadBalancer context.
        ServiceInstanceListSupplier eurekaSupplier = resolveSupplier(serviceId);

        if (eurekaSupplier == null) {
            return chain.filter(exchange);
        }

        // Wrap with our metadata-aware decorator for this specific request.
        MetadataAwareServiceInstanceListSupplier filteredSupplier =
                new MetadataAwareServiceInstanceListSupplier(eurekaSupplier, metadataFilter, exchange);

        return filteredSupplier.get()
                .next()                          // take the first (and only) emission
                .flatMap(instances -> {
                    if (instances.isEmpty()) {
                        // No suitable instance — let the default filter handle the error.
                        return chain.filter(exchange);
                    }

                    // Uniform random selection across the filtered instance pool.
                    int idx = ThreadLocalRandom.current().nextInt(instances.size());
                    ServiceInstance chosen = instances.get(idx);

                    // Reconstruct the URI with the real host:port.
                    // Note: LoadBalancerUriTools.reconstructURI preserves the original scheme
                    // (including "lb"), so NettyRoutingFilter would skip the request. We build
                    // the URI explicitly to guarantee an "http"/"https" scheme.
                    URI requestUrl = buildRequestUrl(chosen, url);
                    exchange.getAttributes().put(
                            ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR, requestUrl);

                    // Clear the "lb" scheme prefix so ReactiveLoadBalancerClientFilter (order 10150)
                    // sees a non-lb URL and skips its own resolution. Without this it would treat
                    // the already-resolved IP address as a Eureka service ID and return a 500.
                    exchange.getAttributes().remove(
                            ServerWebExchangeUtils.GATEWAY_SCHEME_PREFIX_ATTR);

                    // Store a properly-typed Response<ServiceInstance> so downstream filters
                    // (e.g. GatewayMetricsFilter) can cast it without a ClassCastException.
                    exchange.getAttributes().put(
                            ServerWebExchangeUtils.GATEWAY_LOADBALANCER_RESPONSE_ATTR,
                            new DefaultResponse(chosen));

                    return chain.filter(exchange);
                });
    }

    /**
     * Looks up the {@link ServiceInstanceListSupplier} for the given service from the
     * LoadBalancer factory. Extracted as a package-private method so unit tests can
     * override it in an anonymous subclass without needing to mock
     * {@link LoadBalancerClientFactory} (which cannot be instrumented by Mockito on
     * modern JVMs).
     */
    ServiceInstanceListSupplier resolveSupplier(String serviceId) {
        return clientFactory
                .getLazyProvider(serviceId, ServiceInstanceListSupplier.class)
                .getIfAvailable();
    }

    /**
     * Builds the concrete {@code http(s)://host:port} URI for the chosen instance.
     *
     * <p>{@link org.springframework.cloud.client.loadbalancer.LoadBalancerUriTools#reconstructURI}
     * preserves the original URI's scheme (including {@code "lb"}), which causes
     * {@code NettyRoutingFilter} to skip the request entirely (it only routes
     * {@code http} and {@code https} schemes). We therefore construct the URI directly,
     * copying path, query, and fragment from the original {@code lb://} URI.
     */
    private static URI buildRequestUrl(ServiceInstance instance, URI original) {
        String scheme = instance.isSecure() ? "https" : "http";
        try {
            return new URI(scheme, original.getUserInfo(),
                    instance.getHost(), instance.getPort(),
                    original.getPath(), original.getQuery(), original.getFragment());
        } catch (URISyntaxException ex) {
            throw new IllegalStateException("Could not construct request URI for " + instance.getHost(), ex);
        }
    }
}
