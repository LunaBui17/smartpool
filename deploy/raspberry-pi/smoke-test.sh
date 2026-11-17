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
pool_compose run --rm --no-deps -T --entrypoint /bin/sh mqtt-init -ec '
trap "rm -f /tmp/probe" EXIT
mosquitto_sub -h mqtt -u android -P "$MQTT_APP_PASSWORD" -t smartpool/diagnostics/probe -q 1 -C 1 -W 15 > /tmp/probe &
subscriber=$!
sleep 2
mosquitto_pub -h mqtt -u android -P "$MQTT_APP_PASSWORD" -t smartpool/diagnostics/probe -q 1 -m smartpool-smoke-ok
wait "$subscriber"
grep -qx smartpool-smoke-ok /tmp/probe
echo "MQTT authenticated publish/subscribe OK"
'
