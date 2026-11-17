#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")"
source ./compose-common.sh
umask 077
mkdir -p backups
backup="backups/pool_db-$(date +%Y%m%d-%H%M%S).sql.gz"
pool_compose exec -T db sh -ec 'MYSQL_PWD="$MARIADB_PASSWORD" exec mariadb-dump -u "$MARIADB_USER" --single-transaction --quick --skip-lock-tables "$MARIADB_DATABASE"' | gzip > "$backup"
echo "Saved $backup; also keep a secure copy of .env off the Pi."
