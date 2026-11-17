#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")"
source ./compose-common.sh
# Probe reads API data without inserting samples or sending pump commands.
pool_compose exec -T api php -r '
$raw = file_get_contents("http://127.0.0.1/pool_api/pool_api.php?action=health");
$data = $raw === false ? null : json_decode($raw, true);
if (!is_array($data) || ($data["api_version"] ?? null) !== 2 || ($data["status"] ?? "") !== "success") exit(1);
echo "API v2 and MariaDB health OK (not a permission/integration test)\n";
'
echo 'MQTT diagnostics: run smoke-wss.py explicitly with private diagnostics credentials.'
