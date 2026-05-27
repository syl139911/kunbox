#!/bin/bash
# Patch 04a: Add Path field to HTTPOutboundOptions (option/simple.go)
#
# TPBox 配置格式：
#   { "type":"http", "path":"@dingtalk.com", ... }
#
# 需要让 sing-box 的 option 层能解析 "path" 字段

set -e

TARGET="$1/option/simple.go"

if [ ! -f "$TARGET" ]; then
    echo "ERROR: $TARGET not found"
    exit 1
fi

echo "=== Patch 04a: option/simple.go - add Path ==="

# 检查是否已经存在
if grep -q 'Path.*string.*json:"path' "$TARGET"; then
    echo "OK: Path field already exists, skipping"
    exit 0
fi

# 在 DelHost 后面加 Path（如果 DelHost 存在）
if grep -q 'DelHost' "$TARGET"; then
    sed -i '/DelHost.*bool.*json:"del_host/a\\tPath          string              `json:"path,omitempty"`' "$TARGET"
else
    # 如果没有 DelHost，在 Headers 后面加
    sed -i '/Headers.*HTTPHeader/a\\tPath          string              `json:"path,omitempty"`' "$TARGET"
fi

echo "=== After patch ==="
grep -n 'Path\|DelHost' "$TARGET"
echo "=== Patch 04a applied ==="
