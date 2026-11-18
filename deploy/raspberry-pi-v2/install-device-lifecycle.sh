#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")"
if [[ "$EUID" -ne 0 ]]; then exec sudo bash "$PWD/install-device-lifecycle.sh"; fi
deployment_dir="$PWD"
lifecycle_owner="${SUDO_USER:-root}"
getent passwd "$lifecycle_owner" >/dev/null
cat > /etc/systemd/system/smartpool-device-lifecycle.service <<EOF
[Unit]
Description=Smart Pool confirmed-reset account revocation and deletion
After=docker.service network-online.target
Requires=docker.service

[Service]
Type=simple
User=root
WorkingDirectory=$deployment_dir
Environment=SUDO_USER=$lifecycle_owner
ExecStart=/usr/bin/python3 "$deployment_dir/device-lifecycle.py"
Restart=always
RestartSec=5
UMask=0077

[Install]
WantedBy=multi-user.target
EOF
systemctl daemon-reload
systemctl enable --now smartpool-device-lifecycle.service
systemctl restart smartpool-device-lifecycle.service
systemctl is-active smartpool-device-lifecycle.service
