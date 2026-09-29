#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
DEPLOY_DIR="$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)"

exec docker compose -f "$DEPLOY_DIR/docker-compose.yml" logs --tail=200 --follow "$@" toolbox-backend
