#!/system/bin/sh
# post-fs-data.sh - 早期启动（挂载 + SELinux 补丁）
# 在 post-fs-data 阶段执行，用于挂载 APK 和 SELinux 补丁

MODDIR=${0%/*}

# 挂载 APK 到 /system/app
mount -o bind $MODDIR/system/app /system/app/UfiAxisCore 2>/dev/null

# SELinux 补丁（如果需要）
if [ -f $MODDIR/sepolicy/sepolicy.rule ]; then
    magiskpolicy --load-rule $MODDIR/sepolicy/sepolicy.rule 2>/dev/null
fi
