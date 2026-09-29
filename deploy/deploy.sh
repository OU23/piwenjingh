#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
APP_ROOT="${APP_ROOT:-/opt/toolbox/toolbox-frontend}"
RELEASES_DIR="$APP_ROOT/releases"
CURRENT_LINK="$APP_ROOT/dist"
KEEP_RELEASES=2

command -v docker >/dev/null 2>&1 || { echo "Docker is required." >&2; exit 1; }

cd "$PROJECT_ROOT"

mkdir -p "$RELEASES_DIR"
exec 9>"$APP_ROOT/.deploy.lock"
flock -n 9 || { echo "Another deployment is already running." >&2; exit 1; }

"$PROJECT_ROOT/deploy/build.sh"

release_id="$(date -u +%Y%m%dT%H%M%SZ)"
release_dir="$RELEASES_DIR/$release_id"
mkdir -p "$release_dir/dist"
cp -a "$PROJECT_ROOT/dist/." "$release_dir/dist/"

if [[ -e "$CURRENT_LINK" && ! -L "$CURRENT_LINK" ]]; then
  echo "Deployment aborted: $CURRENT_LINK exists but is not a symbolic link." >&2
  echo "Move the legacy directory aside before adopting atomic releases." >&2
  exit 1
fi

next_link="$APP_ROOT/.dist.next"

rm -f "$next_link"
ln -s "$release_dir/dist" "$next_link"
mv -Tf "$next_link" "$CURRENT_LINK"

while IFS= read -r obsolete_release; do
  rm -rf -- "$obsolete_release"
done < <(find "$RELEASES_DIR" -mindepth 1 -maxdepth 1 -type d -printf '%T@ %p\n' | sort -nr | awk -v keep="$KEEP_RELEASES" 'NR > keep { $1=""; sub(/^ /, ""); print }')

echo "Deployed $release_id"
