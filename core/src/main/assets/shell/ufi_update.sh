#!/system/bin/sh
# =============================================================================
# ufi_update.sh — UFI-AXIS Core 更新 watchdog（v6.3：仅 mode=2，纯 watchdog 不安装）
# 用法: ufi_update.sh <apk_path> <target_ver> <apk_sha256> [mode] [baseline]
#   <apk_path> APK 绝对路径；<target_ver> 目标版本（local=跳过比对）；
#   <apk_sha256> 期望 SHA-256；[mode] 恒为 2（老 pending 0/1 也强制按 2）；
#   [baseline] 安装前 versionName:versionCode（mode=2 必填）
# 安装分工: 唯一执行者 AppManager.installApk（UpdateManager 后台线程调用）；
#   本脚本只 watchdog：轮询结果文件/version/lastUpdateTime → 重启 → 写 RESULT
# 机器可读行: RESULT=OK:<ver> | INSTALL_FAILED:<code> | VERIFY_FAIL:<原因> | ENV_FAIL:<原因>
# 退出码: 0=成功 1=安装失败 2=校验失败 9x=环境/参数/锁失败
# 约束: POSIX sh（mksh/toybox），禁止 bash 特性
# =============================================================================
LOCK=/data/local/tmp/ufi_update.lock
CORE_PKG=com.ufi_axis_core
CORE_SVC=com.ufi_axis_core/.service.BackendService

# 日志分类目录：所有日志统一归到 Download/UFI-AXIS/log 下，按组件分子目录。
#   watchdog/ → 本脚本运行日志（原 /data/local/tmp/ufi_update.log 手机/ADB 取不到，已弃用）
LOG_DIR=/sdcard/Download/UFI-AXIS/log/watchdog
[ -d /storage/emulated/0/Download ] && LOG_DIR=/storage/emulated/0/Download/UFI-AXIS/log/watchdog
mkdir -p "$LOG_DIR" 2>/dev/null
LOG="$LOG_DIR/watchdog.log"

# 2026-08-21 启动哨兵：写到 world-writable 的 /data/local/tmp，避免 /sdcard 存储权限问题导致
# 脚本未启动却无任何痕迹。必须在任何 /sdcard 写操作之前写入，确保“脚本是否启动”可判定。
STARTED_SENTINEL=/data/local/tmp/.watchdog_started
{ echo "[STARTED] $(date '+%Y-%m-%d %H:%M:%S') pid=$$ args_count=$#"; echo "$0 $*"; } > "$STARTED_SENTINEL" 2>/dev/null

# 普通日志行：单写 LOG（已统一到 Download/UFI-AXIS/log/watchdog/watchdog.log）
log() { echo "$*" | tee -a "$LOG" 2>/dev/null; }
# 执行命令并把输出写入 LOG（保留命令真实退出码）
run() { "$@" > /data/local/tmp/ufi_run.out 2>&1; _rc=$?; cat /data/local/tmp/ufi_run.out | tee -a "$LOG" 2>/dev/null; rm -f /data/local/tmp/ufi_run.out; return $_rc; }
# 判断 Core 进程是否存活（兼容 pgrep / ps）
core_running() {
  if command -v pgrep >/dev/null 2>&1; then
    pgrep -f "$CORE_PKG" >/dev/null 2>&1
  else
    ps 2>/dev/null | grep -q "$CORE_PKG"
  fi
}
# 判断 BackendService 是否真的在跑。
# 两个坑：
# ① 只看 core_running 不够 —— 回退启动 MainActivity 一定能让【进程】活着，
#    但后端服务没起来前端照样连不上，脚本却已经写了 RESULT=OK；
# ② 只 grep 'BackendService' 也不够 —— `am force-stop` 之后 AMS 里还留着正在销毁的
#    ServiceRecord，紧接着 dumpsys 就会命中它，于是 0 秒判定"已启动"（实测过）。
#    所以必须落到该记录块内的 isForeground=true：只有真正跑起来的前台服务才有这一行。
core_service_running() {
  dumpsys activity services "$CORE_PKG" 2>/dev/null \
    | grep -A24 'ServiceRecord.*BackendService' \
    | grep -q 'isForeground=true'
}
# 拉起后端服务。两个必需条件（缺一个都起不来，实测于 F50/Android 14）：
# ① `-f 0x00000020` = FLAG_INCLUDE_STOPPED_PACKAGES。`am force-stop` 会把包置成 stopped
#    状态，此后不带该 flag 的 Intent 匹配不到包内组件，am 直接回
#    "Error: Not found; no service started."；
# ② BackendService 是 foregroundServiceType=dataSync 的前台服务，Android 8+ 下
#    `am startservice` 从后台拉前台服务会被 AMS 拒（"app is in background uid null"，
#    而退出码仍是 0 —— 所以必须看输出而不是 rc）。正确子命令是 start-foreground-service。
start_core_service() {
  _sout=$(am start-foreground-service --user 0 -f 0x00000020 -n "$CORE_SVC" 2>&1)
  log "[RESTART] start-foreground-service: ${_sout:-<empty>}"
  case "$_sout" in
    *Error*|*error*|*Exception*|*Unknown*)
      _sout=$(am startservice --user 0 -f 0x00000020 -n "$CORE_SVC" 2>&1)
      log "[RESTART] startservice fallback: ${_sout:-<empty>}"
      ;;
  esac
}

