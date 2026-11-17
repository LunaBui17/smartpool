#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")"
source ./compose-common.sh
if [[ "$(uname -m)" != aarch64 && "$(uname -m)" != x86_64 ]]; then
    echo 'Use a 64-bit OS (aarch64 on Raspberry Pi); the MariaDB image requires it.' >&2
    exit 1
fi
docker compose version >/dev/null
docker info >/dev/null
if [[ ! -f .env ]]; then
    pi_ip="${1:?Usage: bash deploy.sh PI_LAN_IPV4}"
    if ! ip -4 -o addr show | awk '{print $4}' | cut -d/ -f1 | grep -Fxq "$pi_ip"; then
        echo 'The supplied IPv4 address is not assigned to this machine.' >&2
        exit 1
    fi
    umask 077
    random_password() { od -An -N24 -tx1 /dev/urandom | tr -d ' \n'; }
    {
        printf 'PI_BIND_IP=%s\nHTTP_PORT=80\nMQTT_PORT=1883\n' "$pi_ip"
        printf 'DB_ROOT_PASSWORD=%s\n' "$(random_password)"
        printf 'DB_PASSWORD=%s\n' "$(random_password)"
        printf 'MQTT_ESP_PASSWORD=%s\n' "$(random_password)"
        printf 'MQTT_APP_PASSWORD=%s\n' "$(random_password)"
    } > .env
fi
chmod 600 .env
pool_compose config --quiet
pool_compose up -d --build --wait --wait-timeout 600 db api mqtt-init mqtt
bash smoke-test.sh
echo 'Ready. Create the first API admin:'
echo '  docker compose exec api php /opt/smartpool/create_admin.php'
echo 'MQTT users: esp32 (firmware), android (app). Passwords are in .env.'
