#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BUCKET="${OSS_BUCKET:?set OSS_BUCKET}"

# 阿里云 OSS（ossutil）
ossutil cp -r "$ROOT/tiles/" "oss://$BUCKET/poi-tiles/" --update
ossutil cp -r "$ROOT/tiles-gcj/" "oss://$BUCKET/poi-tiles-gcj/" --update

# 如果使用 AWS S3：
# aws s3 sync "$ROOT/tiles/" "s3://$BUCKET/poi-tiles/" --cache-control "public,max-age=604800"
# aws s3 sync "$ROOT/tiles-gcj/" "s3://$BUCKET/poi-tiles-gcj/" --cache-control "public,max-age=604800"

echo "uploaded"
echo "set TILES_PUBLIC_BASE_URL=https://your-cdn/poi-tiles"
echo "set TILES_PUBLIC_GCJ_BASE_URL=https://your-cdn/poi-tiles-gcj"
