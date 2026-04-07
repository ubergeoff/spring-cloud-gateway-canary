## Overview
This document outlines the architecture and deployment lifecycle for a Java microservices ecosystem utilizing **Spring Cloud Gateway** and **Eureka**.

The strategy employs an **Active/Active Canary** approach. Unlike traditional Blue/Green deployments where infrastructure is doubled and demoted, this method maximizes resource utilization. All nodes run actively in production. During a release, a single node is temporarily isolated as a "canary" (tagged as `passive`) for internal validation before being promoted back to `active` status.

## Architecture Components
1. **Spring Cloud Gateway:** Acts as the ingress controller and traffic router. It dynamically routes traffic based on metadata registered in Eureka.
2. **Netflix Eureka:** The central service registry. It holds the dynamic `deployment-state` of every microservice instance.
3. **Microservices:** Spring Boot applications acting as Eureka clients.
4. **CI/CD Pipeline:** The orchestrator that automates deployment, node isolation, and metadata updates via Eureka's REST API.

## Configuration

### 1. Microservice Configuration (Eureka Client)
When a node starts, it registers with Eureka. For normal operations, the baseline state is `active`.

```yaml
eureka:
  instance:
    metadata-map:
      deployment-state: active
```

To start a node in canary (passive) mode — for example during a rolling deploy — override the property at launch:

```bash
EUREKA_INSTANCE_METADATA_MAP_DEPLOYMENT_STATE=passive java -jar user-service.jar
```

### 2. Gateway Route Configuration
The gateway defines two routes for each upstream service. Route order determines priority — the more specific predicate (canary header) must come first:

| Order | Route ID                     | Predicate                              | Target pool        |
|-------|------------------------------|----------------------------------------|--------------------|
| 1     | `user-service-passive-testing` | `Path=/users/**` + `x-sgb-zone: passive` header | `deployment-state=passive` instances |
| 2     | `user-service-active-live`    | `Path=/users/**`                       | `deployment-state=active` instances  |

Both routes use `lb://user-service`. Instance selection is performed by `MetadataAwareLoadBalancerFilter` (not by the route metadata itself).

The gateway fetches the Eureka registry every 5 seconds (`registry-fetch-interval-seconds: 5`) so metadata changes propagate quickly without a restart.

### 3. Request Routing Rules

| `x-sgb-zone` header value | Routed to                                      |
|---------------------------|------------------------------------------------|
| `passive` (case-insensitive) | `deployment-state=passive` instances only   |
| Absent or any other value | `deployment-state=active` instances only       |

**Fallback rule:** if no passive instance is registered when a canary request arrives, traffic falls back to active instances. Traffic is never black-holed.

**Default rule:** instances registered without the `deployment-state` metadata key are treated as `active`.

## CI/CD Deployment Lifecycle

The pipeline performs a rolling canary deploy without doubling infrastructure:

### Step 1 — Isolate the canary node
Mark one instance as `passive` via the Eureka metadata REST API. The gateway stops sending public traffic to it within 5 seconds.

```bash
INSTANCE_ID="<instance-id>"   # e.g. 10.0.0.5:user-service:8080
EUREKA_HOST="http://eureka-server:8761"

curl -X PUT \
  "${EUREKA_HOST}/eureka/apps/USER-SERVICE/${INSTANCE_ID}/metadata?deployment-state=passive"
```

### Step 2 — Deploy the new version
Deploy the updated artefact to the isolated node. Because it is `passive`, no user traffic is affected.

### Step 3 — Validate internally
Route internal QA / smoke-test traffic to the canary by including the header:

```
x-sgb-zone: passive
```

### Step 4 — Promote back to active
Once validation passes, flip the node back to `active`. The gateway resumes sending public traffic to it within 5 seconds.

```bash
curl -X PUT \
  "${EUREKA_HOST}/eureka/apps/USER-SERVICE/${INSTANCE_ID}/metadata?deployment-state=active"
```

### Step 5 — Roll forward
Repeat Steps 1–4 for each remaining node until all instances run the new version.

## Instance Metadata Reference

| Key                | Values            | Set by        | Meaning                                      |
|--------------------|-------------------|---------------|----------------------------------------------|
| `deployment-state` | `active`          | Microservice / CI/CD | Node is live; receives public traffic   |
| `deployment-state` | `passive`         | CI/CD pipeline| Node is isolated as canary; receives only `x-sgb-zone: passive` traffic |
| *(absent)*         | —                 | —             | Treated as `active` by the gateway           |
