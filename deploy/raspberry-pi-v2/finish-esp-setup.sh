#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")"
source ./compose-common.sh
if [[ $# -ne 3 ]]; then
    echo 'Usage: bash finish-esp-setup.sh DEVICE_ID STUDENT_FULL_NAME STUDENT_CODE' >&2
    exit 2
fi
if [[ "$EUID" -ne 0 ]]; then
    exec sudo bash "$PWD/finish-esp-setup.sh" "$@"
fi
pool_compose build api
pool_compose up -d --no-deps --wait --wait-timeout 120 api
python3 provision-esp.py "$@"
