#!/usr/bin/env bash
set -euo pipefail

curl --fail --silent --show-error http://127.0.0.1:8080/api/system/init-status
echo
