#!/bin/bash
# Patch 14: sing protocol/http/client.go — RemovePort + Host + DelHost 修复 + 宽松响应解析
#
# 核心改动:
#   1. del_host 真正生效: CONNECT 行改用 path-only
#   2. remove_port: CONNECT 行不带端口
#   3. host 强制覆盖: 独立 Host header 选项
#   4. 宽松响应解析: 手动 ReadString 替代 http.ReadResponse
#
# 必须在 patch 03, 07, 10, 11 之后运行
# 目标文件: sing 库的 protocol/http/client.go

set -e
CLIENT_GO="$1"
[ -f "$CLIENT_GO" ] || { echo "ERROR: $CLIENT_GO not found"; exit 1; }

echo "=== Patch 14: client.go - RemovePort + Host + DelHost Fix + Lenient Response ==="

python3 - "$CLIENT_GO" << 'PYEOF'
import sys, re

target = sys.argv[1]
with open(target, 'r') as f:
    content = f.read()

# --- 1. 添加字段到 Client struct ---
if 'removePort' not in content:
    # 在 hostOption 不存在的情况下，在 httpsDel 之后添加
    content = content.replace(
        'httpsDel   []string // HTTPS 删除指定 header',
        'httpsDel   []string // HTTPS 删除指定 header\n\t// ========== KunBox 新增字段 (Patch 05) ==========\n\tremovePort bool   // CONNECT 行不带端口\n\thostOption string // 强制替换 Host header (独立于 headers)\n\t// ============================================'
    )
    print("  + Client.removePort + hostOption fields added")
else:
    print("  ~ Client fields already exist, skip")

# --- 2. 添加字段到 Options struct ---
if 'RemovePort' not in content:
    content = content.replace(
        'HttpsDel   []string // HTTPS 删除指定 header',
        'HttpsDel   []string // HTTPS 删除指定 header\n\t// ========== KunBox 新增选项 (Patch 05) ==========\n\tRemovePort bool   // CONNECT 行不带端口\n\tHost       string // 强制替换 Host header\n\t// ============================================'
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
    content = content.replace(
        'httpsDel:   options.HttpsDel,\n\t\t// ==========================================',
        'httpsDel:   options.HttpsDel,\n\t\t// ==========================================\n\t\t// ========== KunBox 赋值 (Patch 05) ==========\n\t\tremovePort: options.RemovePort,\n\t\thostOption: options.Host,\n\t\t// =========================================='
    )
    print("  + NewClient removePort + hostOption assignment added")
else:
    print("  ~ NewClient assignment already exists, skip")

# --- 5. 替换 CONNECT 目标构建逻辑 ---
# 找到并替换从 "target := destination.String()" 到 "var raw strings.Builder" 之间的代码
old_target_block = '''\t// --- 构建 CONNECT 目标 ---
\ttarget := destination.String()
\tif c.path != "" {
\t\ttarget += c.path
\t}'''

new_target_block = '''\t// --- 构建 CONNECT 目标 ---
\t// 模式判断:
\t//   del_host=true:  target = path (仅 path，如 /@dingtalk.com)
\t//   remove_port=true: target = host (不带端口)
\t//   默认:           target = host:port
\tvar target string

\tif c.delHost {
\t\t// del_host 模式: CONNECT 行只用 path，完全隐藏真实目标
\t\ttarget = c.path
\t\tif target == "" {
\t\t\t// del_host 但没配 path，fallback 到 host only
\t\t\ttarget = destination.Fqdn
\t\t} else {
\t\t\t// path 没带 / 开头则自动补
\t\t\tif !strings.HasPrefix(target, "/") {
\t\t\t\ttarget = "/" + target
\t\t\t}
\t\t}
\t} else if c.removePort {
\t\t// remove_port 模式: CONNECT 行不带端口
\t\ttarget = destination.Fqdn
\t\tif c.path != "" {
\t\t\ttarget += c.path
\t\t}
\t} else {
\t\t// 标准模式: host:port
\t\ttarget = destination.String()
\t\tif c.path != "" {
\t\t\ttarget += c.path
\t\t}
\t}'''

if 'if c.delHost' not in content:
    content = content.replace(old_target_block, new_target_block)
    print("  + CONNECT target logic rewritten (delHost + removePort)")
else:
    print("  ~ CONNECT target logic already rewritten, skip")

# --- 6. 替换 Host header 构建逻辑 ---
old_host_block = '''\tif c.host != "" {
\t\tfmt.Fprintf(&raw, "Host: %s\\r\\n", c.host)
\t} else if !c.delHost {
\t\tfmt.Fprintf(&raw, "Host: %s\\r\\n", destination.String())
\t}'''

new_host_block = '''\t// --- Host header ---
\t// 优先级: hostOption > headers 中的 Host > destination
\tvar hostValue string
\tif c.hostOption != "" {
\t\thostValue = c.hostOption
\t} else if c.host != "" {
\t\thostValue = c.host
\t} else if !c.delHost {
\t\thostValue = destination.String()
\t}
\tif hostValue != "" {
\t\tfmt.Fprintf(&raw, "Host: %s\\r\\n", hostValue)
\t}'''

if 'hostValue' not in content:
    content = content.replace(old_host_block, new_host_block)
    print("  + Host header logic rewritten (hostOption priority)")
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
# http.ReadResponse 不再使用，但 http.Request 和 url.URL 可能还被其他地方引用
# 实际上我们不再需要 net/url，但保留 http 包因为 http.Header 等还在用
# 移除 url import 如果不再使用
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
