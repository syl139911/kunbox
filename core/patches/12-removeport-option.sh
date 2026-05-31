#!/bin/bash
# Patch 12: option/simple.go — RemovePort + Host fields
#
# 新增:
#   - RemovePort bool: CONNECT 行不带端口
#   - Host string: 强制替换 Host header
#
# 目标文件: sing-box 的 option/simple.go

set -e
TARGET="$1/option/simple.go"
[ -f "$TARGET" ] || { echo "ERROR: $TARGET not found"; exit 1; }

echo "=== Patch 12: option/simple.go - RemovePort + Host ==="

# 在 HttpsDel 字段之后添加 RemovePort + Host
if ! grep -q 'RemovePort' "$TARGET"; then
    sed -i '/HttpsDel.*\[\]string/a\\tRemovePort bool                `json:"remove_port,omitempty"` // TPBox: CONNECT 行不带端口\n\tHost       string              `json:"host,omitempty"`        // TPBox: 强制替换 Host header' "$TARGET"
    echo "  + RemovePort + Host fields added"
else
    echo "  ~ RemovePort already exists, skip"
fi

grep -n 'RemovePort\|Host.*string.*json' "$TARGET"
echo "=== Patch 12 done ==="
