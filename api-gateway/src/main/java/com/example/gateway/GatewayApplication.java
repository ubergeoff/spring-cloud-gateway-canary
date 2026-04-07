package com.example.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the API Gateway.
 *
 * <p>Component scan covers {@code com.example.gateway.**}, which automatically picks up
 * {@code MetadataAwareLoadBalancerConfiguration} in the {@code loadbalancer} sub-package.
 * No explicit {@code @Import} is needed.
 */
@SpringBootApplication
public class GatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GatewayApplication.class, args);
    }
}
