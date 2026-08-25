# MCP Bridge

容器侧桥接脚本：让局域网设备通过 `http://192.168.31.188:25500/mcp` 访问手机上的 KernelHack MCP 服务。

链路: LAN客户端 → socat(0.0.0.0→192.168.31.188:25500) → adb forward(:25503) → 手机 127.0.0.1:25500 (MCP foreground service)

## 手动使用
```bash
mcp-bridge.sh        # 立即自愈一次
mcp-bridge.sh --wait # 等待网络/adb就绪后拉起（开机用）
```

## 开机自启（已在容器内配置）
- `mcp-bridge.service`: 开机等待网络后拉起全链路
- `mcp-bridge.timer`: 每60s健康检查,失败自动修复
