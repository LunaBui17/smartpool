#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")"
source ./compose-common.sh
if [[ "$EUID" -ne 0 ]]; then
    exec sudo bash "$PWD/activate-control-worker.sh"
fi
pool_compose build api control-worker
pool_compose up -d --no-deps --wait --wait-timeout 120 api control-worker
pool_compose exec -T api php -l /var/www/html/pool_api/api_data.php
pool_compose ps api control-worker
