#!/bin/bash
# Patch 01: Add DelHost field to HTTPOutboundOptions in option/simple.go
#
# 在 HTTP 出站配置的 Headers 字段后添加 DelHost 布尔字段，
# 用于控制是否删除请求中的 Host 头（解决某些代理场景下的 Host 冲突）。
#
# 目标文件: option/simple.go
# 上游结构: type HTTPOutboundOptions struct { ... Headers ... }

set -e

TARGET="$1/option/simple.go"

if [ ! -f "$TARGET" ]; then
    echo "ERROR: $TARGET not found"
    exit 1
fi

echo "=== Patch 01: option/simple.go - add DelHost to HTTPOutboundOptions ==="

# 在 Headers 字段后插入 DelHost 字段
sed -i '/Headers badoption.HTTPHeader/a\	DelHost bool                 `json:"del_host,omitempty"`' "$TARGET"

grep -n 'DelHost' "$TARGET"
echo "=== Patch 01 applied ==="
