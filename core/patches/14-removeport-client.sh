#!/bin/bash
# Patch 14: sing protocol/http/client.go — RemovePort + Host + 宽松响应解析
#
# 核心改动:
#   1. remove_port: CONNECT 行不带端口
#   2. host 强制覆盖: 独立 Host header 选项
#   3. del_host: 仅删除 Host header，不改变 CONNECT 行
#   4. 宽松响应解析: 手动 ReadString 替代 http.ReadResponse
#
# 必须在 patch 03, 07, 10, 11 之后运行
# 目标文件: sing 库的 protocol/http/client.go

set -e
CLIENT_GO="$1"
[ -f "$CLIENT_GO" ] || { echo "ERROR: $CLIENT_GO not found"; exit 1; }

echo "=== Patch 14: client.go - RemovePort + Host + Lenient Response ==="

python3 - "$CLIENT_GO" << 'PYEOF'
import sys, re

target = sys.argv[1]
with open(target, 'r') as f:
    content = f.read()

# --- 1. 添加字段到 Client struct ---
if 'removePort' not in content:
    # 先尝试带注释的精确匹配
    if 'httpsDel   []string // HTTPS 删除指定 header' in content:
        content = content.replace(
            'httpsDel   []string // HTTPS 删除指定 header',
            'httpsDel   []string // HTTPS 删除指定 header\n\t// ========== KunBox 新增字段 (Patch 05) ==========\n\tremovePort bool   // CONNECT 行不带端口\n\thostOption string // 强制替换 Host header (独立于 headers)\n\t// ============================================'
        )
    # fallback: 匹配 patch 10 写入的无注释版本
    elif 'httpsDel   []string' in content:
        content = content.replace(
            'httpsDel   []string',
            'httpsDel   []string\n\t// ========== KunBox 新增字段 (Patch 05) ==========\n\tremovePort bool   // CONNECT 行不带端口\n\thostOption string // 强制替换 Host header (独立于 headers)\n\t// ============================================'
        )
    # fallback2: 匹配 tab 缩进的无注释版本
    elif 'httpsDel\t[]string' in content:
        content = content.replace(
            'httpsDel\t[]string',
            'httpsDel\t[]string\n\tremovePort bool\n\thostOption string'
        )
    print("  + Client.removePort + hostOption fields added")
else:
    print("  ~ Client fields already exist, skip")

# --- 2. 添加字段到 Options struct ---
if 'RemovePort' not in content:
    # 先尝试带注释的精确匹配
    if 'HttpsDel   []string // HTTPS 删除指定 header' in content:
        content = content.replace(
            'HttpsDel   []string // HTTPS 删除指定 header',
            'HttpsDel   []string // HTTPS 删除指定 header\n\t// ========== KunBox 新增选项 (Patch 05) ==========\n\tRemovePort bool   // CONNECT 行不带端口\n\tHost       string // 强制替换 Host header\n\t// ============================================'
        )
    # fallback: 匹配 patch 10 写入的无注释版本
    elif 'HttpsDel   []string' in content:
        content = content.replace(
            'HttpsDel   []string',
            'HttpsDel   []string\n\t// ========== KunBox 新增选项 (Patch 05) ==========\n\tRemovePort bool   // CONNECT 行不带端口\n\tHost       string // 强制替换 Host header\n\t// ============================================'
        )
    # fallback2: 匹配 tab 缩进的无注释版本
    elif 'HttpsDel\t[]string' in content:
        content = content.replace(
            'HttpsDel\t[]string',
            'HttpsDel\t[]string\n\tRemovePort bool\n\tHost       string'
        )
    print("  + Options.RemovePort + Host fields added")
else:
    print("  ~ Options fields already exist, skip")

# --- 3. 添加 getter 方法 ---
if 'func (c *Client) RemovePort()' not in content:
    content = content.replace(
        'func (c *Client) HttpsFirst() string       { return c.httpsFirst }',
        'func (c *Client) HttpsFirst() string       { return c.httpsFirst }\nfunc (c *Client) RemovePort() bool         { return c.removePort }\nfunc (c *Client) Host() string             { return c.hostOption }'
    )
    print("  + RemovePort() + Host() getters added")
