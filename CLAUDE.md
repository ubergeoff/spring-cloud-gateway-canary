# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Test Commands

```bash
# Maven is not on the system PATH — use the IntelliJ-bundled mvn:
MVN="/c/Program Files/JetBrains/IntelliJ IDEA Community Edition 2025.2.6.1/plugins/maven/lib/maven3/bin/mvn"

# Build (skipping tests)
"$MVN" package -DskipTests

# Run all tests
"$MVN" test

# Run a single test class
"$MVN" test -Dtest=MetadataAwareServiceInstanceFilterTest

# Run a single nested test class
"$MVN" test -Dtest="MetadataAwareServiceInstanceFilterTest\$NormalRouting"

# Run the application (requires Eureka at localhost:8761)
"$MVN" spring-boot:run
```

## Dev Startup Script

`start-dev.sh` (in this project root) starts the full local stack in the correct order.

```bash
bash start-dev.sh
```

**What it does:**
1. Starts the Eureka server (`eureka-server/`) in the background
2. Polls `http://localhost:8761/actuator/health` every second (up to 60 s) until Eureka reports healthy
3. Starts `user-service-a` (port 8081) in the background and waits for it to become healthy
4. Starts `user-service-b` (port 8082) in the background and waits for it to become healthy
5. Starts the API Gateway (`api-gateway/`) in the foreground
6. On Ctrl-C (or any exit), kills all background processes cleanly via a `trap`

If any background process dies during its health-check wait, the script exits immediately with an error.

## Architecture

This is a **Spring Cloud Gateway** (reactive, Netty-based) that adds metadata-driven canary routing on top of Eureka service discovery.

### Core concept: deployment-state routing

Every Eureka instance carries a `deployment-state` metadata key set to either `active` (production) or `passive` (canary). Inbound requests are steered to the right pool based on the `x-sgb-zone` request header:

| `x-sgb-zone` header | Routed to             |
|---------------------|-----------------------|
| `passive`           | `deployment-state=passive` instances only |
| absent / other      | `deployment-state=active` instances only  |

Fallback rule: if no passive instance is available, traffic falls back to active — traffic is never black-holed.

### Filter execution order

```
Inbound request
  → MetadataAwareLoadBalancerFilter (order 10149)   ← our custom filter
      reads x-sgb-zone, filters Eureka instances, picks one, rewrites lb:// URI
  → ReactiveLoadBalancerClientFilter (order 10150)  ← Spring built-in, skipped
      (detects GATEWAY_LOADBALANCER_RESPONSE_ATTR already set → no-op)
  → NettyRoutingFilter
      forwards to the resolved host:port
```

### Key classes (`src/main/java/com/example/gateway/loadbalancer/`)

| Class | Role |
|-------|------|
| `MetadataAwareServiceInstanceFilter` | Stateless filter logic; reads `deployment-state` metadata, applies fallback. Unit-testable without Spring context. |
| `MetadataAwareServiceInstanceListSupplier` | Reactive `ServiceInstanceListSupplier` decorator; wraps the Eureka supplier and delegates to the filter above. |
| `MetadataAwareLoadBalancerFilter` | `GlobalFilter` (order 10149); wires everything together per-request, rewrites exchange URI. |
| `MetadataAwareLoadBalancerConfiguration` | `@Configuration`; registers the beans above. |

### Load-balancer strategies

Configured via `gateway.load-balancer.strategy` in `api-gateway/src/main/resources/application.yml`:

| Value | Class | Notes |
|-------|-------|-------|
| `random` | `RandomInstanceSelectionStrategy` | Stateless uniform random — good for testing |
| `least-connections` | `LeastConnectionsInstanceSelectionStrategy` | Tracks in-flight requests; ties broken by list order (always picks first instance on sequential requests) |
| `round-robin` | `RoundRobinInstanceSelectionStrategy` | Strict alternation via `AtomicInteger` counter |

### Route configuration (`application.yml`)

Two routes for `lb://user-service`:
1. **passive-testing** (order 1) — requires `Header=x-sgb-zone, passive`; tagged `deployment-state: passive`
2. **active-live** (order 2) — catches all remaining `/users/**` traffic; tagged `deployment-state: active`

Route `metadata.deployment-state` is informational — actual instance selection is driven by the `x-sgb-zone` header in `MetadataAwareServiceInstanceFilter`.

### Managing canary instances via Eureka REST API

```bash
# Mark instance as passive (canary) before deploying
EUREKA_INSTANCE_METADATA_MAP_DEPLOYMENT_STATE=passive

# Promote back to active
curl -X PUT "http://eureka-server:8761/eureka/apps/USER-SERVICE/{instanceId}/metadata?deployment-state=active"
```

The gateway fetches the Eureka registry every 5 seconds (`registry-fetch-interval-seconds: 5`) so promotions propagate quickly.

**Important:** Spring Cloud LoadBalancer's `CachingServiceInstanceListSupplier` caches `ServiceInstance` objects (including their metadata snapshots) for **35 seconds**. A Eureka metadata promotion updates the Eureka server immediately, but the gateway's LoadBalancer cache may serve stale `deployment-state` values for up to 35 seconds — causing the metadata filter to exclude the promoted instance.

To make a promotion effective instantly, flush the cache after the Eureka `PUT`:

```bash
curl -X PUT "http://localhost:8761/eureka/apps/USER-SERVICE/{instanceId}/metadata?deployment-state=active"
curl -X POST "http://localhost:8080/actuator/gateway/refresh"
```
