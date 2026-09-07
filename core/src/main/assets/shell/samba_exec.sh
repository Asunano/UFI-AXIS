#!/system/bin/sh
# UFI-AXIS Samba root preexec script
# Runs as root via ZTE F50 Samba root preexec mechanism.
# Primary purpose: start socat Unix socket listener → root shell for pm install.
#
# Deployed by BackendService to /data/local/tmp/ufi_axis/samba_exec.sh
# Referenced by /data/samba/etc/smb.conf: root preexec = /system/bin/sh <this>

LOG_FILE="/data/local/tmp/ufi_axis/samba.log"
SOCKET_FILE="/data/local/tmp/ufi_axis/root.sock"
SOCAT_PATH="/data/local/tmp/ufi_axis/socat"

log() {
    echo "[$(date '+%Y-%m-%d %H:%M:%S')] $1" >> "$LOG_FILE" 2>/dev/null
}

# Keep log under 1MB
if [ -f "$LOG_FILE" ]; then
    _sz=$(wc -c < "$LOG_FILE" 2>/dev/null)
    if [ "${_sz:-0}" -gt 1048576 ]; then
        echo "[$(date)] log rotated" > "$LOG_FILE"
    fi
fi

log "samba_exec.sh start (uid=$(id -u))"

# ── Start socat root shell (core function) ──
start_socat() {
    if [ ! -x "$SOCAT_PATH" ]; then
        log "socat binary not found at $SOCAT_PATH"
        return 1
    fi
    if pgrep -f "$SOCKET_FILE" >/dev/null 2>&1; then
        log "socat already running"
        return 0
    fi
    mkdir -p "$(dirname "$SOCKET_FILE")"
    log "starting socat on $SOCKET_FILE"
    "$SOCAT_PATH" -d -d UNIX-LISTEN:"$SOCKET_FILE",fork,reuseaddr,unlink-early EXEC:/system/bin/sh &
    sleep 1
    if pgrep -f "$SOCKET_FILE" >/dev/null 2>&1; then
        log "socat started OK"
    else
        log "socat start FAILED"
    fi
}

# ── Drop IPv6 rules (prevent IPv6 port access for security) ──
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

# ── Disable phantom process killer ──
close_thread_killer() {
    settings put global settings_enable_monitor_phantom_procs false 2>/dev/null || true
    settings put global max_phantom_processes 2147483647 2>/dev/null || true
}

# ── Main ──
start_socat
drop_ipv6
close_thread_killer

log "samba_exec.sh done"
