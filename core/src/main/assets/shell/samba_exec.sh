#!/system/bin/sh
# UFI-AXIS Samba root preexec 脚本
#
# 由 smbd 的 `root preexec` 以 **root** 身份执行（2026-11-05 实测：uid=0，SELinux u:r:su:s0）。
# 路径无关：所有东西都相对本脚本所在目录，core 换部署位置不需要改这里。
#
# ★ 铁律：**必须 exit 0**。Samba 对 root preexec 非 0 退出的处理是直接判定本次连接失败
#   （源码里的 "root preexec gave %d - failing connection"），脚本报错会顺带把用户的
#   SMB 共享弄挂。所以下面每条失败路径都只能记日志，不能 return 非 0。
#
# 部署位置：<app filesDir>/root_shell/samba_exec.sh（见 SambaRootShell.kt）

D=$(dirname "$0")
SOCKET_FILE="$D/root.sock"
SOCAT_PATH="$D/socat"
LOG_FILE="$D/samba.log"

log() {
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] $1" >> "$LOG_FILE" 2>/dev/null
}

# 日志超过 1MB 就轮转，避免长年累月把 /data 撑满
if [ -f "$LOG_FILE" ]; then
    _sz=$(wc -c < "$LOG_FILE" 2>/dev/null)
    if [ "${_sz:-0}" -gt 1048576 ]; then
        echo "[$(date)] log rotated" > "$LOG_FILE"
    fi
fi

log "start uid=$(id -u)"

# ── 起 root shell socket ──
start_socat() {
    if [ ! -x "$SOCAT_PATH" ]; then
        log "socat 不存在或不可执行：$SOCAT_PATH"
        return 0
    fi
    if pgrep -f "UNIX-LISTEN:$SOCKET_FILE" >/dev/null 2>&1; then
        log "socat 已在运行"
        return 0
    fi

    # umask 000 + chmod：socket 由 root 创建、连接方是 app uid。
    # 默认 755 时 LocalSocket.connect() 会 EACCES（实测踩过）。
    umask 000
    "$SOCAT_PATH" UNIX-LISTEN:"$SOCKET_FILE",fork,reuseaddr,unlink-early \
        EXEC:/system/bin/sh >>"$LOG_FILE" 2>&1 &
    sleep 1
    chmod 777 "$SOCKET_FILE" 2>/dev/null

    if pgrep -f "UNIX-LISTEN:$SOCKET_FILE" >/dev/null 2>&1; then
        log "socat 已启动：$(ls -l "$SOCKET_FILE" 2>&1)"
    else
        log "socat 启动失败"
    fi
    return 0
}

# ── 以下两项是**既有行为**，会改系统状态，不是本脚本的主职责 ──
# 1) 封掉 IPv6 上的服务端口，避免 IPv6 绕过 IPv4 的访问控制
drop_ipv6() {
    for port in 8080 1146 139 445 5555; do
        ip6tables -C INPUT -p tcp --dport $port -j DROP 2>/dev/null || \
            ip6tables -A INPUT -p tcp --dport $port -j DROP
        ip6tables -C INPUT -p udp --dport $port -j DROP 2>/dev/null || \
            ip6tables -A INPUT -p udp --dport $port -j DROP
    done
    iptables -C INPUT 1 -i lo -j ACCEPT 2>/dev/null || \
        iptables -I INPUT 1 -i lo -j ACCEPT
}

# 2) 关掉 phantom process killer，否则 core 拉起的 aria2/ttyd 会被系统回收
close_thread_killer() {
    settings put global settings_enable_monitor_phantom_procs false 2>/dev/null
    settings put global max_phantom_processes 2147483647 2>/dev/null
}

start_socat
drop_ipv6
close_thread_killer

log "done"
exit 0
