# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run

```bash
# Build (skip tests — there are none)
mvn clean package

# Run the server
mvn spring-boot:run

# Run the pre-built fat jar
java -jar target/eureka-server-0.0.1-SNAPSHOT.jar
```

The server starts on **port 8761**. The Eureka dashboard is available at `http://localhost:8761`.

## Stack

- Java 21, Spring Boot 3.3.4, Spring Cloud 2023.0.3
- Single dependency: `spring-cloud-starter-netflix-eureka-server`

## Architecture

This is a minimal standalone Eureka service registry — a single `@SpringBootApplication` + `@EnableEurekaServer` entry point with no business logic.

**Key configuration** (`src/main/resources/application.yml`):
- `eureka.client.register-with-eureka: false` and `fetch-registry: false` — the server does not register itself as a client.
- Self-preservation is **disabled** (`enable-self-preservation: false`) with a 5-second eviction interval, suitable for local/dev use where stale instances should be removed quickly.

Microservices that want to register with this server should point their `eureka.client.service-url.defaultZone` to `http://localhost:8761/eureka/`.
