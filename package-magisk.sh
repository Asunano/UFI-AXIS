#!/bin/bash
# package-magisk.sh - 打包 Magisk 模块

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MODULE_DIR="$SCRIPT_DIR/module"
APK_DIR="$SCRIPT_DIR/app/build/outputs/apk/release"
OUTPUT_DIR="$SCRIPT_DIR/build/outputs"

# 检查 APK 是否存在
if [ ! -f "$APK_DIR/app-release-unsigned.apk" ]; then
    echo "Error: Release APK not found. Run './gradlew assembleRelease' first."
    exit 1
fi

# 创建输出目录
mkdir -p "$OUTPUT_DIR"

# 创建临时目录
TEMP_DIR=$(mktemp -d)
trap "rm -rf $TEMP_DIR" EXIT

# 复制模块文件
cp -r "$MODULE_DIR"/* "$TEMP_DIR/"

# 创建 APK 目录并复制 APK
mkdir -p "$TEMP_DIR/system/app/UfiAxisCore"
cp "$APK_DIR/app-release-unsigned.apk" "$TEMP_DIR/system/app/UfiAxisCore/UfiAxisCore.apk"

# 获取版本号
VERSION=$(grep "version=" "$TEMP_DIR/module.prop" | cut -d'=' -f2)

# 创建 ZIP
cd "$TEMP_DIR"
zip -r "$OUTPUT_DIR/UFI-AXIS-Core-${VERSION}.zip" . -x "*.git*"
cd "$SCRIPT_DIR"

echo "Magisk module packaged: $OUTPUT_DIR/UFI-AXIS-Core-${VERSION}.zip"
