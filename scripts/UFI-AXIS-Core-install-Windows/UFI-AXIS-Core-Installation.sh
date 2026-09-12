#!/usr/bin/env bash
# =============================================================================
# UFI-AXIS-Core 一键安装工具（Linux / macOS）
#
# 与同目录 UFI-AXIS-Core-Installation.bat 功能对等：交互式输入设备远程 ADB 地址 →
# 连接 → 安装 core/ 下的 APK → 识别包名 → 授权 → 启动 → 轮询 /health 确认服务就绪。
# 提示文案、步骤编号、重试次数、超时时间均与 bat 保持一致。
#
# 与 Windows 版唯一的实质差异：**adb 来源**。
# 目录里随包分发的是 adb.exe（仅 Windows 可用），所以这里优先用脚本同目录的 `adb`
# （若你自己放了一个 Linux/macOS 版），其次回落 PATH；都没有时按当前系统给出对应的
# 安装命令并询问是否代为安装。安装失败（无网络 / 无 sudo / 包名不存在等）一律走
# 失败退出，绝不假装成功继续往下跑。
# =============================================================================

set -u

BASE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CORE="$BASE/core"
LOGDIR="$BASE/log"
DEF_IP="192.168.0.1"
DEF_PORT="5555"
HEALTH_PORT="8088"
KNOWN_PKG="com.ufi_axis_core"
TMPBASE="${TMPDIR:-/tmp}"
TMPOUT="$TMPBASE/ufiaxis_out.txt"
TMPBEFORE="$TMPBASE/ufiaxis_pkgs_before.txt"
TMPAFTER="$TMPBASE/ufiaxis_pkgs_after.txt"
ADB=""

# adb 解析：脚本同目录优先，其次 PATH。找到返回 0，没找到返回 1。
resolve_adb() {
    if [ -x "$BASE/adb" ]; then
        ADB="$BASE/adb"
    elif command -v adb >/dev/null 2>&1; then
        ADB="$(command -v adb)"
    else
        ADB=""
    fi
    [ -n "$ADB" ]
}
resolve_adb || true

mkdir -p "$LOGDIR" 2>/dev/null || true
TS="$(date +%Y%m%d_%H%M%S 2>/dev/null || echo "$$")"
LOGFILE="$LOGDIR/install_$TS.log"
echo "==== UFI-AXIS-Core 安装日志 $TS ====" >"$LOGFILE"

# ======== 子过程 ========
# 回显 + 落日志（对应 bat 的 :say）
say() { printf '%s\n' "$1"; printf '%s\n' "$1" >>"$LOGFILE"; }

# 回显 + 落日志（对应 bat 的 :dump）
dump() { [ -f "$1" ] || return 0; cat "$1"; cat "$1" >>"$LOGFILE"; }

# 只落日志、不回显（用于健康检查响应体，对应 bat 的 :logonly）
logonly() { [ -f "$1" ] || return 0; cat "$1" >>"$LOGFILE"; }

cleanup() { rm -f "$TMPOUT" "$TMPBEFORE" "$TMPAFTER" >/dev/null 2>&1 || true; }

# 对应 bat 的 pause
pause() { printf '按回车键继续...'; read -r _ || true; }

# 按当前系统回显 adb 的安装命令；识别不出包管理器则回显空串。
# Linux 上非 root 且有 sudo 时自动加 sudo 前缀（Linux 装 adb 通常一条命令就够）。
adb_install_cmd() {
    local sudo_prefix=""
    if [ "$(id -u 2>/dev/null || echo 1000)" != "0" ] && command -v sudo >/dev/null 2>&1; then
        sudo_prefix="sudo "
    fi
    case "$(uname -s 2>/dev/null || echo unknown)" in
        Darwin)
            # macOS 没有系统包管理器，只有装了 Homebrew 才给命令
            command -v brew >/dev/null 2>&1 && printf 'brew install --cask android-platform-tools'
            ;;
        Linux)
            if command -v apt-get >/dev/null 2>&1; then printf '%sapt-get install -y adb' "$sudo_prefix"
            elif command -v dnf     >/dev/null 2>&1; then printf '%sdnf install -y android-tools' "$sudo_prefix"
            elif command -v yum     >/dev/null 2>&1; then printf '%syum install -y android-tools' "$sudo_prefix"
            elif command -v pacman  >/dev/null 2>&1; then printf '%spacman -S --noconfirm android-tools' "$sudo_prefix"
            elif command -v zypper  >/dev/null 2>&1; then printf '%szypper install -y android-tools' "$sudo_prefix"
            elif command -v apk     >/dev/null 2>&1; then printf '%sapk add android-tools' "$sudo_prefix"
            fi
            ;;
    esac
}

