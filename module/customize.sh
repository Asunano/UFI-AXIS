#!/system/bin/sh
# customize.sh - 安装交互

ui_print() {
    echo "$1"
}

ui_print "========================================="
ui_print "  UFI-AXIS-Core Magisk Module"
ui_print "  随身 WiFi 设备系统级后端"
ui_print "========================================="
ui_print ""

# 检查 Android 版本
SDK=$(getprop ro.build.version.sdk)
if [ "$SDK" -lt 31 ]; then
    ui_print "! 需要 Android 12+ (API 31)"
    abort "! 安装失败"
fi

# 检查 Magisk 版本
ui_print "- 检查 Magisk 版本..."
if [ ! -f /data/adb/magisk/util_functions.sh ]; then
    ui_print "! 未检测到 Magisk"
    abort "! 安装失败"
fi

# 复制 APK
ui_print "- 安装 APK..."
mkdir -p $MODPATH/system/app/UfiAxisCore
cp $MODPATH/system/app/UfiAxisCore.apk $MODPATH/system/app/UfiAxisCore/UfiAxisCore.apk

# 创建数据目录
ui_print "- 创建数据目录..."
mkdir -p /data/ufiaxis/db
mkdir -p /data/ufiaxis/logs
mkdir -p /data/ufiaxis/cache
mkdir -p /data/ufiaxis/config

# 设置权限
ui_print "- 设置权限..."
set_perm_recursive $MODPATH 0 0 0755 0644
set_perm $MODPATH/system/app/UfiAxisCore/UfiAxisCore.apk 0 0 0644

ui_print ""
ui_print "========================================="
ui_print "  安装完成！"
ui_print "  重启设备后服务将自动启动"
ui_print "  端口: 8088"
ui_print "========================================="