# 等 BackendService 进入前台：前 4s 每 0.25s 探一次（服务一起来就立刻放行），之后每 1s。
# 设备 toybox 的 sleep 支持小数（实测 `sleep 0.5` OK），所以细粒度轮询是安全的。
# $1 = 最长等待秒数；成功回 0、超时回 1；实际等待秒数写进 $_WAITED 供日志用。
wait_core_service() {
  _limit=$(( $1 * 4 ))
  _q=0
  while [ $_q -lt $_limit ]; do
    if core_service_running; then _WAITED=$((_q / 4)); return 0; fi
    if [ $_q -lt 16 ]; then sleep 0.25; _q=$((_q + 1)); else sleep 1; _q=$((_q + 4)); fi
  done
  _WAITED=$((_q / 4))
  return 1
}

# 清空旧日志（本次更新重建）
# 2026-08-21 修复：原 `: > "$LOG"` 在 /sdcard 上偶发不触发截断（多次会话串到同一文件），
# 改用 printf + truncate 双保险，确保截断后再写入本轮内容。
{ printf '' > "$LOG"; } >/dev/null 2>&1 || : > "$LOG" 2>/dev/null
log "=== UFI update start $(date) ==="
log "[ARGS] apk=$1 target=$2"

# ── ① 环境自检（跨 ROM toybox 兼容，缺命令明确报错退出）──
for C in pm dumpsys am grep cut sed cp rm mkdir id date head tail awk; do
  command -v "$C" >/dev/null 2>&1 || { log "RESULT=ENV_FAIL:missing_$C"; exit 90; }
done
# sha256sum 兼容：toybox 旧版叫 sha256
HASH_CMD=$(command -v sha256sum || command -v sha256 || echo "")
[ -n "$HASH_CMD" ] || { log "RESULT=ENV_FAIL:no_sha"; exit 90; }
log "[ENV] uid=$(id -u) hash=$HASH_CMD"

# ── 锁文件：防止脚本被重复执行（PID 文件 + stale 检测）──
LOCK_PID_FILE="$LOCK/pid"
if [ -d "$LOCK" ]; then
  # 锁目录已存在，检查是否为 stale lock（持有进程已退出）
  _stale=0
  if [ -f "$LOCK_PID_FILE" ]; then
    _old_pid=$(cat "$LOCK_PID_FILE" 2>/dev/null)
    if [ -n "$_old_pid" ] && [ -d "/proc/$_old_pid" ]; then
      # 进程仍在运行 → 真正的并发执行
      log "RESULT=ENV_FAIL:already_running (pid=$_old_pid)"
      exit 94
    fi
    _stale=1
  else
    # 无 PID 文件（旧版本脚本残留）→ 视为 stale
    _stale=1
  fi
  if [ $_stale -eq 1 ]; then
    log "[LOCK] removing stale lock (previous script exited abnormally)"
    rm -rf "$LOCK" 2>/dev/null
  fi
fi
mkdir -p "$LOCK" 2>/dev/null || { log "RESULT=ENV_FAIL:already_running"; exit 94; }
echo $$ > "$LOCK_PID_FILE" 2>/dev/null
trap 'rm -rf "$LOCK" 2>/dev/null' EXIT

# ── ② 参数合法性 ──
[ -n "$1" ] && [ -f "$1" ] || { log "RESULT=ENV_FAIL:bad_apk"; exit 91; }
[ -n "$2" ] || { log "RESULT=ENV_FAIL:no_target_ver"; exit 92; }
[ -n "$3" ] || { log "RESULT=ENV_FAIL:no_sha256"; exit 95; }

# ── ③ APK 完整性（SHA-256 与 Core 传入期望比对，防篡改/防损坏）──
APK_HASH=$($HASH_CMD "$1" | cut -d' ' -f1)
log "[CHECK] apk sha256=$APK_HASH"
[ "$APK_HASH" = "$3" ] || { log "RESULT=VERIFY_FAIL:apk_hash_mismatch"; exit 93; }

