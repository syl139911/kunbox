#!/bin/bash
# Patch 13: protocol/http/outbound.go — Pass RemovePort + Host to client
#
# 目标文件: sing-box 的 protocol/http/outbound.go

set -e
TARGET="$1/protocol/http/outbound.go"
[ -f "$TARGET" ] || { echo "ERROR: $TARGET not found"; exit 1; }

echo "=== Patch 13: outbound.go - RemovePort + Host ==="

# 在 HttpsDel 传递之后添加 RemovePort + Host
if ! grep -q 'RemovePort' "$TARGET"; then
    sed -i '/HttpsDel.*options\.HttpsDel/a\\t\t\tRemovePort: options.RemovePort,\n\t\t\tHost:       options.Host,' "$TARGET"
    echo "  + RemovePort + Host passed to client"
else
    echo "  ~ RemovePort already passed, skip"
fi

grep -n 'RemovePort\|Host:.*options\.Host' "$TARGET"
echo "=== Patch 13 done ==="
