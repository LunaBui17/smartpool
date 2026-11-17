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
    echo 'First run: sudo python3 setup-passwords.py PI_LAN_IP' >&2
    exit 1
fi
[[ -f .env.mqtt-policy ]] || { echo 'Missing .env.mqtt-policy; run setup-passwords.py for a new installation.' >&2; exit 1; }
chmod 600 .env
docker volume create smartpool-v2_mqtt-auth >/dev/null
docker volume create smartpool-v2_mqtt-data >/dev/null
pool_compose config --quiet
pool_compose up -d --wait --wait-timeout 600 db mqtt-init mqtt adminer
pool_compose build api
# Never reset data implicitly. A legacy schema stops here with reset instructions.
pool_compose run --rm --no-deps -T api php /opt/smartpool/migrate.php
pool_compose up -d --no-deps --wait --wait-timeout 600 api
bash smoke-test.sh
echo 'Ready. Create the first API admin:'
echo '  docker compose exec api php /opt/smartpool/create_admin.php'
echo 'MQTT uses per-device accounts. Manage them with: sudo python3 mqtt-admin.py status'
