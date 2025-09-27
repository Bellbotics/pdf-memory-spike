# Tiltfile
# Purpose: Define how Tilt should build Docker images and apply Kubernetes
# manifests for the pdf-memory-spike project. Tilt enables rapid local
# development: it watches files, rebuilds images, and updates Kubernetes
# resources in real-time.
#
# Usage:
#   tilt up         # start Tilt UI and dev loop
#   tilt down       # tear down resources
#
# Notes:
# - Tilt watches the files in the current repo. If you edit code in either the
#   Spring app or the Python sidecar, Tilt rebuilds the respective image and
#   updates your cluster.
# - Make sure your cluster (kind, minikube, etc.) can pull images from
#   localhost:5001 (your local registry).
# - By default, Tilt uses the Dockerfiles to rebuild on every change detected
#   in the build context.

# -------------------------------------------------------------------
# 1. Apply Kubernetes manifests
# -------------------------------------------------------------------
# This tells Tilt to apply the resources defined in k8s.yaml.
# That file should include the Deployment(s), Service(s), and Namespace.
# Every time Tilt rebuilds an image, it will automatically redeploy pods.
k8s_yaml('k8s.yaml')

# -------------------------------------------------------------------
# 2. Define Docker builds
# -------------------------------------------------------------------
# Spring Boot app (Java)
# - image: localhost:5001/bds-app
#   This tag must match the image reference in k8s.yaml.
# - context: '.' (root of repo). If you prefer, point it to 'spring-app'.
# - dockerfile: Dockerfile.spring
docker_build(
    'localhost:5001/bds-app',
    '.',                      # build context
    dockerfile='Dockerfile.spring'
)

# FastAPI sidecar (Python)
# - image: localhost:5001/mem-spike-scorer
# - context: '.' (root). You could narrow to 'sidecar/' if preferred.
# - dockerfile: Dockerfile.sidecar
docker_build(
    'localhost:5001/mem-spike-scorer',
    '.',
    dockerfile='Dockerfile.sidecar'
)
