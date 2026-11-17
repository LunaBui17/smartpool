#!/usr/bin/env bash
# Call after changing to the deployment directory. Never print resolved secrets.
pool_compose() {
    docker compose -f compose.yaml -f compose.cloudflare.yaml "$@"
}
