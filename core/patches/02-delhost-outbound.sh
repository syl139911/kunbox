#!/bin/bash
# Patch 02: Pass DelHost option to HTTP client in protocol/http/outbound.go
#
# 在构建 HTTP 客户端 Options 时，将 DelHost 配置传递下去。
#
# 目标文件: protocol/http/outbound.go
# 上游结构: Headers: options.Headers.Build(),

set -e

TARGET="$1/protocol/http/outbound.go"

if [ ! -f "$TARGET" ]; then
    echo "ERROR: $TARGET not found"
    exit 1
fi

echo "=== Patch 02: protocol/http/outbound.go - pass DelHost to HTTP client ==="

# 在 Headers 赋值后插入 DelHost 赋值
sed -i '/Headers:.*options\.Headers\.Build(),/a\				DelHost:  options.DelHost,' "$TARGET"

grep -n 'DelHost' "$TARGET"
echo "=== Patch 02 applied ==="
