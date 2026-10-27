#!/usr/bin/env bash
# MySQL 每日备份脚本（配合 cron，如：0 2 * * * /opt/metrics/deploy/backup-mysql.sh >> /var/log/metrics-backup.log 2>&1）
# 备份 metrics_db，gzip 压缩，保留 BACKUP_KEEP_DAYS 天（默认 14）
# 依赖：docker（compose 中的 mysql 容器）；恢复见 docs/部署手册.md
set -euo pipefail

# ===== 可按环境覆盖 =====
CONTAINER_NAME="${CONTAINER_NAME:-metrics-mysql}"
DB_NAME="${DB_NAME:-metrics_db}"
DB_USER="${DB_USER:-root}"
# 推荐从 .env 读取：export $(grep DB_PASSWORD /opt/metrics/.env)
DB_PASSWORD="${DB_PASSWORD:?请设置 DB_PASSWORD 环境变量}"
BACKUP_DIR="${BACKUP_DIR:-/opt/metrics/backups}"
BACKUP_KEEP_DAYS="${BACKUP_KEEP_DAYS:-14}"

mkdir -p "$BACKUP_DIR"
STAMP="$(date +%Y%m%d-%H%M%S)"
FILE="$BACKUP_DIR/${DB_NAME}-${STAMP}.sql.gz"

echo "[$(date '+%F %T')] 开始备份 $DB_NAME -> $FILE"
docker exec "$CONTAINER_NAME" sh -c \
  "exec mysqldump -u$DB_USER -p\"$DB_PASSWORD\" --single-transaction --routines --triggers --default-character-set=utf8mb4 $DB_NAME" \
  | gzip > "$FILE"

# 完整性检查：gzip 可解压且非空
if ! gzip -t "$FILE" || [ ! -s "$FILE" ]; then
  echo "[$(date '+%F %T')] 备份文件校验失败：$FILE" >&2
  rm -f "$FILE"
  exit 1
fi

# 清理过期备份
find "$BACKUP_DIR" -name "${DB_NAME}-*.sql.gz" -mtime +"$BACKUP_KEEP_DAYS" -delete

echo "[$(date '+%F %T')] 备份完成：$FILE（$(du -h "$FILE" | cut -f1)），保留 $BACKUP_KEEP_DAYS 天"
