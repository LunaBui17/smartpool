#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")"
source ./compose-common.sh

usage() {
    cat <<'EOF'
Usage: sudo bash pool.sh COMMAND [ARGS]

  up [PI_LAN_IP]    Install Docker if needed, build/start backend and tunnel
                   The IP is required only before .env has been created.
  stop             Stop all containers and the endpoint exporter; keep data
  restart          Stop everything, then build/start again
  status           Show containers, exporter state and saved TCP addresses
  logs [SERVICE]   Follow logs (Ctrl+C exits logs without stopping services)
  endpoints        Read the active tunnel's URL file in your home
  tunnel           Start/check Cloudflare Quick Tunnel
  admin            Create the first API admin interactively
  backup           Back up MariaDB
  help             Show this help

Examples:
  sudo bash pool.sh up 192.168.60.87
  sudo bash pool.sh up
  sudo bash pool.sh logs
  sudo bash pool.sh logs mqtt
  sudo bash pool.sh logs cloudflare
  sudo bash pool.sh tunnel
EOF
}

action="${1:-help}"
if [[ $# -gt 0 ]]; then shift; fi
case "$action" in
    help|-h|--help) usage; exit 0 ;;
    up|stop|restart|status|logs|endpoints|tunnel|admin|backup) ;;
    *) usage >&2; exit 1 ;;
esac
if [[ "$EUID" -ne 0 ]]; then
    exec sudo bash "$PWD/pool.sh" "$action" "$@"
fi

dc() {
    pool_compose "$@"
}
show_endpoints() {
    local target_home
    target_home="$(getent passwd "${SUDO_USER:-root}" | cut -d: -f6)"
    local filename=smartpool-v2-endpoints.txt
    if [[ -f "$target_home/$filename" ]]; then
        echo "Public addresses: $target_home/$filename"
        cat "$target_home/$filename"
    else
        echo 'No endpoint file yet. Start the stack with: sudo bash pool.sh up'
    fi
}
up_all() {
    if [[ ! -f .env && $# -eq 0 ]]; then
        echo 'First run: sudo bash pool.sh up PI_LAN_IP' >&2
        exit 1
    fi
    bash install-docker.sh
    bash deploy.sh "$@"
    bash cloudflare.sh start
}
stop_all() {
    local result=0
    # Always try stopping the backend even if the tunnel/exporter stop fails.
    bash cloudflare.sh stop || result=$?
    if [[ -f .env ]]; then dc stop || result=$?; fi
    return "$result"
}

case "$action" in
    up) up_all "$@" ;;
    stop) stop_all ;;
    restart) stop_all; up_all "$@" ;;
    status)
        if [[ -f .env ]]; then dc ps -a; else echo 'Backend has not been configured.'; fi
        echo 'Tunnel: Cloudflare Quick Tunnel'
        systemctl is-active smartpool-v2-cloudflare-endpoints.service || true
        show_endpoints
        ;;
    logs) dc logs --tail=100 --follow "$@" ;;
    endpoints) show_endpoints ;;
    tunnel)
        bash cloudflare.sh start
        ;;
    admin) dc exec api php /opt/smartpool/create_admin.php ;;
    backup) bash backup.sh ;;
esac
