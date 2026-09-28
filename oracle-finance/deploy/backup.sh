#!/usr/bin/env sh
set -eu
BACKUP_DIR="${BACKUP_DIR:-./backups}"
mkdir -p "$BACKUP_DIR"
STAMP=$(date -u +%Y%m%dT%H%M%SZ)
docker compose exec -T finance-api python -c '
import sqlite3, os
src=os.getenv("DATABASE_PATH","/app/data/finance.db")
dst="/app/data/backup.sqlite"
srcdb=sqlite3.connect(src)
dstdb=sqlite3.connect(dst)
srcdb.backup(dstdb)
dstdb.close()
srcdb.close()
'
docker cp "$(docker compose ps -q finance-api):/app/data/backup.sqlite" "$BACKUP_DIR/finance-$STAMP.sqlite"
echo "Backup written to $BACKUP_DIR/finance-$STAMP.sqlite"
