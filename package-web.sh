#!/usr/bin/env bash
# 一键构建并打包 web 前端为 web-<version>.zip（版本取自根目录 version.json 的 web.version）
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WEB_DIR="$SCRIPT_DIR/web"

echo "==> 进入 $WEB_DIR"
cd "$WEB_DIR"

echo "==> 构建前端 (npm run build)"
npm run build

# 从 version.json 读取 web.version
VERSION="$(node -e "console.log(require('../version.json').web.version)")"
if [ -z "${VERSION:-}" ]; then
  echo "[错误] 无法读取 version.json 的 web.version" >&2
  exit 1
fi

OUT="$SCRIPT_DIR/web-$VERSION.zip"

echo "==> 打包 dist/* -> $OUT"
rm -f "$OUT"
cd dist
zip -r "$OUT" . -x "*.DS_Store"
cd ..

echo "[完成] 已生成 $OUT"
