#!/system/bin/sh
# uninstall.sh - 卸载清理

# 停止服务
am stopservice com.ufi_axis_core/.service.BackendService 2>/dev/null

# 清理数据目录
rm -rf /data/ufiaxis/db 2>/dev/null
rm -rf /data/ufiaxis/logs 2>/dev/null
rm -rf /data/ufiaxis/cache 2>/dev/null
rm -rf /data/ufiaxis/config 2>/dev/null

# 清理应用数据
pm clear com.ufi_axis_core 2>/dev/null

echo "UFI-AXIS-Core uninstalled and cleaned"
