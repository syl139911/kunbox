#!/bin/bash
# Patch 06: protocol/http/outbound.go — Pass HttpFirst to HTTP client
#
# 目标文件: protocol/http/outbound.go
# 在 Path 传递后追加 HttpFirst

set -e
TARGET="$1/protocol/http/outbound.go"
[ -f "$TARGET" ] || { echo "ERROR: $TARGET not found"; exit 1; }

echo "=== Patch 06: outbound.go - HttpFirst ==="

if ! grep -q 'HttpFirst.*options.HttpFirst' "$TARGET"; then
    sed -i '/Path:.*options\.Path,/a\				HttpFirst:  options.HttpFirst,' "$TARGET"
    echo "  + HttpFirst passed"
fi

grep -n 'HttpFirst' "$TARGET"
echo "=== Patch 06 done ==="
