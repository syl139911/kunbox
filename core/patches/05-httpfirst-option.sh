#!/bin/bash
# Patch 05: option/simple.go — Add HttpFirst to HTTPOutboundOptions
#
# 目标文件: option/simple.go
# 新增字段: HttpFirst — HTTP preface 内容，写在 CONNECT 之前

set -e
TARGET="$1/option/simple.go"
[ -f "$TARGET" ] || { echo "ERROR: $TARGET not found"; exit 1; }

echo "=== Patch 05: option/simple.go - HttpFirst ==="

if ! grep -q 'HttpFirst.*string.*json:"http_first' "$TARGET"; then
    sed -i '/Path.*string.*json:"path/a\	HttpFirst     string              `json:"http_first,omitempty"`' "$TARGET"
    echo "  + HttpFirst added"
fi

grep -n 'HttpFirst' "$TARGET"
echo "=== Patch 05 done ==="
