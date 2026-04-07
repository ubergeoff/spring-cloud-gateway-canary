#!/usr/bin/env bash
# Start Eureka server, both user-service stubs, then Spring Cloud Gateway.
# Run from this project's directory: bash start-dev.sh

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MVN="/c/Program Files/JetBrains/IntelliJ IDEA Community Edition 2025.2.6.1/plugins/maven/lib/maven3/bin/mvn"
EUREKA_DIR="$SCRIPT_DIR/eureka-server"
USER_SVC_A_DIR="$SCRIPT_DIR/user-service-a"
USER_SVC_B_DIR="$SCRIPT_DIR/user-service-b"
GATEWAY_DIR="$SCRIPT_DIR/api-gateway"
EUREKA_URL="http://localhost:8761/actuator/health"
SVC_A_URL="http://localhost:8081/actuator/health"
SVC_B_URL="http://localhost:8082/actuator/health"

# ---------------------------------------------------------------------------
# Helper: wait for a URL to become healthy (up to N seconds)
# Usage: wait_for <url> <label> <pid-var-name> <timeout>
# ---------------------------------------------------------------------------
wait_for() {
  local url="$1" label="$2" pid="$3" timeout="$4"
  echo "      Waiting for $label at $url ..."
  for i in $(seq 1 "$timeout"); do
    if curl -sf "$url" > /dev/null 2>&1; then
      echo "      $label is up."
      return 0
    fi
    if ! kill -0 "$pid" 2>/dev/null; then
      echo "ERROR: $label process died unexpectedly." >&2
      exit 1
    fi
    sleep 1
  done
  echo "ERROR: $label did not become healthy within $timeout seconds." >&2
  return 1
}

# ---------------------------------------------------------------------------
# Cleanup: kill all background processes on exit
# ---------------------------------------------------------------------------
cleanup() {
  echo ""
  echo "Shutting down..."
  kill "$EUREKA_PID"    2>/dev/null || true
  kill "$SVC_A_PID"     2>/dev/null || true
  kill "$SVC_B_PID"     2>/dev/null || true
}
trap cleanup EXIT INT TERM

# ---------------------------------------------------------------------------
# 1. Eureka server
# ---------------------------------------------------------------------------
echo "[1/4] Starting Eureka server..."
(cd "$EUREKA_DIR" && "$MVN" spring-boot:run) &
EUREKA_PID=$!

wait_for "$EUREKA_URL" "Eureka" "$EUREKA_PID" 60

# ---------------------------------------------------------------------------
# 2. user-service-a  (active / production)
# ---------------------------------------------------------------------------
echo "[2/4] Starting user-service-a (active, port 8081)..."
(cd "$USER_SVC_A_DIR" && "$MVN" spring-boot:run) &
SVC_A_PID=$!

wait_for "$SVC_A_URL" "user-service-a" "$SVC_A_PID" 60

# ---------------------------------------------------------------------------
# 3. user-service-b  (passive / canary)
# ---------------------------------------------------------------------------
echo "[3/4] Starting user-service-b (passive, port 8082)..."
(cd "$USER_SVC_B_DIR" && "$MVN" spring-boot:run) &
SVC_B_PID=$!

wait_for "$SVC_B_URL" "user-service-b" "$SVC_B_PID" 60

# ---------------------------------------------------------------------------
# 4. API Gateway (foreground — Ctrl-C stops everything via trap above)
# ---------------------------------------------------------------------------
echo "[4/4] Starting API Gateway..."
echo ""
echo "  Stack is up. Test routing with:"
echo "    # Active instance (no header):"
echo "    curl http://localhost:8080/users/1"
echo ""
echo "    # Passive/canary instance:"
echo "    curl -H 'x-sgb-zone: passive' http://localhost:8080/users/1"
echo ""
(cd "$GATEWAY_DIR" && "$MVN" spring-boot:run)
