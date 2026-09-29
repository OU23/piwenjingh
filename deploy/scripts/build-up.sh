#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
DEPLOY_DIR="$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)"
COMPOSE_FILE="$DEPLOY_DIR/docker-compose.yml"

if ! command -v docker >/dev/null 2>&1 || ! docker compose version >/dev/null 2>&1; then
  echo "Docker and Docker Compose v2 are required." >&2
  exit 1
fi

if grep -q 'REPLACE_' "$COMPOSE_FILE"; then
  echo "Replace all REPLACE_* values in $COMPOSE_FILE before deployment." >&2
  exit 1
fi

mkdir -p /opt/toolbox/uploads/files /opt/toolbox/uploads/chunks
if [[ ! -w /opt/toolbox/uploads/files || ! -w /opt/toolbox/uploads/chunks ]]; then
  echo "Docker deployment user cannot write /opt/toolbox/uploads." >&2
  exit 1
fi

docker compose -f "$COMPOSE_FILE" config -q
docker compose -f "$COMPOSE_FILE" up --build --detach

echo "Waiting for the backend health check..."
for _ in $(seq 1 60); do
  if "$SCRIPT_DIR/healthcheck.sh" >/dev/null 2>&1; then
    echo "toolbox-backend is healthy."
    exit 0
  fi
  sleep 2
done

echo "Backend did not become healthy. Run $SCRIPT_DIR/logs.sh for details." >&2
exit 1
