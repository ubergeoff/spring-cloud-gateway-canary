package com.example.gateway.loadbalancer;

import com.example.gateway.loadbalancer.strategy.InstanceSelectionStrategy;
import com.example.gateway.loadbalancer.strategy.LeastConnectionsInstanceSelectionStrategy;
import com.example.gateway.loadbalancer.strategy.RandomInstanceSelectionStrategy;
import com.example.gateway.loadbalancer.strategy.RoundRobinInstanceSelectionStrategy;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
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
@EnableConfigurationProperties(LoadBalancerProperties.class)
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
     * Selects the {@link InstanceSelectionStrategy} implementation based on
     * {@code gateway.load-balancer.strategy} in {@code application.yml}.
     */
    @Bean
    public InstanceSelectionStrategy instanceSelectionStrategy(LoadBalancerProperties properties) {
        return switch (properties.getStrategy()) {
            case LEAST_CONNECTIONS -> new LeastConnectionsInstanceSelectionStrategy();
            case ROUND_ROBIN -> new RoundRobinInstanceSelectionStrategy();
            default -> new RandomInstanceSelectionStrategy();
        };
    }

    /**
     * The {@link org.springframework.cloud.gateway.filter.GlobalFilter} that
     * intercepts {@code lb://} URIs and delegates instance selection to the
     * metadata-aware supplier.
     */
    @Bean
    public MetadataAwareLoadBalancerFilter metadataAwareLoadBalancerFilter(
            LoadBalancerClientFactory clientFactory,
            MetadataAwareServiceInstanceFilter filter,
            InstanceSelectionStrategy selectionStrategy) {
        return new MetadataAwareLoadBalancerFilter(clientFactory, filter, selectionStrategy);
    }
}
