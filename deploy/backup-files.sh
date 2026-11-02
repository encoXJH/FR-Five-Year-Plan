#!/usr/bin/env bash
# 文件内容备份：LOCAL 打包数据卷；MINIO 通过 S3 协议镜像 bucket。
set -euo pipefail

FILE_STORAGE_TYPE="${FILE_STORAGE_TYPE:-LOCAL}"
BACKUP_DIR="${BACKUP_DIR:-/opt/metrics/backups}"
BACKUP_KEEP_DAYS="${BACKUP_KEEP_DAYS:-14}"
STAMP="$(date +%Y%m%d-%H%M%S)"
mkdir -p "$BACKUP_DIR"

if [ "$FILE_STORAGE_TYPE" = "LOCAL" ]; then
  VOLUME_NAME="${FILE_VOLUME_NAME:-metrics-files}"
  TARGET="$BACKUP_DIR/files-$STAMP.tgz"
  docker run --rm -v "$VOLUME_NAME:/data:ro" -v "$BACKUP_DIR:/backup" alpine \
    tar czf "/backup/files-$STAMP.tgz" -C /data .
  if [ ! -s "$TARGET" ] || ! tar tzf "$TARGET" >/dev/null; then
    echo "文件备份校验失败：$TARGET" >&2
    rm -f "$TARGET"
    exit 1
  fi
elif [ "$FILE_STORAGE_TYPE" = "MINIO" ]; then
  MINIO_PUBLIC_ENDPOINT="${MINIO_PUBLIC_ENDPOINT:-http://127.0.0.1:9000}"
  MINIO_BUCKET="${MINIO_BUCKET:-metrics-files}"
  MINIO_ACCESS_KEY="${MINIO_ACCESS_KEY:?请设置 MINIO_ACCESS_KEY}"
  MINIO_SECRET_KEY="${MINIO_SECRET_KEY:?请设置 MINIO_SECRET_KEY}"
  TARGET="$BACKUP_DIR/$MINIO_BUCKET-$STAMP"
  mkdir -p "$TARGET"
  docker run --rm --network host -v "$BACKUP_DIR:/backup" --entrypoint sh minio/mc -c \
    "mc alias set backup-source '$MINIO_PUBLIC_ENDPOINT' '$MINIO_ACCESS_KEY' '$MINIO_SECRET_KEY' >/dev/null && mc mirror --overwrite 'backup-source/$MINIO_BUCKET' '/backup/$MINIO_BUCKET-$STAMP'"
  if [ -z "$(find "$TARGET" -type f -print -quit)" ]; then
    echo "MinIO 备份校验失败：$TARGET 为空" >&2
    exit 1
  fi
else
  echo "不支持的 FILE_STORAGE_TYPE：$FILE_STORAGE_TYPE" >&2
  exit 1
fi

find "$BACKUP_DIR" -maxdepth 1 \( -name 'files-*.tgz' -o -name "${MINIO_BUCKET:-metrics-files}-*" \) \
  -mtime +"$BACKUP_KEEP_DAYS" -exec rm -rf {} +
echo "文件备份完成，保留 $BACKUP_KEEP_DAYS 天"