# 确保 adb 可用：没有就按系统给命令并询问是否代装。
# 返回 0 = adb 已就绪；返回 1 = 不可用（调用方必须终止，**不允许**当成功继续）。
ensure_adb() {
    resolve_adb && return 0
    local os cmd ask rc
    os="$(uname -s 2>/dev/null || echo unknown)"
    say "[错误] 未找到可用的 adb（当前系统: $os）"
    cmd="$(adb_install_cmd)"
    if [ -z "$cmd" ]; then
        say "       未识别到可用的包管理器，无法给出安装命令"
        if [ "$os" = "Darwin" ]; then
            say "       请先装 Homebrew（https://brew.sh）再执行: brew install --cask android-platform-tools"
        else
            say "       请手动安装 Android platform-tools，或把 adb 放到脚本同目录后重跑"
        fi
        return 1
    fi
    say "       可用安装命令: $cmd"
    ask=0
    while true; do
        ask=$((ask + 1))
        [ "$ask" -gt 5 ] && return 1
        printf '输入 Y 现在安装 adb，输入 N 退出，然后按回车: '
        read -r YN || YN=""
        YN="${YN// /}"
        case "$YN" in
            Y|y) break ;;
            N|n) say "已选择不安装 —— 装好 adb 后重跑本脚本即可。"; return 1 ;;
            *) say "[提示] 只能输入 Y 或 N" ;;
        esac
    done
    say "正在执行: $cmd"
    # pipefail 必开：否则管道退出码取的是 tee 的，安装失败会被当成成功
    set -o pipefail
    sh -c "$cmd" 2>&1 | tee -a "$LOGFILE"
    rc=$?
    set +o pipefail
    if [ "$rc" -ne 0 ]; then
        say "[失败] 安装命令返回 $rc —— 常见原因：无网络、无 sudo 权限、软件源里没有该包"
        say "       请手动安装后重跑，或把 adb 放到脚本同目录"
        return 1
    fi
    hash -r 2>/dev/null || true
    if resolve_adb; then
        say "[成功] adb 已就绪: $ADB"
        return 0
    fi
    say "[失败] 安装命令执行完毕，但仍然找不到 adb —— 请手动确认安装结果"
    return 1
}
# 对应 bat 的 :fail
do_fail() {
    cleanup
    say ""
    say "============================================"
    say "安装未完成，详细日志: $LOGFILE"
    say "============================================"
    pause
    exit 1
}

# 对应 bat 的 :done_cancel
do_cancel() {
    cleanup
    say "已取消安装。"
    say "本次日志: $LOGFILE"
    pause
    exit 0
}

# ======== 主流程 ========
# 外层循环对应 bat 的 :start —— 连接失败选 N 时回到这里重填地址
while true; do
clear 2>/dev/null || true
say "============================================"
say "        UFI-AXIS-Core 一键安装工具"
say "============================================"
say "日志文件: $LOGFILE"
say ""

if ! ensure_adb; then
    do_fail
fi
if [ ! -d "$CORE" ]; then
    say "[错误] 未找到 core 目录: $CORE"
    say "       请在脚本同目录下放置 core 文件夹"
    do_fail
