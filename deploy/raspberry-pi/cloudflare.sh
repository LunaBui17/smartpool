#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")"
source ./compose-common.sh
action="${1:-start}"
case "$action" in start|status|stop) ;; *) echo 'Usage: sudo bash cloudflare.sh [start|status|stop]' >&2; exit 1 ;; esac
if [[ "$EUID" -ne 0 ]]; then exec sudo bash "$PWD/cloudflare.sh" "$action"; fi
target_user="${SUDO_USER:-root}"
target_home="$(getent passwd "$target_user" | cut -d: -f6)"
if [[ ! "$target_user" =~ ^[a-zA-Z0-9_-]+$ || ! "$target_home" =~ ^/[a-zA-Z0-9_./-]+$ ]]; then
    echo 'Unsupported username or home path.' >&2; exit 1
fi
output="$target_home/smartpool-endpoints.txt"
export_once() { python3 "$PWD/cloudflare-endpoints.py" --user "$target_user" --output "$output"; }
case "$action" in
    start)
        [[ -f .env ]] || { echo 'Run pool.sh up PI_LAN_IP first.' >&2; exit 1; }
        for service in api db mqtt; do
            pool_compose ps --status running --services | grep -Fxq "$service" || {
                echo "Start the backend first: $service is not running." >&2; exit 1;
            }
        done
        pool_compose up -d --no-deps cloudflare
        install -d -m 755 /usr/local/lib/smartpool
        install -m 644 cloudflare-endpoints.py /usr/local/lib/smartpool/cloudflare-endpoints.py
        cat > /etc/systemd/system/smartpool-cloudflare-endpoints.service <<EOF
[Unit]
Description=Export Smart Pool Cloudflare URLs to the user's home
After=network-online.target docker.service
Wants=network-online.target

[Service]
Type=simple
ExecStart=/usr/bin/python3 /usr/local/lib/smartpool/cloudflare-endpoints.py --watch --user $target_user --output $output
Restart=on-failure
RestartSec=5
NoNewPrivileges=true

[Install]
WantedBy=multi-user.target
EOF
        systemctl daemon-reload
        export_once
        systemctl enable --now smartpool-cloudflare-endpoints.service
        systemctl restart smartpool-cloudflare-endpoints.service
        for attempt in {1..30}; do
            if [[ -f "$output" ]] && grep -qx 'status=online' "$output"; then
                cat "$output"
                echo "URLs refresh automatically every 10 seconds: $output"
                exit 0
            fi
            sleep 2
        done
        echo "Quick Tunnel is not ready yet; check pool.sh logs cloudflare and $output." >&2
        exit 1
        ;;
    stop)
        if [[ -f .env ]]; then pool_compose stop cloudflare; fi
        if systemctl cat smartpool-cloudflare-endpoints.service >/dev/null 2>&1; then
            systemctl disable --now smartpool-cloudflare-endpoints.service
        fi
        if [[ -f "$output" ]]; then export_once; fi
        ;;
    status)
        pool_compose ps cloudflare
        [[ ! -f "$output" ]] || cat "$output"
        systemctl --no-pager status smartpool-cloudflare-endpoints.service
        ;;
esac
