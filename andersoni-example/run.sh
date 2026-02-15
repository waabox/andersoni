#!/usr/bin/env bash
set -euo pipefail

# Andersoni Example - Full Stack Demo
# Starts all infrastructure, builds the app, deploys 3 pods to K3s.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_DIR="$SCRIPT_DIR/devcontainer"
K8S_DIR="$SCRIPT_DIR/k8s"
COMPOSE="docker compose -f $COMPOSE_DIR/docker-compose.yml"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

info()  { echo -e "${CYAN}[INFO]${NC}  $*"; }
ok()    { echo -e "${GREEN}[OK]${NC}    $*"; }
warn()  { echo -e "${YELLOW}[WARN]${NC}  $*"; }
fail()  { echo -e "${RED}[FAIL]${NC}  $*"; exit 1; }

wait_for_healthy() {
  local service=$1
  local max_attempts=${2:-30}
  local attempt=0
  info "Waiting for $service to be healthy..."
  while [ $attempt -lt $max_attempts ]; do
    if $COMPOSE ps "$service" 2>/dev/null | grep -q "healthy"; then
      ok "$service is healthy"
      return 0
    fi
    attempt=$((attempt + 1))
    sleep 2
  done
  fail "$service did not become healthy after $((max_attempts * 2))s"
}

cleanup() {
  echo ""
  warn "Shutting down..."
  $COMPOSE down -v 2>/dev/null || true
}

# ---------------------------------------------------------------------------
# Parse args
# ---------------------------------------------------------------------------
case "${1:-up}" in
  up|start)  ACTION="up" ;;
  down|stop) $COMPOSE down -v; ok "Everything stopped."; exit 0 ;;
  logs)      $COMPOSE logs -f "${2:-}"; exit 0 ;;
  status)
    $COMPOSE ps
    echo ""
    if command -v kubectl &>/dev/null; then
      KUBECONFIG=$(docker volume inspect devcontainer_k3s-output -f '{{.Mountpoint}}')/kubeconfig.yaml
      if [ -f "$KUBECONFIG" ]; then
        kubectl --kubeconfig="$KUBECONFIG" -n andersoni-example get pods 2>/dev/null || true
      fi
    fi
    exit 0
    ;;
  *)         echo "Usage: $0 {up|down|logs|status}"; exit 1 ;;
esac

trap cleanup INT TERM

# ---------------------------------------------------------------------------
# Step 1: Start infrastructure
# ---------------------------------------------------------------------------
echo ""
echo -e "${CYAN}========================================${NC}"
echo -e "${CYAN}  Andersoni Example - Full Stack Demo   ${NC}"
echo -e "${CYAN}========================================${NC}"
echo ""

info "Starting infrastructure (PostgreSQL, Kafka, MinIO, K3s)..."
$COMPOSE up -d postgres kafka minio minio-init k3s

# ---------------------------------------------------------------------------
# Step 2: Wait for services
# ---------------------------------------------------------------------------
wait_for_healthy postgres
wait_for_healthy kafka 60
wait_for_healthy minio

info "Waiting for K3s to be ready..."
K3S_READY=false
for i in $(seq 1 60); do
  KUBECONFIG_PATH=$($COMPOSE exec k3s cat /output/kubeconfig.yaml 2>/dev/null) && K3S_READY=true && break
  sleep 3
done

if [ "$K3S_READY" = false ]; then
  fail "K3s did not start in time"
fi

# Extract kubeconfig to a temp file
KUBECONFIG_FILE=$(mktemp)
$COMPOSE exec k3s cat /output/kubeconfig.yaml > "$KUBECONFIG_FILE"

# Fix the server address (K3s writes 127.0.0.1 but we need localhost:6443)
sed -i.bak 's|server:.*|server: https://localhost:6443|' "$KUBECONFIG_FILE" 2>/dev/null || \
  sed -i '' 's|server:.*|server: https://localhost:6443|' "$KUBECONFIG_FILE"
rm -f "${KUBECONFIG_FILE}.bak"

export KUBECONFIG="$KUBECONFIG_FILE"

# Wait for K3s node to be Ready
info "Waiting for K3s node to be Ready..."
for i in $(seq 1 30); do
  if kubectl get nodes 2>/dev/null | grep -q " Ready"; then
    ok "K3s node is Ready"
    break
  fi
  sleep 3
done

# ---------------------------------------------------------------------------
# Step 3: Build the application
# ---------------------------------------------------------------------------
info "Building the application..."
cd "$SCRIPT_DIR"
mvn clean package -DskipTests -q || fail "Maven build failed"
ok "Application built"

# ---------------------------------------------------------------------------
# Step 4: Build Docker image and import into K3s
# ---------------------------------------------------------------------------
info "Building Docker image..."
docker build -t andersoni-example:latest "$SCRIPT_DIR" -q || fail "Docker build failed"
ok "Docker image built"

info "Importing image into K3s..."
docker save andersoni-example:latest | docker exec -i "$($COMPOSE ps -q k3s)" ctr images import - >/dev/null 2>&1
ok "Image imported into K3s"

# ---------------------------------------------------------------------------
# Step 5: Deploy to K8s
# ---------------------------------------------------------------------------
info "Deploying to K8s (3 replicas)..."
kubectl apply -f "$K8S_DIR/namespace.yaml"
kubectl apply -f "$K8S_DIR/rbac.yaml"
kubectl apply -f "$K8S_DIR/deployment.yaml"
kubectl apply -f "$K8S_DIR/service.yaml"
ok "K8s manifests applied"

# ---------------------------------------------------------------------------
# Step 6: Wait for pods
# ---------------------------------------------------------------------------
info "Waiting for pods to be ready (this may take ~60s)..."
kubectl -n andersoni-example wait --for=condition=Ready pod --all --timeout=120s 2>/dev/null || {
  warn "Not all pods ready yet. Current status:"
  kubectl -n andersoni-example get pods
  warn "Some pods may still be starting. Check with: $0 status"
}

# ---------------------------------------------------------------------------
# Step 7: Show status
# ---------------------------------------------------------------------------
echo ""
echo -e "${GREEN}========================================${NC}"
echo -e "${GREEN}  Demo is running!                      ${NC}"
echo -e "${GREEN}========================================${NC}"
echo ""
kubectl -n andersoni-example get pods
echo ""
info "Endpoints:"
echo "  Search:  curl http://localhost:30080/events/search?index=by-sport&key=FOOTBALL"
echo "  Info:    curl http://localhost:30080/events/info"
echo "  Refresh: curl -X POST http://localhost:30080/events/refresh"
echo ""
info "Management:"
echo "  Status:  $0 status"
echo "  Logs:    $0 logs"
echo "  Stop:    $0 down"
echo ""
info "KUBECONFIG=$KUBECONFIG_FILE"
echo "  kubectl --kubeconfig=$KUBECONFIG_FILE -n andersoni-example get pods"
echo ""
