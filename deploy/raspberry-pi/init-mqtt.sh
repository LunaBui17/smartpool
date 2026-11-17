#!/bin/sh
set -eu
umask 077
# No plaintext passwords in argv, environment, logs or rendered Compose output.
mkdir /auth/.accounts-lock 2>/dev/null || { echo 'MQTT account update already running' >&2; exit 1; }
work="/auth/.accounts-$$"
mkdir "$work"
cleanup() { rm -f "$work"/*; rmdir "$work"; rmdir /auth/.accounts-lock; }
trap cleanup EXIT
trap 'exit 1' HUP INT TERM
random_password() { od -An -N24 -tx1 /dev/urandom | tr -d ' \n'; }
render_acl() {
    cat /acl.base > "$work/acl"
    while IFS= read -r id; do
        [ -n "$id" ] || continue
        printf '\nuser %s\n' "$id" >> "$work/acl"
        for suffix in cmd/pump config/desired; do
            printf 'topic read smartpool/devices/%s/%s\n' "$id" "$suffix" >> "$work/acl"
        done
        for suffix in status telemetry ack/command ack/config; do
            printf 'topic write smartpool/devices/%s/%s\n' "$id" "$suffix" >> "$work/acl"
        done
        printf 'topic readwrite smartpool/devices/%s/diagnostics/probe\n' "$id" >> "$work/acl"
    done < "$work/device-ids"
}
commit_files() {
    render_acl
    chown mosquitto:mosquitto "$work/passwords" "$work/acl"
    chmod 600 "$work/passwords"
    chmod 644 "$work/acl"
    mv "$work/passwords" /auth/passwords
    mv "$work/acl" /auth/acl
    mv "$work/device-ids" /auth/device-ids
}
action="${1:-init}"
if [ "$action" = init ]; then
    if [ ! -f /auth/policy-v2 ]; then
        # Old shared accounts are intentionally not imported.
        if [ -f /auth/passwords ]; then
            cp /auth/passwords "/auth/passwords.legacy-$(date +%Y%m%d-%H%M%S)"
        fi
        bridge="$(random_password)"; diag="$(random_password)"
        printf '_smartpool_bridge\n%s\n' "$bridge" > "$work/bridge.credentials"
        printf '_smartpool_diag\n%s\n' "$diag" > "$work/diagnostics.credentials"
        printf '_smartpool_bridge:%s\n_smartpool_diag:%s\n' "$bridge" "$diag" > "$work/passwords"
        mosquitto_passwd -U "$work/passwords"
        : > "$work/device-ids"
        commit_files
        mv "$work/bridge.credentials" /auth/bridge.credentials
        mv "$work/diagnostics.credentials" /auth/diagnostics.credentials
        printf '2\n' > /auth/policy-v2
    else
        test -s /auth/passwords && test -s /auth/acl && test -f /auth/device-ids
        test -s /auth/bridge.credentials && test -s /auth/diagnostics.credentials
        # Refresh ACL from source without resetting accounts/passwords.
        cp /auth/passwords "$work/passwords"
        cp /auth/device-ids "$work/device-ids"
        commit_files
    fi
    echo 'MQTT v2 auth files ready'
    exit 0
fi
test -f /auth/policy-v2 || { echo 'Run mqtt-admin.py init first' >&2; exit 1; }
case "$action" in
    status) printf 'policy=2\nbridge=_smartpool_bridge\ndiagnostics=_smartpool_diag\n'; cat /auth/device-ids; exit 0 ;;
    export-diagnostics) cat /auth/diagnostics.credentials; exit 0 ;;
    add|rotate|revoke) ;;
    *) echo 'Unknown MQTT account action' >&2; exit 1 ;;
esac
id="${2:?device_id required}"
case "$id" in ''|[!A-Za-z0-9]*|*[!A-Za-z0-9._-]*) echo 'Invalid device_id' >&2; exit 1 ;; esac
[ "${#id}" -le 64 ] || exit 1
exists=0
if grep -Fxq "$id" /auth/device-ids; then exists=1; fi
if [ "$action" = add ] && [ "$exists" = 1 ]; then echo 'Device account already exists; use rotate' >&2; exit 1; fi
if [ "$action" = rotate ] && [ "$exists" = 0 ]; then echo 'Device account not found' >&2; exit 1; fi
awk -F: -v u="$id" '$1 != u' /auth/passwords > "$work/passwords"
awk -v u="$id" '$0 != u' /auth/device-ids > "$work/device-ids"
if [ "$action" != revoke ]; then
    IFS= read -r password
    case "$password" in ''|*[!a-f0-9]*) echo 'Invalid generated password' >&2; exit 1 ;; esac
    [ "${#password}" -eq 48 ] || exit 1
    printf '%s:%s\n' "$id" "$password" > "$work/entry"
    mosquitto_passwd -U "$work/entry"
    cat "$work/entry" >> "$work/passwords"
    printf '%s\n' "$id" >> "$work/device-ids"
fi
commit_files
printf 'MQTT account %s: %s\n' "$action" "$id"
