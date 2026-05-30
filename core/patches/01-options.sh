#!/bin/bash
# Patch 01: option/simple.go — Add DelHost + Path to HTTPOutboundOptions
#
# 合并自: 01-delhost-option.sh + 04a-option-path.sh
# 目标文件: option/simple.go

set -e
TARGET="$1/option/simple.go"
[ -f "$TARGET" ] || { echo "ERROR: $TARGET not found"; exit 1; }

echo "=== Patch 01: option/simple.go - DelHost + Path ==="

# 1a: DelHost (在 Headers 后)
if ! grep -q 'DelHost.*bool.*json:"del_host' "$TARGET"; then
    sed -i '/Headers.*HTTPHeader/a\	DelHost bool                 `json:"del_host,omitempty"`' "$TARGET"
    echo "  + DelHost added"
fi

# 1b: Path (在 DelHost 后，或 Headers 后)
if ! grep -q 'Path.*string.*json:"path' "$TARGET"; then
    if grep -q 'DelHost' "$TARGET"; then
        sed -i '/DelHost.*bool.*json:"del_host/a\	Path          string              `json:"path,omitempty"`' "$TARGET"
    else
        sed -i '/Headers.*HTTPHeader/a\	Path          string              `json:"path,omitempty"`' "$TARGET"
    fi
    echo "  + Path added"
fi

grep -n 'DelHost\|Path' "$TARGET"
echo "=== Patch 01 done ==="
