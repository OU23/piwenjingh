#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
DEPLOY_DIR="$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)"

docker compose -f "$DEPLOY_DIR/docker-compose.yml" ps
echo
du -sh /opt/toolbox/uploads/files /opt/toolbox/uploads/chunks 2>/dev/null || true
