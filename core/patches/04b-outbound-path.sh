#!/bin/bash
# Patch 04b: Pass Path from options to client (protocol/http/outbound.go)
#
# 在 NewClient 调用处增加 Path: options.Path

set -e

TARGET="$1/protocol/http/outbound.go"

if [ ! -f "$TARGET" ]; then
    echo "ERROR: $TARGET not found"
    exit 1
fi

echo "=== Patch 04b: outbound.go - pass Path ==="

# 检查是否已经存在
if grep -q 'Path:.*options.Path' "$TARGET"; then
    echo "OK: Path already passed, skipping"
    exit 0
fi

# 在 DelHost 传递后加 Path（如果 DelHost 存在）
if grep -q 'DelHost:.*options.DelHost' "$TARGET"; then
    sed -i '/DelHost:.*options.DelHost/a\\t\t\t\tPath:          options.Path,' "$TARGET"
else
    # 在 Headers 传递后加
    sed -i '/Headers:.*options\.Headers\.Build()/a\\t\t\t\tPath:          options.Path,' "$TARGET"
fi

echo "=== After patch ==="
grep -n 'Path\|DelHost' "$TARGET"
echo "=== Patch 04b applied ==="
