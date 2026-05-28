#!/bin/bash
# Patch 09: protocol/http/outbound.go — Pass HttpsFirst + HttpDel + HttpsDel to HTTP client
#
# 目标文件: protocol/http/outbound.go
# 在 HttpFirst 传递后追加 HttpsFirst, HttpDel, HttpsDel

set -e
TARGET="$1/protocol/http/outbound.go"
[ -f "$TARGET" ] || { echo "ERROR: $TARGET not found"; exit 1; }

echo "=== Patch 09: outbound.go - HttpsFirst + HttpDel + HttpsDel ==="

if ! grep -q 'HttpsFirst.*options.HttpsFirst' "$TARGET"; then
    sed -i '/HttpFirst:.*options\.HttpFirst,/a\				HttpsFirst: options.HttpsFirst,\n				HttpDel:    options.HttpDel,\n				HttpsDel:   options.HttpsDel,' "$TARGET"
    echo "  + HttpsFirst + HttpDel + HttpsDel passed"
fi

grep -n 'HttpsFirst\|HttpDel\|HttpsDel' "$TARGET"
echo "=== Patch 09 done ==="
