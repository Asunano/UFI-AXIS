#!/system/bin/sh
# =============================================================================
# ufi_keepalive.sh — UFI-AXIS Core 看门狗（独立 shell 进程保活）v2
#
# 设计约束：POSIX sh（设备 shell 为 mksh/toybox），禁止 bash 特性。
#
# 职责：
# - 以独立进程常驻，每 15 秒检测 Core 的 BackendService 是否在 AMS 服务表里；
# - 服务不在时，通过 am start-foreground-service --user 0 重新拉起（老 ROM 回退 startservice）；
# - 更新冲突处理：检测到更新锁或 staging APK 时静默跳过，避免中断安装；
# - 启动宽限期：首次启动后等待 60s 再开始检测，给 Core 充分的初始化时间；
# - 连续失败退避：Core 连续启动失败时逐步拉长检测间隔（15s → 30s → 60s）；
# - 日志：统一写到 Download/UFI-AXIS/log/keepalive/keepalive.log。
#
# 部署：由 BootReceiver 开机直接部署（独立于 BackendService，解决鸡生蛋问题）；
#       BackendService.ensureKeepAliveWatchdog() 作为补充部署（幂等）+ 每 5 分钟自愈复查。
# 防重入：mkdir 原子锁 + 锁内 pid 文件；**陈旧锁自动接管**（见 acquire_lock）。
# =============================================================================
LOCK=/data/local/tmp/ufi_keepalive.lock
PIDFILE=/data/local/tmp/ufi_keepalive.lock/pid
CORE_PKG=com.ufi_axis_core
CORE_SVC=com.ufi_axis_core/.service.BackendService
UPDATE_LOCK=/data/local/tmp/ufi_update.lock
STAGE_APK=/data/local/tmp/ufi-core-stage.apk
# 用户主动停止标记（BackendService.onDestroy 在 explicitStop 分支落下，正常启动时删除）：
# 存在时看门狗不得拉起 Core，否则"停止服务"会被自己的保活机制撤销。
STOP_FLAG=/data/local/tmp/ufi_core_stopped
INTERVAL=15
MAX_INTERVAL=60
BOOT_GRACE_PERIOD=60
MAX_LOG_LINES=2000

# 日志分类目录：Download/UFI-AXIS/log/keepalive/keepalive.log（替代 /data/local/tmp/ufi_keepalive.log）
LOG_DIR=/sdcard/Download/UFI-AXIS/log/keepalive
[ -d /storage/emulated/0/Download ] && LOG_DIR=/storage/emulated/0/Download/UFI-AXIS/log/keepalive
mkdir -p "$LOG_DIR" 2>/dev/null
LOG="$LOG_DIR/keepalive.log"

# 普通日志行：单写 LOG
log() { echo "$(date '+%H:%M:%S') $*" | tee -a "$LOG" 2>/dev/null; }

# 简单日志滚动：超过 MAX_LOG_LINES 时保留后半部分
rotate_log() {
  for f in "$LOG"; do
    if [ -f "$f" ]; then
      _lines=$(wc -l < "$f" 2>/dev/null)
      if [ -n "$_lines" ] && [ "$_lines" -gt "$MAX_LOG_LINES" ] 2>/dev/null; then
        tail -n "$MAX_LOG_LINES" "$f" > "${f}.tmp" 2>/dev/null && mv "${f}.tmp" "$f" 2>/dev/null
      fi
    fi
  done
}

# 判断 Core 进程是否存活（优先 pgrep，回退 ps）
core_running() {
  if command -v pgrep >/dev/null 2>&1; then
    pgrep -f "$CORE_PKG" >/dev/null 2>&1
  else
    ps 2>/dev/null | grep -q "$CORE_PKG"
  fi
}

# BackendService 是否真在跑。两个细节：
# ① 只判进程不够 —— 进程可能因为 MainActivity/其它组件被拉起而存活，后端服务却没跑
#    （升级重启、autoStartOnBoot 关闭等），这种"进程活着但服务没了"旧实现检测不到；
# ② 只 grep 'BackendService' 也不够 —— force-stop 后 AMS 里还留着正在销毁的记录，
#    会被误判成在跑。落到记录块内的 isForeground=true 才是"真的起来了"。
core_service_running() {
  dumpsys activity services "$CORE_PKG" 2>/dev/null \
    | grep -A24 'ServiceRecord.*BackendService' \
    | grep -q 'isForeground=true'
}

# 拉起后端服务。两个必需条件（缺一个都起不来，实测于 F50/Android 14）：
# ① `-f 0x00000020` = FLAG_INCLUDE_STOPPED_PACKAGES：包被 force-stop 过就处于 stopped
#    状态，不带该 flag 的 Intent 匹配不到包内组件（am 回 "Not found; no service started."）；
# ② BackendService 是前台服务，后台 `am startservice` 会被 AMS 拒
#    （"app is in background uid null"，退出码仍为 0），必须用 start-foreground-service。
start_core_service() {
  _sout=$(am start-foreground-service --user 0 -f 0x00000020 -n "$CORE_SVC" 2>&1)
  case "$_sout" in
    *Error*|*error*|*Exception*|*Unknown*)
      log "[RESTART] start-foreground-service rejected: ${_sout:-<empty>}"
      _sout=$(am startservice --user 0 -f 0x00000020 -n "$CORE_SVC" 2>&1)
      log "[RESTART] startservice fallback: ${_sout:-<empty>}"
      ;;
    *) log "[RESTART] start-foreground-service ok: ${_sout:-<empty>}";;
  esac
}

