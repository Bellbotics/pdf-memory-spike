# Kubernetes Manifests

Run Spring + Sidecar together in a cluster.

Files
- k8s.yaml, deployment.yaml, service.yaml

Quickstart (with Kind)
```bash
make cluster     # creates kind cluster + local registry
make build       # builds/pushes images to local registry
make deploy      # kubectl apply -f k8s/
make forward     # port-forward spring service to localhost:8033
```
