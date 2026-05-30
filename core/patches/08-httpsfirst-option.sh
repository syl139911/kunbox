#!/bin/bash
# Patch 08: option/simple.go — Add HttpsFirst + HttpDel + HttpsDel to HTTPOutboundOptions
#
# 目标文件: option/simple.go
# 新增字段: HttpsFirst — HTTPS CONNECT 独立 preface
#           HttpDel    — HTTP 删除指定 header 列表
#           HttpsDel   — HTTPS 删除指定 header 列表

set -e
TARGET="$1/option/simple.go"
[ -f "$TARGET" ] || { echo "ERROR: $TARGET not found"; exit 1; }

echo "=== Patch 08: option/simple.go - HttpsFirst + HttpDel + HttpsDel ==="

# HttpsFirst (在 HttpFirst 后)
if ! grep -q 'HttpsFirst.*string.*json:"https_first' "$TARGET"; then
    sed -i '/HttpFirst.*string.*json:"http_first/a\	HttpsFirst    string              `json:"https_first,omitempty"`' "$TARGET"
    echo "  + HttpsFirst added"
fi

# HttpDel (在 HttpsFirst 后)
if ! grep -q 'HttpDel.*json:"http_del' "$TARGET"; then
    sed -i '/HttpsFirst.*string.*json:"https_first/a\	HttpDel       []string            `json:"http_del,omitempty"`' "$TARGET"
    echo "  + HttpDel added"
fi

# HttpsDel (在 HttpDel 后)
if ! grep -q 'HttpsDel.*json:"https_del' "$TARGET"; then
    sed -i '/HttpDel.*json:"http_del/a\	HttpsDel      []string            `json:"https_del,omitempty"`' "$TARGET"
    echo "  + HttpsDel added"
fi

grep -n 'HttpsFirst\|HttpDel\|HttpsDel' "$TARGET"
echo "=== Patch 08 done ==="