# 检查是否处于更新状态（多条件检测，比单一锁文件更可靠）
is_updating() {
  # 条件1: 更新脚本的锁目录（mkdir 原子锁）
  if [ -d "$UPDATE_LOCK" ]; then return 0; fi
  # 条件2: staging APK 存在（更新脚本复制到 /data/local/tmp 的待安装 APK）
  if [ -f "$STAGE_APK" ]; then return 0; fi
  return 1
}

# 原子锁 + 陈旧锁接管。
#
# 2026-09-04 修一个会让看门狗**永久失效**的缺陷：原实现只有 `mkdir "$LOCK"`，失败即退出。
# 而 `trap ... EXIT` 只在正常退出/被 TERM 时跑 —— 被 SIGKILL（LMK、phantom process killer）
# 或掉电带走时锁目录留在 /data/local/tmp（该目录跨重启保留）。于是此后：
#   - 脚本自己起不来（mkdir 失败 → exit 1）；
#   - Kotlin 侧两个部署点也都以"锁存在 = 已在运行"为由跳过启动。
# 结果就是「看门狗看着装好了，实际上再也没起来过」。
# 现在锁里记 pid，接管前先看那个 pid 还活着不活着（且 cmdline 确实是本脚本）。
acquire_lock() {
  if mkdir "$LOCK" 2>/dev/null; then
    echo $$ > "$PIDFILE" 2>/dev/null
    return 0
  fi
  _old=$(cat "$PIDFILE" 2>/dev/null)
  if [ -n "$_old" ] && [ -d "/proc/$_old" ] &&
     tr '\0' ' ' < "/proc/$_old/cmdline" 2>/dev/null | grep -q ufi_keepalive; then
    return 1
  fi
  echo "$(date) stale lock (pid=${_old:-none}), taking over" >> "$LOG" 2>/dev/null
  rm -rf "$LOCK" 2>/dev/null
  if mkdir "$LOCK" 2>/dev/null; then
    echo $$ > "$PIDFILE" 2>/dev/null
    return 0
  fi
  return 1
}

if ! acquire_lock; then
  echo "$(date) keepalive already running, exit" >> "$LOG" 2>/dev/null
  exit 1
fi
trap 'rm -rf "$LOCK" 2>/dev/null' EXIT

rotate_log
log "=== UFI keepalive v2 start pid=$$ ==="
log "[ENV] uid=$(id -u) interval=${INTERVAL}s grace=${BOOT_GRACE_PERIOD}s"

# ── 启动宽限期：给 Core 充分的初始化时间，不急于检测 ──
log "[GRACE] waiting ${BOOT_GRACE_PERIOD}s before starting detection..."
_sleep_count=0
while [ $_sleep_count -lt "$BOOT_GRACE_PERIOD" ]; do
  sleep 1
  _sleep_count=$((_sleep_count + 1))
  # 每 15s 报告一次进度
  if [ $((_sleep_count % 15)) -eq 0 ]; then
    log "[GRACE] ${_sleep_count}/${BOOT_GRACE_PERIOD}s elapsed"
  fi
done
log "[GRACE] grace period ended, starting detection"

# ── 主循环 ──
_consecutive_fails=0
_current_interval=$INTERVAL

while true; do
  rotate_log

  # ① 更新冲突检测：更新中则跳过本轮
  if is_updating; then
    log "[SKIP] update in progress (lock=$(test -d "$UPDATE_LOCK" && echo Y || echo N), stage=$(test -f "$STAGE_APK" && echo Y || echo N)), skip"
    _consecutive_fails=0
    _current_interval=$INTERVAL
    sleep "$_current_interval"
    continue
  fi

  # ②用户主动停止：不拉起。标记由 BackendService 落/删，重启服务即恢复保活。
  if [ -f "$STOP_FLAG" ]; then
    log "[SKIP] user stopped Core (flag=$STOP_FLAG), not restarting"
    _consecutive_fails=0
    _current_interval=$INTERVAL
    sleep "$_current_interval"
    continue
  fi

  # ③ Core 存活检测（进程 + 服务都要在）
  if core_service_running; then
    # Core 存活，重置退避计数
    if [ "$_consecutive_fails" -gt 0 ]; then
      log "[OK] Core recovered, reset interval to ${INTERVAL}s"
    fi
    _consecutive_fails=0
    _current_interval=$INTERVAL
  else
    # 服务不在，尝试拉起
    _consecutive_fails=$((_consecutive_fails + 1))
    log "[RESTART] BackendService not running (fail #$_consecutive_fails, process alive=$(core_running && echo Y || echo N)), starting $CORE_SVC"
    start_core_service

    # 退避策略：连续失败时逐步拉长间隔（15s → 30s → 60s）
    if [ "$_consecutive_fails" -ge 4 ]; then
      _current_interval=$MAX_INTERVAL
    elif [ "$_consecutive_fails" -ge 2 ]; then
      _current_interval=30
    fi
    log "[RESTART] next check in ${_current_interval}s"
  fi

  sleep "$_current_interval"
done
