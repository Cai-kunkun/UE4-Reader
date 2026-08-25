#!/bin/bash
# MCP 桥接自愈脚本: 确保 手机MCP服务 → adb forward → socat LAN桥 全链路可用
# 用法: mcp-bridge.sh [--wait]  (--wait 用于开机等待网络就绪)

LAN_IP="192.168.31.188"
BRIDGE_PORT=25500
FWD_PORT=25503
PKG="com.arrbrants.kernelhack"
SVC="$PKG/.McpService"

log() { echo "[mcp-bridge] $*"; }

# --wait: 等待网络与 adb 就绪(最多120s)
if [ "$1" = "--wait" ]; then
    for i in $(seq 1 24); do
        ip -4 addr show scope global 2>/dev/null | grep -q inet && break
        sleep 5
    done
    for i in $(seq 1 12); do
        adb start-server >/dev/null 2>&1 && adb devices | grep -q "localhost:5555" && break
        adb connect localhost:5555 >/dev/null 2>&1
        sleep 5
    done
fi

adb connect localhost:5555 >/dev/null 2>&1

# 0) 手机端 MCP 服务保活
ensure_service() {
    if ! adb -s localhost:5555 shell true >/dev/null 2>&1; then
        adb connect localhost:5555 >/dev/null 2>&1
    fi
    if adb -s localhost:5555 shell pidof "$PKG" >/dev/null 2>&1; then
        # 进程在,但 socket 可能没起 - 重启服务触发 onStartCommand
        adb -s localhost:5555 shell am start-foreground-service -n "$SVC" >/dev/null 2>&1
    else
        adb -s localhost:5555 shell am start-foreground-service -n "$SVC" >/dev/null 2>&1
    fi
    sleep 3
}

# 1) adb forward
ensure_fwd() {
    adb -s localhost:5555 forward --list 2>/dev/null | grep -q "tcp:$FWD_PORT" \
        || adb -s localhost:5555 forward tcp:$FWD_PORT tcp:25500 >/dev/null
}

# 2) socat LAN 桥
ensure_socat() {
    if ! pgrep -x socat | head -1 >/dev/null; then
        setsid socat TCP-LISTEN:$BRIDGE_PORT,fork,reuseaddr,bind=$LAN_IP TCP:127.0.0.1:$FWD_PORT </dev/null >>/tmp/socat-mcp.log 2>&1 &
        sleep 1
    fi
}

# 3) 端到端健康检查 (最多3轮修复)
for round in 1 2 3; do
    ensure_service; ensure_fwd; ensure_socat
    RESP=$(printf '%s' '{"jsonrpc":"2.0","id":9,"method":"ping"}' | curl --max-time 6 -sS -X POST "http://$LAN_IP:$BRIDGE_PORT/mcp" -H 'Content-Type: application/json' --data-binary @- 2>/dev/null)
    if echo "$RESP" | grep -q '"result"'; then
        log "OK: http://$LAN_IP:$BRIDGE_PORT/mcp healthy (round $round)"
        exit 0
    fi
    log "round $round failed, retrying..."
    # 强力恢复: 杀app重来
    adb -s localhost:5555 shell am force-stop "$PKG" >/dev/null 2>&1
    sleep 2
done

log "FAILED after 3 rounds"
exit 1
