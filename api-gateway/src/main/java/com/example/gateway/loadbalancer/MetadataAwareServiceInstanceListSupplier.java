package com.example.gateway.loadbalancer;

import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.loadbalancer.core.ServiceInstanceListSupplier;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * A {@link ServiceInstanceListSupplier} decorator that applies
 * {@link MetadataAwareServiceInstanceFilter} to every list of candidates emitted
 * by the delegate supplier (typically the Eureka-backed supplier).
 *
 * <p>Spring Cloud LoadBalancer's request-context is propagated through the reactive
 * pipeline via {@link org.springframework.cloud.loadbalancer.support.LoadBalancerClientFactory}
 * and retrieved here from the {@link org.springframework.cloud.loadbalancer.core.LoadBalancerLifecycle}
 * hint stored on the exchange attribute
 * {@code ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR}.  We keep it simple: we
 * receive the exchange directly from the custom
 * {@link MetadataAwareLoadBalancerConfiguration} which wires the filter factory.
 */
public class MetadataAwareServiceInstanceListSupplier implements ServiceInstanceListSupplier {

    private final ServiceInstanceListSupplier delegate;
    private final MetadataAwareServiceInstanceFilter filter;
    private final ServerWebExchange exchange;

    public MetadataAwareServiceInstanceListSupplier(ServiceInstanceListSupplier delegate,
                                                    MetadataAwareServiceInstanceFilter filter,
                                                    ServerWebExchange exchange) {
        this.delegate = delegate;
        this.filter   = filter;
        this.exchange = exchange;
    }

    @Override
    public String getServiceId() {
        return delegate.getServiceId();
    }

    /**
     * Intercepts the Flux of instance lists and applies the metadata filter
     * before the load balancer chooses a single instance.
     */
    @Override
    public Flux<List<ServiceInstance>> get() {
        return delegate.get()
                .map(instances -> filter.filter(exchange, instances));
    }
}