else:
    print("  ~ Getters already exist, skip")

# --- 4. 在 NewClient 赋值 ---
if 'removePort: options.RemovePort' not in content:
    # 先尝试带分隔注释的精确匹配
    if 'httpsDel:   options.HttpsDel,\n\t\t// ==========================================' in content:
        content = content.replace(
            'httpsDel:   options.HttpsDel,\n\t\t// ==========================================',
            'httpsDel:   options.HttpsDel,\n\t\t// ==========================================\n\t\t// ========== KunBox 赋值 (Patch 05) ==========\n\t\tremovePort: options.RemovePort,\n\t\thostOption: options.Host,\n\t\t// =========================================='
        )
    # fallback: 匹配无注释的 httpsDel 赋值行
    elif 'httpsDel:   options.HttpsDel,' in content:
        content = content.replace(
            'httpsDel:   options.HttpsDel,',
            'httpsDel:   options.HttpsDel,\n\t\t\tremovePort: options.RemovePort,\n\t\t\thostOption: options.Host,'
        )
    print("  + NewClient removePort + hostOption assignment added")
else:
    print("  ~ NewClient assignment already exists, skip")

# --- 5. 替换 CONNECT 目标构建逻辑 ---
# del_host 不改变 CONNECT 行，只删 Host header
# path 拼接在 host:port 后面
# removePort 去掉端口
old_target_block = '''\t// --- 构建 CONNECT 目标 ---
\ttarget := destination.String()
\tif c.path != "" {
\t\ttarget += c.path
\t}'''

new_target_block = '''\t// --- 构建 CONNECT 目标 ---
\t// path 特性: 拼在 host:port 后面 (如 "host:port@gw.alicdn.com")
\t// removePort: 去掉端口 (如 "host" 而不是 "host:443")
\t// del_host: 不改变 CONNECT 行，只删除 Host header
\tvar target string
\tif c.removePort {
\t\ttarget = destination.Fqdn
\t} else {
\t\ttarget = destination.String()
\t}
\tif c.path != "" {
\t\ttarget += c.path
\t}'''

if 'if c.removePort' not in content:
    content = content.replace(old_target_block, new_target_block)
    print("  + CONNECT target logic rewritten (removePort + path)")
else:
    print("  ~ CONNECT target logic already rewritten, skip")

# --- 6. 替换 Host header 构建逻辑 ---
# del_host=true: 完全不发 Host header
# hostOption: 强制替换
old_host_block = '''\tif c.host != "" {
\t\tfmt.Fprintf(&raw, "Host: %s\\r\\n", c.host)
\t} else if !c.delHost {
\t\tfmt.Fprintf(&raw, "Host: %s\\r\\n", destination.String())
\t}'''

new_host_block = '''\t// --- Host header ---
\t// del_host=true: 完全不发 Host header (文档: "删除Host字段")
\t// hostOption: 强制替换 Host 值
\t// 默认: Host = destination
\tif !c.delHost {
\t\tvar hostValue string
\t\tif c.hostOption != "" {
\t\t\thostValue = c.hostOption
\t\t} else if c.host != "" {
\t\t\thostValue = c.host
\t\t} else {
\t\t\thostValue = destination.String()
\t\t}
\t\tfmt.Fprintf(&raw, "Host: %s\\r\\n", hostValue)
\t}'''

if 'if !c.delHost' not in content or 'hostValue' not in content:
    content = content.replace(old_host_block, new_host_block)
    print("  + Host header logic rewritten (del_host skips Host)")
else:
    print("  ~ Host header logic already rewritten, skip")

