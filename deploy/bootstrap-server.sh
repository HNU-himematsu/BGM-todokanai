#!/usr/bin/env bash
set -euo pipefail

BASE_DIR="${BASE_DIR:-/opt/bgm-guess}"
SERVICE_NAME="${SERVICE_NAME:-bgm-guess}"
SERVICE_USER="${SERVICE_USER:-bgm}"
DEPLOY_USER="${DEPLOY_USER:-deploy}"
INSTALL_SUDOERS="${INSTALL_SUDOERS:-0}"
REPO_DIR="${REPO_DIR:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"

if [[ "$(id -u)" -ne 0 ]]; then
  echo "run this script as root" >&2
  exit 1
fi

if ! id -u "$SERVICE_USER" >/dev/null 2>&1; then
  useradd --system --home "$BASE_DIR" --shell /usr/sbin/nologin "$SERVICE_USER"
fi

if ! id -u "$DEPLOY_USER" >/dev/null 2>&1; then
  useradd --create-home --shell /bin/bash "$DEPLOY_USER"
fi

install -d -m 755 "$BASE_DIR" "$BASE_DIR/releases" "$BASE_DIR/bin"
install -d -m 750 -o "$SERVICE_USER" -g "$SERVICE_USER" "$BASE_DIR/shared" "$BASE_DIR/shared/data"
install -d -m 755 /etc/bgm-guess

chown root:"$DEPLOY_USER" "$BASE_DIR"
chmod 775 "$BASE_DIR"

install -m 644 "$REPO_DIR/deploy/systemd/bgm-guess.service" "/etc/systemd/system/$SERVICE_NAME.service"
install -m 755 "$REPO_DIR/deploy/activate-release.sh" "$BASE_DIR/bin/activate-release.sh"

if [[ ! -f /etc/bgm-guess/bgm-guess.env ]]; then
  install -m 640 -o root -g "$SERVICE_USER" \
    "$REPO_DIR/deploy/systemd/bgm-guess.env.example" /etc/bgm-guess/bgm-guess.env
fi

chown "$DEPLOY_USER:$DEPLOY_USER" "$BASE_DIR/releases"

if [[ "$INSTALL_SUDOERS" == "1" ]]; then
  install -m 440 "$REPO_DIR/deploy/systemd/sudoers-bgm-deploy" /etc/sudoers.d/bgm-deploy
  visudo -cf /etc/sudoers.d/bgm-deploy
fi

systemctl daemon-reload
systemctl enable "$SERVICE_NAME"

cat <<EOF
server layout is ready.

next steps:
1. put timeline.json, timeline-online.json, questions.json and leaderboard files in $BASE_DIR/shared/data (no audio file)
2. edit /etc/bgm-guess/bgm-guess.env
3. install the nginx site from deploy/nginx-game.conf.example
4. add the deploy user public key to /home/$DEPLOY_USER/.ssh/authorized_keys
5. run the Deploy workflow from GitHub Actions
EOF
