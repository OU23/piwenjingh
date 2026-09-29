#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
IMAGE="${NODE_BUILD_IMAGE:-node:20-bookworm}"

command -v docker >/dev/null 2>&1 || {
  echo "Docker is required to build the frontend." >&2
  exit 1
}

test -f "$PROJECT_ROOT/package-lock.json" || {
  echo "package-lock.json is required; cannot run a reproducible npm ci build." >&2
  exit 1
}

echo "Building with ${IMAGE}..."
docker run --rm \
  --user "$(id -u):$(id -g)" \
  --env npm_config_cache=/tmp/npm-cache \
  --volume "$PROJECT_ROOT:/workspace" \
  --tmpfs /workspace/node_modules:exec,mode=777 \
  --workdir /workspace \
  "$IMAGE" \
  sh -c 'npm ci && npm run build'

test -f "$PROJECT_ROOT/dist/index.html" || {
  echo "Build completed without dist/index.html." >&2
  exit 1
}

echo "Build output: $PROJECT_ROOT/dist"