# ── mode=2 watchdog（v6.3 唯一职责）：等待外部安装完成 ──
# 安装统一由 UpdateManager 后台线程 → AppManager.installApk 执行（传 /sdcard 原路径）。
# 本脚本不再自行触发安装（mode=0/1 已删除，老 pending 第4行 0/1 也强制按 2 处理）。
#   信号0（优先）: InstallService 结果文件 /data/local/tmp/ufi_install_result.txt
#                  （SUCCESS* → 成功；FAILED* → 失败；进程被 commit 杀死时不写文件 → 走信号1/2）
#   信号1: versionName:versionCode 变化（版本升级）
#   信号2: lastUpdateTime 变化（同版本重装）
INSTALL_MODE="${4:-2}"
BASELINE_VER="${5:-}"
# 注意：保留完整时间戳（含时分秒），否则同日重装(lastUpdateTime 仅日期相同)会导致信号2失效。
_baseline_lut=$(dumpsys package "$CORE_PKG" 2>/dev/null | grep -m1 'lastUpdateTime' | sed 's/.*lastUpdateTime[=: ]*//')
log "[WATCHDOG] mode=$INSTALL_MODE baseline ver=$BASELINE_VER lut=${_baseline_lut:-unknown}"
if [ -z "$BASELINE_VER" ] || [ "$BASELINE_VER" = "0" ]; then
  log "[WATCHDOG] ERROR: baseline_version not provided"
  log "RESULT=ENV_FAIL:no_baseline"
  exit 95
fi

INSTALL_RESULT_FILE=/data/local/tmp/ufi_install_result.txt
log "[WATCHDOG] waiting for install (triggered by UpdateManager → AppManager.installApk)..."
INSTALL_EXIT=1
_i=0
_WATCHDOG_FAIL_REASON=""
_TRIGGERED=/data/local/tmp/ufi_install_triggered
_WATCHDOG_TIMEOUT=300
while [ $_i -lt $_WATCHDOG_TIMEOUT ]; do
  # 信号0（优先）: InstallService 结果文件（PI API 失败且进程存活时会写 FAILED）
  if [ -f "$INSTALL_RESULT_FILE" ] && [ -s "$INSTALL_RESULT_FILE" ]; then
    _content=$(cat "$INSTALL_RESULT_FILE" 2>/dev/null)
    if [ -n "$_content" ]; then
      log "[WATCHDOG] install result file found at ${_i}s: $_content"
      case "$_content" in
        SUCCESS*) INSTALL_EXIT=0; break;;
        *)        INSTALL_EXIT=1; _WATCHDOG_FAIL_REASON="$_content"; break;;
      esac
    fi
  fi
  # 信号1: 版本变化
  _cur_vn=$(dumpsys package "$CORE_PKG" 2>/dev/null | grep -m1 'versionName' | sed 's/.*versionName[=: ]*//' | sed 's/[", ].*//')
  _cur_vc=$(dumpsys package "$CORE_PKG" 2>/dev/null | grep -m1 'versionCode' | sed 's/.*versionCode[=: ]*//' | sed 's/[[:space:]].*//')
  _cur_ver="${_cur_vn:-unknown}:${_cur_vc:-0}"
  if [ "$_cur_ver" != "$BASELINE_VER" ]; then
    log "[WATCHDOG] version changed: $BASELINE_VER → $_cur_ver (${_i}s)"
    INSTALL_EXIT=0; break
  fi
  # 信号2: lastUpdateTime 变化（同版本重装）
  _cur_lut=$(dumpsys package "$CORE_PKG" 2>/dev/null | grep -m1 'lastUpdateTime' | sed 's/.*lastUpdateTime[=: ]*//')
  if [ -n "$_cur_lut" ] && [ -n "$_baseline_lut" ] && [ "$_cur_lut" != "$_baseline_lut" ]; then
    log "[WATCHDOG] lastUpdateTime changed: $_baseline_lut → $_cur_lut (${_i}s)"
    INSTALL_EXIT=0; break
  fi
  # 早退：等待超 150s 却无任何动静，且未见 UpdateManager 的安装触发标志、也无结果文件
  # → 安装根本没发起，直接早退（ENV_FAIL）而非傻等 300s。
  # 2026-08-22：60s → 150s —— 安装链路改为「PI 真实结果等待 25s + 失败落入 shell 安装」，
  # shell fallback 可能在 25~120s 间才产生版本/lastUpdateTime 变化，60s 早退会误杀。
  if [ $_i -ge 150 ] && [ ! -f "$_TRIGGERED" ] && [ ! -f "$INSTALL_RESULT_FILE" ]; then
    log "[WATCHDOG] early-exit at ${_i}s: install never triggered (no flag, no result)"
    log "RESULT=ENV_FAIL:install_not_triggered"
    exit 94
  fi
  # 心跳：每 ~15s 输出进度（2026-08-23 收紧：30s → 15s，提升可见性）
  if [ $((_i % 15)) -eq 0 ]; then
    _hb_vn=$(dumpsys package "$CORE_PKG" 2>/dev/null | grep -m1 'versionName' | sed 's/.*versionName[=: ]*//' | sed 's/[", ].*//')
    log "[WATCHDOG] heartbeat ${_i}s curver=${_hb_vn:-?} baseline=$BASELINE_VER lut=${_cur_lut:-?}"
  fi
  # 自适应轮询：前 30s 每 1s（快速响应安装完成），之后每 2s（2026-08-23 收紧：5s → 2s）
  if [ $_i -lt 30 ]; then _iv=1; else _iv=2; fi
  sleep $_iv; _i=$((_i + _iv))
