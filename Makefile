# Makefile (repo root)
# Purpose: One-liners to stand up local cluster, build images, deploy manifests,
# and port-forward the service for testing on localhost:8033.
#
# Usage:
#   make cluster   → create kind cluster + local registry
#   make build     → build & push Spring + sidecar images to local registry
#   make deploy    → apply k8s deployment + service
#   make forward   → port-forward service to localhost:8033
#   make clean     → delete cluster + stop registry
#
# Optional improvement: add `make train` to regenerate pipeline.pkl before build.

KIND_CLUSTER=pdf-ml
REGISTRY_NAME=kind-registry
REGISTRY_PORT=5001
NAMESPACE=pdf-ml

SPRING_IMAGE=localhost:5001/bds-app:dev
SIDECAR_IMAGE=localhost:5001/mem-spike-scorer:dev

# --- 1) Cluster setup -------------------------------------------------

cluster:
	@echo "🚀 Creating kind cluster '$(KIND_CLUSTER)' and local registry '$(REGISTRY_NAME)'..."
	- docker run -d -p $(REGISTRY_PORT):5000 --name $(REGISTRY_NAME) --restart=always registry:2
	kind create cluster --name $(KIND_CLUSTER) --config kind/kind-config.yaml
	- docker network connect "kind" "$(REGISTRY_NAME)" 2>/dev/null || true
	@echo "✅ Cluster ready. Context: kind-$(KIND_CLUSTER)"

# --- 2) Build & push images -------------------------------------------

build:
	@echo "🔨 Building Spring Boot image → $(SPRING_IMAGE)"
	docker build -t $(SPRING_IMAGE) -f spring-app/Dockerfile.spring spring-app
	@echo "🔨 Building FastAPI sidecar image → $(SIDECAR_IMAGE)"
	docker build -t $(SIDECAR_IMAGE) -f sidecar/Dockerfile.sidecar sidecar
	@echo "📦 Pushing images to local registry..."
	docker push $(SPRING_IMAGE)
	docker push $(SIDECAR_IMAGE)
	@echo "✅ Images pushed."

# --- 3) Deploy to Kubernetes ------------------------------------------

deploy:
	@echo "📡 Deploying to namespace $(NAMESPACE)..."
	kubectl create namespace $(NAMESPACE) --dry-run=client -o yaml | kubectl apply -f -
	kubectl apply -f k8s/deployment.yaml -n $(NAMESPACE)
	kubectl apply -f k8s/service.yaml -n $(NAMESPACE)
	@echo "⏳ Waiting for rollout..."
	kubectl -n $(NAMESPACE) rollout status deploy/bds

# --- 4) Port-forward ---------------------------------------------------

forward:
	@echo "🌐 Port-forwarding svc/bds:8033 → localhost:8033 ..."
	kubectl -n $(NAMESPACE) port-forward svc/bds 8033:8033

# --- 5) Cleanup --------------------------------------------------------

clean:
	@echo "🧹 Deleting cluster and local registry..."
	- kind delete cluster --name $(KIND_CLUSTER)
	- docker stop $(REGISTRY_NAME) && docker rm $(REGISTRY_NAME)
	@echo "✅ Cleaned."
