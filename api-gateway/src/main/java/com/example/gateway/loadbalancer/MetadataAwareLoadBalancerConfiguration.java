package com.example.gateway.loadbalancer;

import org.springframework.cloud.loadbalancer.support.LoadBalancerClientFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the metadata-aware load balancing infrastructure.
 *
 * <p>Picked up automatically by Spring Boot's component scan because it resides
 * within the {@code com.example.gateway} package tree.  No explicit
 * {@code @Import} is required on the main application class.
 *
 * <p>This class is deliberately not marked {@code @AutoConfiguration} so you
 * retain full control over when it activates.
 */
@Configuration
public class MetadataAwareLoadBalancerConfiguration {

    /**
     * The stateless filter bean — shared across all requests.
     * It reads the {@code x-sgb-zone} header and performs metadata matching.
     */
    @Bean
    public MetadataAwareServiceInstanceFilter metadataAwareServiceInstanceFilter() {
        return new MetadataAwareServiceInstanceFilter();
    }

    /**
     * The {@link org.springframework.cloud.gateway.filter.GlobalFilter} that
     * intercepts {@code lb://} URIs and delegates instance selection to the
     * metadata-aware supplier.
     */
    @Bean
    public MetadataAwareLoadBalancerFilter metadataAwareLoadBalancerFilter(
            LoadBalancerClientFactory clientFactory,
            MetadataAwareServiceInstanceFilter filter) {
        return new MetadataAwareLoadBalancerFilter(clientFactory, filter);
    }
}