# --- 7. 替换响应解析 (http.ReadResponse → 手动 ReadString) ---
old_response_block = '''\t// === Step 3: 读取响应 ===
\t// 用最小的 request 对象让 http.ReadResponse 工作
\trequest := &http.Request{
\t\tMethod: http.MethodConnect,
\t\tURL:    &url.URL{Host: destination.String()},
\t}

\treader := std_bufio.NewReader(conn)
\tresponse, err := http.ReadResponse(reader, request)
\tif err != nil {
\t\tfmt.Fprintf(os.Stderr, "[KunBox-HTTP] ReadResponse FAILED: err=%v\\n", err)
\t\tconn.Close()
\t\treturn nil, err
\t}
\t// [KunBox Debug] 代理响应
\tfmt.Fprintf(os.Stderr, "[KunBox-HTTP] proxy response: %d %s\\n", response.StatusCode, response.Status)
\tif response.StatusCode == http.StatusOK {
\t\tif reader.Buffered() > 0 {
\t\t\tbuffer := buf.NewSize(reader.Buffered())
\t\t\t_, err = buffer.ReadFullFrom(reader, buffer.FreeLen())
\t\t\tif err != nil {
\t\t\t\tfmt.Fprintf(os.Stderr, "[KunBox-HTTP] ReadFullFrom FAILED: err=%v\\n", err)
\t\t\t\tconn.Close()
\t\t\t\treturn nil, err
\t\t\t}
\t\t\tconn = bufio.NewCachedConn(conn, buffer)
\t\t}
\t\treturn conn, nil
\t} else {
\t\tconn.Close()
\t\tswitch response.StatusCode {
\t\tcase http.StatusProxyAuthRequired:
\t\t\treturn nil, E.New("authentication required")
\t\tcase http.StatusMethodNotAllowed:
\t\t\treturn nil, E.New("method not allowed")
\t\tdefault:
\t\t\treturn nil, E.New("unexpected status: ", response.Status)
\t\t}
\t}'''

new_response_block = '''\t// === Step 3: 宽松响应解析 ===
\t// 替换 http.ReadResponse()，接受非标准响应:
\t//   "HTTP/1.1 200 OK"
\t//   "HTTP/1.0 200 OK"
\t//   "200 OK"
\t//   甚至只包含 "200" 的行
\treader := std_bufio.NewReader(conn)

\t// 读取状态行
\tstatusLine, err := reader.ReadString('\\n')
\tif err != nil {
\t\tfmt.Fprintf(os.Stderr, "[KunBox-HTTP] read status line FAILED: err=%v\\n", err)
\t\tconn.Close()
\t\treturn nil, E.New("failed to read proxy response: ", err)
\t}
\tstatusLine = strings.TrimSpace(statusLine)
\tfmt.Fprintf(os.Stderr, "[KunBox-HTTP] proxy status line: %q\\n", statusLine)

\t// 检查是否包含 200
\tif !strings.Contains(statusLine, "200") {
\t\t// 吃掉剩余 headers 再报错
\t\tfor {
\t\t\tline, readErr := reader.ReadString('\\n')
\t\t\tif line == "\\r\\n" || line == "\\n" || readErr != nil {
\t\t\t\tbreak
\t\t\t}
\t\t}
\t\tconn.Close()
\t\treturn nil, E.New("connect failed: ", statusLine)
\t}

\t// 吃掉剩余 response headers 直到空行
\tfor {
\t\tline, readErr := reader.ReadString('\\n')
\t\tif line == "\\r\\n" || line == "\\n" || readErr != nil {
\t\t\tbreak
\t\t}
\t}

\t// 连接建立成功
\tif reader.Buffered() > 0 {
\t\tbuffer := buf.NewSize(reader.Buffered())
\t\t_, err = buffer.ReadFullFrom(reader, buffer.FreeLen())
\t\tif err != nil {
\t\t\tfmt.Fprintf(os.Stderr, "[KunBox-HTTP] ReadFullFrom FAILED: err=%v\\n", err)
\t\t\tconn.Close()
\t\t\treturn nil, err
\t\t}
\t\tconn = bufio.NewCachedConn(conn, buffer)
\t}
\treturn conn, nil'''

if 'statusLine, err := reader.ReadString' not in content:
    content = content.replace(old_response_block, new_response_block)
    print("  + Response parsing rewritten (lenient ReadString)")
else:
    print("  ~ Response parsing already rewritten, skip")

# --- 8. 清理不再需要的 import ---
if 'url.URL{' not in content and '"net/url"' in content:
    content = content.replace('\t"net/url"\n', '')
    print("  - Removed unused net/url import")
else:
    print("  ~ net/url import still in use or already removed")

with open(target, 'w') as f:
    f.write(content)

print("  ✅ Patch 14 applied successfully")
PYEOF

echo "=== Patch 14 done ==="
