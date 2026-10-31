#!/usr/bin/env bash
set -euo pipefail

RELEASE_ID="${1:-}"
BASE_DIR="${BASE_DIR:-/opt/bgm-guess}"
SERVICE_NAME="${SERVICE_NAME:-bgm-guess}"
HEALTH_URL="${HEALTH_URL:-http://127.0.0.1:8080/api/health}"
KEEP_RELEASES="${KEEP_RELEASES:-5}"

if [[ ! "$RELEASE_ID" =~ ^[A-Za-z0-9._-]+$ ]]; then
  echo "invalid release id: $RELEASE_ID" >&2
  exit 2
fi

RELEASE_DIR="$BASE_DIR/releases/$RELEASE_ID"
CURRENT_LINK="$BASE_DIR/current"

if [[ ! -f "$RELEASE_DIR/app.jar" ]]; then
  echo "missing app.jar: $RELEASE_DIR/app.jar" >&2
  exit 1
fi

if [[ ! -f "$RELEASE_DIR/dist/index.html" ]]; then
  echo "missing dist/index.html: $RELEASE_DIR/dist/index.html" >&2
  exit 1
fi

chmod -R a+rX "$RELEASE_DIR"

PREVIOUS="$(readlink -f "$CURRENT_LINK" 2>/dev/null || true)"

ln -sfn "$RELEASE_DIR" "$CURRENT_LINK.new"
mv -Tf "$CURRENT_LINK.new" "$CURRENT_LINK"

sudo -n systemctl restart "$SERVICE_NAME"

healthy=0
for _ in $(seq 1 30); do
  if curl -fsS --max-time 2 "$HEALTH_URL" >/dev/null 2>&1; then
    healthy=1
    break
  fi
  sleep 2
done

if [[ "$healthy" -ne 1 ]]; then
  echo "health check failed for release $RELEASE_ID" >&2
  if [[ -n "$PREVIOUS" && -d "$PREVIOUS" ]]; then
    echo "rolling back to $PREVIOUS" >&2
    ln -sfn "$PREVIOUS" "$CURRENT_LINK.new"
    mv -Tf "$CURRENT_LINK.new" "$CURRENT_LINK"
    sudo -n systemctl restart "$SERVICE_NAME" || true
  fi
  exit 1
fi

mapfile -t releases < <(find "$BASE_DIR/releases" -mindepth 1 -maxdepth 1 -type d -printf '%f\n' | sort -r)
if [[ "${#releases[@]}" -gt "$KEEP_RELEASES" ]]; then
  for old in "${releases[@]:$KEEP_RELEASES}"; do
    rm -rf "${BASE_DIR:?}/releases/$old"
  done
fi

echo "activated $RELEASE_ID"
