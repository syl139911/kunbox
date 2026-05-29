#!/bin/bash
# Patch 10: sing protocol/http/client.go — HttpsFirst + HttpDel + HttpsDel
#
# 核心改动：HTTP/HTTPS 分离处理
# - httpsFirst: HTTPS CONNECT (端口443) 独立 preface
# - httpDel/httpsDel: 按协议删除指定 header
# - 端口感知: isHttps = destination.Port == 443
#
# 目标文件: sing 库的 protocol/http/client.go (通过 go mod download 缓存)

set -e
CLIENT_GO="$1"
[ -f "$CLIENT_GO" ] || { echo "ERROR: $CLIENT_GO not found"; exit 1; }

echo "=== Patch 10: client.go - HttpsFirst + HttpDel + HttpsDel ==="

# --- Step 1: 注入新字段到 Client struct ---
if ! grep -q 'httpsFirst' "$CLIENT_GO"; then
    sed -i '/httpFirst.*string$/a\	httpsFirst string\n\thttpDel    []string\n\thttpsDel   []string' "$CLIENT_GO"
    echo "  + Client.httpsFirst/httpDel/httpsDel added"
else
    echo "  ~ Client fields already exist, skip"
fi

# --- Step 2: 注入新字段到 Options struct ---
if ! grep -q 'HttpsFirst.*string' "$CLIENT_GO"; then
    sed -i '/HttpFirst.*string$/a\	HttpsFirst string\n\tHttpDel    []string\n\tHttpsDel   []string' "$CLIENT_GO"
    echo "  + Options.HttpsFirst/HttpDel/HttpsDel added"
else
    echo "  ~ Options fields already exist, skip"
fi

# --- Step 3: 注入赋值到 NewClient ---
if ! grep -q 'httpsFirst:.*options\.HttpsFirst' "$CLIENT_GO"; then
    sed -i '/httpFirst:.*options\.HttpFirst,/a\			httpsFirst: options.HttpsFirst,\n\t\t\thttpDel:    options.HttpDel,\n\t\t\thttpsDel:   options.HttpsDel,' "$CLIENT_GO"
    echo "  + NewClient assignments added"
else
    echo "  ~ NewClient assignments already exist, skip"
fi

# --- Step 4: 替换 CONNECT 逻辑为端口感知版本 ---
python3 - "$CLIENT_GO" << 'PYEOF'
import sys, re

target = sys.argv[1]
with open(target, 'r') as f:
    content = f.read()

# 替换 http_first 写入块为端口感知版本
old_first = '''\t\t// === KunBox http_first (HTTP preface) ===
\t\t// conn 是原始 TCP 连接，Write 直接进内核 socket buffer，无需 flush
\t\tif c.httpFirst != "" {
\t\t\t_, err = conn.Write([]byte(c.httpFirst))
\t\t\tif err != nil {
\t\t\t\tconn.Close()
\t\t\t\treturn nil, err
\t\t\t}
\t\t}
\t\t'''

new_first = '''\t\t// 判断目标是否为 HTTPS (端口 443)
\t\tisHttps := destination.Port == 443 || c.httpsFirst != ""

\t\t// === KunBox http_first / https_first (preface) ===
\t\t// HTTP 和 HTTPS 各自独立的 preface，互不 fallback
\t\tvar firstContent string
\t\tif isHttps {
\t\t\tfirstContent = c.httpsFirst
\t\t} else {
\t\t\tfirstContent = c.httpFirst
\t\t}
\t\tif firstContent != "" {
\t\t\t_, err = conn.Write([]byte(firstContent))
\t\t\tif err != nil {
\t\t\t\tconn.Close()
\t\t\t\treturn nil, err
\t\t\t}
\t\t}
\t\t'''

if old_first in content:
    content = content.replace(old_first, new_first)
    print('OK: Replaced http_first block with port-aware version')
else:
    print('WARN: http_first block not found (may already be patched)')

# 替换 header skip 逻辑为动态 del 集合
old_skip = '''\t\tskipHeaders := map[string]bool{
\t\t\t"user-agent":       true,
\t\t\t"proxy-connection": true,
\t\t\t"host":             true,
\t\t}
\t\tif c.headers != nil {
\t\t\tfor key, values := range c.headers {
\t\t\t\tif skipHeaders[strings.ToLower(key)] {
\t\t\t\t\tcontinue
\t\t\t\t}
\t\t\t\tfor _, value := range values {
\t\t\t\t\tfmt.Fprintf(&raw, "%s: %s\\r\\n", key, value)
\t\t\t\t}
\t\t\t}
\t\t}'''

new_skip = '''\t\t// === KunBox: 构建 del headers 集合 ===
\t\t// 根据 HTTP/HTTPS 选择不同的 del 列表
\t\tdelHeaders := make(map[string]bool)
\t\tdelHeaders["user-agent"] = true
\t\tdelHeaders["proxy-connection"] = true
\t\tdelHeaders["host"] = true
\t\tif isHttps {
\t\t\tfor _, h := range c.httpsDel {
\t\t\t\tdelHeaders[strings.ToLower(h)] = true
\t\t\t}
\t\t} else {
\t\t\tfor _, h := range c.httpDel {
\t\t\t\tdelHeaders[strings.ToLower(h)] = true
\t\t\t}
\t\t}

\t\tif c.headers != nil {
\t\t\tfor key, values := range c.headers {
\t\t\t\tif delHeaders[strings.ToLower(key)] {
\t\t\t\t\tcontinue
\t\t\t\t}
\t\t\t\tfor _, value := range values {
\t\t\t\t\tfmt.Fprintf(&raw, "%s: %s\\r\\n", key, value)
\t\t\t\t}
\t\t\t}
\t\t}'''

if old_skip in content:
    content = content.replace(old_skip, new_skip)
    print('OK: Replaced skipHeaders with dynamic del set')
else:
    print('WARN: skipHeaders block not found (may already be patched)')

with open(target, 'w') as f:
    f.write(content)
PYEOF

# --- 验证 ---
echo ""
echo "=== Verification ==="
grep -n 'httpsFirst\|httpDel\|httpsDel\|isHttps\|delHeaders' "$CLIENT_GO" | head -20
echo ""
echo "=== Patch 10 done ==="