fi
# 用数组收集 APK：文件名可能含空格，逐个引用才安全
APKS=()
for f in "$CORE"/*.apk; do
    [ -f "$f" ] && APKS+=("$f")
done
if [ "${#APKS[@]}" -eq 0 ]; then
    say "[错误] core 目录中未找到 APK 文件"
    do_fail
fi
say "[1/6] 请输入设备远程 ADB 地址"
printf '> 地址 [回车使用默认 %s]: ' "$DEF_IP"
read -r ADDR || ADDR=""
ADDR="${ADDR//\"/}"
[ -n "$ADDR" ] || ADDR="$DEF_IP"

# 没带端口就补默认端口；带了就取冒号前作为纯 IP（健康检查用）
IPONLY="$ADDR"
case "$ADDR" in
    *:*) IPONLY="${ADDR%%:*}" ;;
    *)   ADDR="$ADDR:$DEF_PORT" ;;
esac
say "使用地址: $ADDR"
say ""

# 连接循环对应 bat 的 :connect / :ask_retry
RETRYASK=0
BACK_TO_START=0
while true; do
    say "[2/6] 正在连接 $ADDR ..."
    "$ADB" connect "$ADDR" >"$TMPOUT" 2>&1
    dump "$TMPOUT"
    "$ADB" -s "$ADDR" get-state >"$TMPOUT" 2>&1
    STATE="$(head -n 1 "$TMPOUT" 2>/dev/null | tr -d '\r')"
    [ "$STATE" = "device" ] && break

    say "       当前状态: $STATE"
    say "[失败] 无法连接 $ADDR，请检查："
    say "       - 设备已开启无线调试"
    say "       - 电脑与设备在同一网络"
    say "       - 设备上如有授权弹窗请先点允许"
    say ""
    # 内层循环只负责收 Y/N；Y 重连、N 回到填地址
    while true; do
        RETRYASK=$((RETRYASK + 1))
        [ "$RETRYASK" -gt 5 ] && do_fail
        printf '输入 Y 重新连接，输入 N 返回修改地址，然后按回车: '
        read -r YN || YN=""
        YN="${YN// /}"
        case "$YN" in
            Y|y) break ;;
            N|n) BACK_TO_START=1; break ;;
            *) say "[提示] 只能输入 Y 或 N" ;;
        esac
    done
    [ "$BACK_TO_START" = "1" ] && break
done
[ "$BACK_TO_START" = "1" ] && continue
say "[成功] 设备已连接: $ADDR"
say ""

say "[3/6] 即将安装 core 目录中的 APK:"
for f in "${APKS[@]}"; do say "       - $(basename "$f")"; done
say ""
CONFIRMASK=0
while true; do
    CONFIRMASK=$((CONFIRMASK + 1))
    [ "$CONFIRMASK" -gt 5 ] && do_fail
    printf '输入 Y 确认安装，输入 N 取消，然后按回车: '
    read -r YN || YN=""
    YN="${YN// /}"
    case "$YN" in
        Y|y) break ;;
        N|n) do_cancel ;;
        *) say "[提示] 只能输入 Y 或 N" ;;
    esac
done

say ""
say "[4/6] 正在安装 APK ..."
"$ADB" -s "$ADDR" shell pm list packages -3 >"$TMPBEFORE" 2>/dev/null || true
[ -f "$TMPBEFORE" ] || : >"$TMPBEFORE"
for f in "${APKS[@]}"; do
    say "   安装 $(basename "$f") ..."
    "$ADB" -s "$ADDR" install -r -d "$f" >"$TMPOUT" 2>&1
    dump "$TMPOUT"
    # 与 bat 一致：以输出里是否含 Success 判定，而非退出码（adb install 退出码不可靠）
    if ! grep -qi "Success" "$TMPOUT" 2>/dev/null; then
        say "[失败] $(basename "$f") 安装失败，请检查 APK 文件与设备兼容性。"
        do_fail
    fi
done
say "[成功] APK 安装完成。"

"$ADB" -s "$ADDR" shell pm list packages -3 >"$TMPAFTER" 2>/dev/null || true
# 包名识别：① 先试已知包名；② 再用装前/装后三方包列表求差集取第一个；③ 都不行让用户手输
NEWPKG=""
for c in $KNOWN_PKG; do
    [ -n "$NEWPKG" ] && break
    "$ADB" -s "$ADDR" shell pm path "$c" >"$TMPOUT" 2>&1
    grep -qi "package:" "$TMPOUT" 2>/dev/null && NEWPKG="$c"
done
if [ -z "$NEWPKG" ] && [ -s "$TMPBEFORE" ] && [ -f "$TMPAFTER" ]; then
    NEWPKG="$(grep -F 'package:' "$TMPAFTER" 2>/dev/null | tr -d '\r' \
        | grep -vxF -f <(grep -F 'package:' "$TMPBEFORE" 2>/dev/null | tr -d '\r') 2>/dev/null \
        | head -n 1 | sed 's/^package://')"
fi
if [ -z "$NEWPKG" ]; then
    printf '未能自动识别包名，请手动输入（直接回车跳过）: '
    read -r NEWPKG || NEWPKG=""
    NEWPKG="${NEWPKG//\"/}"
fi
[ -n "$NEWPKG" ] && say "识别到包名: $NEWPKG"
say ""

if [ -n "$NEWPKG" ]; then
    # 先授权再启动：首启缺权限时应用可能弹系统授权框，导致启动流程卡住
    say "[5/6] 正在授予权限 ..."
    for p in \
        READ_EXTERNAL_STORAGE \
        WRITE_EXTERNAL_STORAGE \
        MANAGE_EXTERNAL_STORAGE \
        ACCESS_FINE_LOCATION \
        ACCESS_COARSE_LOCATION \
        READ_PHONE_STATE \
        READ_SMS \
        RECEIVE_SMS \
        RECEIVE_MMS \
        READ_CELL_BROADCASTS \
        POST_NOTIFICATIONS \
        REQUEST_INSTALL_PACKAGES
    do
        ST="FAIL"
        if "$ADB" -s "$ADDR" shell pm grant "$NEWPKG" "android.permission.$p" >/dev/null 2>&1; then
            ST="OK"
        elif "$ADB" -s "$ADDR" shell appops set "$NEWPKG" "$p" allow >/dev/null 2>&1; then
            ST="OK"
        fi
        if [ "$ST" = "OK" ]; then say "    [OK] $p"; else say "    [--] $p 未自动授权"; fi
    done
    say "[完成] 权限授予完成。"
    say "提示: 如系统设置中仍有权限显示未开启，可手动允许。"
    say ""

    say "正在启动应用 $NEWPKG ..."
    "$ADB" -s "$ADDR" shell cmd package resolve-activity --brief "$NEWPKG" >"$TMPOUT" 2>&1
    LAUNCH_COMP="$(tr -d '\r' <"$TMPOUT" 2>/dev/null | grep -E '^[A-Za-z0-9_.]+/' | head -n 1)"
    if [ -n "$LAUNCH_COMP" ]; then
        "$ADB" -s "$ADDR" shell am start -n "$LAUNCH_COMP" >"$TMPOUT" 2>&1
        dump "$TMPOUT"
    else
        say "[提示] 未解析到启动入口，改用 monkey 启动"
        "$ADB" -s "$ADDR" shell monkey -p "$NEWPKG" -c android.intent.category.LAUNCHER 1 >"$TMPOUT" 2>&1
        dump "$TMPOUT"
    fi
    say "[完成] 启动指令已发送。"
    say ""
else
    say "[跳过] 未提供包名，跳过启动与授权。"
fi
say "[6/6] 等待 5 秒让应用服务启动..."
sleep 5
if ! command -v curl >/dev/null 2>&1; then
    say "[错误] 系统未找到 curl，无法检查服务状态。"
    do_fail
fi
HEALTH_URL="http://$IPONLY:$HEALTH_PORT/health"

# 健康检查：最多自动重试 6 次（每次间隔 5s）；仍失败则问用户是否重来
HEALTHASK=0
while true; do
    TRY=0
    HEALTH_OK=0
    while [ "$TRY" -lt 6 ]; do
        TRY=$((TRY + 1))
        say "正在请求 $HEALTH_URL ..."
        rm -f "$TMPOUT" >/dev/null 2>&1 || true
        curl -s --max-time 8 "$HEALTH_URL" >"$TMPOUT" 2>/dev/null || true
        # 与 bat 一致：响应体里同时含 status 与 ok 才算就绪
        if grep -qi "status" "$TMPOUT" 2>/dev/null && grep -qi "ok" "$TMPOUT" 2>/dev/null; then
            HEALTH_OK=1
            break
        fi
        [ "$TRY" -lt 6 ] || break
        say "服务尚未就绪，5 秒后自动重试 第 $TRY 次 ..."
        sleep 5
    done
    [ "$HEALTH_OK" = "1" ] && break

    say "[失败] 服务状态异常或无法访问，响应内容:"
    dump "$TMPOUT"
    say ""
    RECHECK=0
    while true; do
        HEALTHASK=$((HEALTHASK + 1))
        [ "$HEALTHASK" -gt 5 ] && do_fail
        printf '输入 Y 重新检查，输入 N 退出，然后按回车: '
        read -r YN || YN=""
        YN="${YN// /}"
        case "$YN" in
            Y|y) RECHECK=1; break ;;
            N|n) do_fail ;;
            *) say "[提示] 只能输入 Y 或 N" ;;
        esac
    done
    [ "$RECHECK" = "1" ] && continue
done
say "[成功] 服务状态正常。"
logonly "$TMPOUT"
say ""
say "============================================"
say "     安装成功！UFI-AXIS-Core 已就绪"
say "     服务地址: $HEALTH_URL"
say "     本次日志: $LOGFILE"
say "============================================"
cleanup
echo "窗口 5 秒后自动关闭..."
sleep 5
exit 0

done  # while true（:start 外层循环）—— 正常路径不会走到这里，所有出口都显式 exit
