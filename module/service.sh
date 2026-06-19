#!/system/bin/sh
# service.sh - 开机服务启动
# 在 Android 系统启动完成后执行

MODDIR=${0%/*}

# 等待系统启动完成
while [ "$(getprop sys.boot_completed)" != "1" ]; do
    sleep 1
done

# 启动 BackendService
am start-foreground-service -n com.ufi_axis_core/.service.BackendService 2>/dev/null

# 等待服务启动
sleep 2

# 验证服务是否运行
if pgrep -f "com.ufi_axis_core" > /dev/null; then
    echo "UFI-AXIS-Core service started successfully"
else
    echo "UFI-AXIS-Core service failed to start"
fi