done
rm -f "$INSTALL_RESULT_FILE" 2>/dev/null
if [ "$INSTALL_EXIT" -ne 0 ]; then
  if [ -n "$_WATCHDOG_FAIL_REASON" ]; then
    log "[WATCHDOG] install reported failure: $_WATCHDOG_FAIL_REASON"
    log "RESULT=INSTALL_FAILED:$_WATCHDOG_FAIL_REASON"
  else
    log "[WATCHDOG] timeout after ${_i}s — install did not complete"
    log "RESULT=INSTALL_FAILED:watchdog_timeout"
  fi
  # MED2：失败分支同样清理 pending 文件（防残留导致每次开机 BootReceiver 重复拉起 600s watchdog）
  rm -f /data/local/tmp/ufi_pending_install.txt 2>/dev/null
  exit 1
fi

# ── MED2：mode=2 安装成功确认后清理 pending 文件（防下次开机 BootReceiver 重复安装 core）──
rm -f /data/local/tmp/ufi_pending_install.txt 2>/dev/null
log "[PENDING] pending install file removed after successful mode=2 install"

# ── ⑤⑥ 装后校验 + 重启（单路径：新 APK 已装但旧 Core 仍在运行，先重启再校验）──
log "[RESTART] restarting Core before verification..."
# 清掉"用户主动停止"标记：升级本身就意味着要它跑起来。标记残留时 keepalive 会永久罢工
# （ufi_keepalive.sh 见到 STOP_FLAG 直接 skip），于是升级后再也没人拉 Core。
rm -f /data/local/tmp/ufi_core_stopped 2>/dev/null
# 这里**不能**用 am force-stop：它会把包置成 stopped 状态，之后所有不带
# FLAG_INCLUDE_STOPPED_PACKAGES 的 Intent 都匹配不到包内组件（实测 am 回
# "Error: Not found; no service started."）。覆盖安装本身已经杀掉旧进程，
# 清场只需 am kill 兜一下（不置 stopped 位，且只对后台进程生效）。
am kill "$CORE_PKG" 2>/dev/null
start_core_service
# 起服务只需给 AMS 极短的时间：原来是固定 sleep 2 + 每秒轮询，最快也要 2s 才认账，
# 实测服务本身 0.5~1s 就已经 isForeground=true —— 白等。现在 0.3s 起探、前 4s 每 0.25s 一次。
sleep 0.3
if ! wait_core_service 20; then
  # 兜底：AMS 拒了后台启动时，先把 LAUNCHER 主 Activity 拉到前台 —— 应用进入前台后
  # 启动前台服务不再受后台限制，所以拉起 Activity 后要再发一次 start-foreground-service
  # （不能只靠 MainActivity 自启：那条路径受 autoStartOnBoot 开关约束，关掉就不起服务）。
  log "[RESTART] 服务未起（等了 ${_WAITED}s），回退 am start 主 Activity 后重发 start-foreground-service"
  run am start --user 0 -f 0x00000020 -n com.ufi_axis_core/.MainActivity
  sleep 1.5
  start_core_service
  wait_core_service 20
fi
if ! core_service_running; then
  log "[RESTART] BackendService NOT running after ${_WAITED}s (process alive=$(core_running && echo Y || echo N))"
  log "RESULT=ENV_FAIL:core_not_restarted"
  exit 91
fi
log "[RESTART] BackendService running OK (took ${_WAITED}s)"
# 等 PackageManager 把新版本号刷出来（原来固定 2s，实测装完早就更新好了）
sleep 0.5
# ── 装后校验 ──
CUR=$(dumpsys package "$CORE_PKG" 2>/dev/null | grep -m1 'versionName' | sed 's/.*versionName[=: ]*//' | sed 's/[", ].*//')
log "[VERIFY] versionName='$CUR' target='$2'"
if [ -z "$CUR" ]; then
  log "[VERIFY] WARN: 版本解析失败，按成功放行"
  CUR="unknown"
elif [ "$2" != "local" ] && [ "$CUR" != "$2" ]; then
  log "RESULT=VERIFY_FAIL:version_${CUR}"
  exit 2
fi
log "RESULT=OK:$CUR"
exit 0
