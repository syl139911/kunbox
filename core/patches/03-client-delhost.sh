#!/bin/bash
# Patch 03: Patch sing HTTP client.go to support DelHost logic
#
# 修改 sing 库的 HTTP 客户端：
# 1. Client 结构体添加 delHost 字段
# 2. Options 结构体添加 DelHost 字段
# 3. NewClient 中传递 delHost
# 4. DialContext 中：当 delHost=true 时，使用 Opaque URL 并清空 Host
#
# 目标文件: sing 库的 protocol/http/client.go
# 依赖: sing v0.8.9 (通过 go mod download 缓存)

set -e

CLIENT_GO="$1"

if [ ! -f "$CLIENT_GO" ]; then
    echo "ERROR: $CLIENT_GO not found!"
    find "$(dirname "$CLIENT_GO")/../.." -name "client.go" -path "*/http/*" 2>/dev/null || true
    exit 1
fi

echo "=== Patch 03: sing protocol/http/client.go - DelHost support ==="
echo "Target: $CLIENT_GO"

echo "=== Before patch ==="
grep -n 'host\|Host\|delHost\|Fqdn' "$CLIENT_GO" || true

# 3a: Add delHost field to Client struct (after 'host       string')
sed -i '/^\thost.*string$/a\	delHost    bool' "$CLIENT_GO"

# 3b: Add DelHost field to Options struct (after 'Headers  http.Header')
sed -i '/^\tHeaders.*http\.Header$/a\	DelHost  bool' "$CLIENT_GO"

# 3c: Add delHost assignment in NewClient (after 'headers:    options.Headers,')
sed -i '/^\theaders:.*options\.Headers,$/a\			delHost:    options.DelHost,' "$CLIENT_GO"

# 3d: Skip host check modification — Patch 04 handles delHost in raw TCP section
# (Patch 04 removes the request block entirely, so modifying host check here would
# reference a deleted variable and cause a compile error.)
echo "NOTE: Skipping host check modification (Patch 04 handles delHost via raw TCP)"

echo "=== After patch ==="
grep -n 'delHost\|DelHost\|Opaque\|target' "$CLIENT_GO" || true

# Verify Options struct has DelHost
if ! grep -q 'DelHost.*bool' "$CLIENT_GO"; then
    echo "ERROR: DelHost field not found in client.go after patching!"
    exit 1
fi
echo "OK: DelHost field confirmed in client.go"

echo "=== Patched Options struct ==="
sed -n '/type Options struct/,/^}/p' "$CLIENT_GO"

echo "=== Patched DialContext host check ==="
grep -n -A5 'delHost' "$CLIENT_GO"

echo "=== Patch 03 applied ==="
