# MetadataAwareServiceInstanceFilter

Drop-in load balancer extension for Spring Cloud Gateway that routes requests
to Eureka-registered service instances based on their `deployment-state` metadata key.

---

## Files

```
src/main/java/com/example/gateway/loadbalancer/
├── MetadataAwareServiceInstanceFilter.java        # Core filter logic (stateless)
├── MetadataAwareServiceInstanceListSupplier.java  # Reactive supplier decorator
├── MetadataAwareLoadBalancerFilter.java           # GlobalFilter — intercepts lb:// URIs
└── MetadataAwareLoadBalancerConfiguration.java    # @Configuration — wires the beans

src/test/java/com/example/gateway/loadbalancer/
└── MetadataAwareServiceInstanceFilterTest.java    # JUnit 5 unit tests (no Spring context)
```

---

## How it works

```
Inbound request
      │
      ▼
MetadataAwareLoadBalancerFilter  (order 10149)
      │
      │  reads x-sgb-zone header
      │  wraps Eureka supplier with MetadataAwareServiceInstanceListSupplier
      │  picks instance from filtered list (round-robin)
      │  writes resolved URI back to exchange attributes
      ▼
NettyRoutingFilter forwards to chosen instance
```

### Routing rules

| `x-sgb-zone` header | Eligible instances              |
|---------------------|---------------------------------|
| `passive`           | `deployment-state=passive` only |
| absent / any other  | `deployment-state=active` only  |

**Fallback:** if no passive instance is registered, the filter falls back to active instances so traffic is never black-holed.

Instances with **no** `deployment-state` metadata key are treated as `active`.

---

## Integration

### 1. Add the configuration class to your application

```java
@SpringBootApplication
@Import(MetadataAwareLoadBalancerConfiguration.class)
public class GatewayApplication { ... }
```

Or just let component-scan pick it up if it's in the same package tree.

### 2. Disable Spring's built-in ReactiveLoadBalancerClientFilter (optional)

The `MetadataAwareLoadBalancerFilter` runs at order **10149**, just before Spring's
`ReactiveLoadBalancerClientFilter` at **10150**.  Because it writes the resolved
instance into `GATEWAY_LOADBALANCER_RESPONSE_ATTR`, the built-in filter will
detect that the instance is already resolved and skip its own LB logic.

No extra config required.

### 3. Gateway route YAML (unchanged from Gemini's design)

```yaml
spring:
  cloud:
    gateway:
      routes:
        - id: user-service-passive-testing
          uri: lb://user-service
          order: 1
          predicates:
            - Path=/users/**
            - Header=x-sgb-zone, passive

        - id: user-service-active-live
          uri: lb://user-service
          order: 2
          predicates:
            - Path=/users/**
```

### 4. Eureka instance metadata (set via CI/CD)

```bash
# Promote a canary node back to active
curl -X PUT \
  "http://eureka-server:8761/eureka/apps/USER-SERVICE/{instanceId}/metadata?deployment-state=active"

# Isolate a node as canary before deploying
# (or inject at startup via environment variable)
EUREKA_INSTANCE_METADATA_MAP_DEPLOYMENT_STATE=passive
```

---

## Dependencies (pom.xml additions)

```xml
<!-- Already present in a typical Spring Cloud Gateway project -->
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-gateway</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-netflix-eureka-client</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-loadbalancer</artifactId>
</dependency>

<!-- Test -->
<dependency>
    <groupId>org.springframework</groupId>
    <artifactId>spring-test</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.assertj</groupId>
    <artifactId>assertj-core</artifactId>
    <scope>test</scope>
</dependency>
```