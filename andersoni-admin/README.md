# Andersoni Admin Console

Standalone debugging and admin dashboard to monitor Andersoni instances
running in a Kubernetes cluster.

## Requirements

- **Java 21**
- **Maven 3.8+**
- **Kubernetes cluster** with access via kubeconfig or in-cluster credentials
- The cluster must have pods labeled for discovery (default: `app=andersoni-example`)
- The target pods must expose an HTTP info endpoint (default: `/events/info` on port 8080)
- A K8s Lease must exist for leader detection (default: `andersoni-example-leader`)

### RBAC Permissions

The service account (or kubeconfig user) needs:

| Resource | Verbs | API Group |
|----------|-------|-----------|
| pods | list | core |
| pods/proxy | get | core |
| leases | get | coordination.k8s.io |

## Build

```bash
cd andersoni-admin
mvn clean package
```

## Run

### Local (with kubeconfig from the example demo)

```bash
export KUBECONFIG=$(ls -t /tmp/andersoni-kubeconfig-* | head -1)
mvn spring-boot:run
```

Open http://localhost:9090

### Environment Variables

| Variable | Default | Description |
|----------|---------|-------------|
| `ADMIN_NAMESPACE` | `andersoni-example` | K8s namespace to monitor |
| `ADMIN_LABEL_SELECTOR` | `app=andersoni-example` | Pod label selector |
| `ADMIN_LEASE_NAME` | `andersoni-example-leader` | Lease name for leader detection |
| `ADMIN_INFO_PATH` | `/events/info` | HTTP path to call on each pod |

## How It Works

1. Connects to the K8s API (auto-detects in-cluster vs kubeconfig)
2. Reads the K8s Lease to identify the current leader
3. Lists pods matching the label selector
4. Proxies HTTP requests to each pod through the K8s API server
5. Serves a Vue.js + Tailwind CSS dashboard on port 9090
