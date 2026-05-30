#!/bin/bash
# Patch 02: protocol/http/outbound.go — Pass DelHost + Path to client
#
# 合并自: 02-delhost-outbound.sh + 04b-outbound-path.sh
# 目标文件: protocol/http/outbound.go

set -e
TARGET="$1/protocol/http/outbound.go"
[ -f "$TARGET" ] || { echo "ERROR: $TARGET not found"; exit 1; }

echo "=== Patch 02: outbound.go - DelHost + Path ==="

# 2a: DelHost (在 Headers 赋值后)
if ! grep -q 'DelHost:.*options.DelHost' "$TARGET"; then
    sed -i '/Headers:.*options\.Headers\.Build(),/a\				DelHost:  options.DelHost,' "$TARGET"
    echo "  + DelHost passed"
fi

# 2b: Path (在 DelHost 后)
if ! grep -q 'Path:.*options.Path' "$TARGET"; then
    if grep -q 'DelHost:.*options.DelHost' "$TARGET"; then
        sed -i '/DelHost:.*options.DelHost/a\				Path:          options.Path,' "$TARGET"
    else
        sed -i '/Headers:.*options\.Headers\.Build()/a\				Path:          options.Path,' "$TARGET"
    fi
    echo "  + Path passed"
fi

grep -n 'DelHost\|Path' "$TARGET"
echo "=== Patch 02 done ==="
