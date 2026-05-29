#!/bin/bash
# Patch 11: sing protocol/http/client.go — Debug Logging
#
# 在所有关键路径添加 stderr 日志，方便排查连接问题
# 必须在 patch 03, 07, 10 之后运行
#
# 覆盖的路径:
#   - dial to proxy (成功/失败)
#   - TLS 握手详情
#   - httpFirst/httpsFirst 写入失败
#   - CONNECT 写入失败
#   - ReadResponse 失败
#   - ReadFullFrom 失败
#   - 非 200 状态码
#
# 目标文件: sing 库的 protocol/http/client.go

set -e
CLIENT_GO="$1"
[ -f "$CLIENT_GO" ] || { echo "ERROR: $CLIENT_GO not found"; exit 1; }

echo "=== Patch 11: client.go - Debug Logging ==="

# --- 确保 os 和 fmt 已导入 ---
python3 - "$CLIENT_GO" << 'PYEOF'
import sys
target = sys.argv[1]
with open(target, 'r') as f:
    content = f.read()

needed = {'fmt': '"fmt"', 'os': '"os"'}
missing = [p for p, imp in needed.items() if imp not in content]
if missing:
    lines = content.split('\n')
    new_lines = []
    for line in lines:
        new_lines.append(line)
        if line.strip() == 'import (':
            for pkg in missing:
                new_lines.append('\t' + needed[pkg])
    with open(target, 'w') as f:
        f.write('\n'.join(new_lines))
    print(f'OK: Added imports: {missing}')
else:
    print('OK: All imports present')
PYEOF

# --- 添加日志 ---
python3 - "$CLIENT_GO" << 'PYEOF'
import sys

target = sys.argv[1]
with open(target, 'r') as f:
    content = f.read()

changes = 0

# 1. dial 失败日志: conn, err := c.dialer.DialContext(...) 后的 if err != nil
old_dial = '''conn, err := c.dialer.DialContext(ctx, N.NetworkTCP, c.serverAddr)
\t\tif err != nil {
\t\t\treturn nil, err
\t\t}'''
new_dial = '''conn, err := c.dialer.DialContext(ctx, N.NetworkTCP, c.serverAddr)
\t\tif err != nil {
\t\t\tfmt.Fprintf(os.Stderr, "[KunBox-HTTP] dial to proxy FAILED: %s err=%v\\n", c.serverAddr, err)
\t\t\treturn nil, err
\t\t}
\t\tfmt.Fprintf(os.Stderr, "[KunBox-HTTP] dial to proxy OK: %s\\n", c.serverAddr)'''
if old_dial in content:
    content = content.replace(old_dial, new_dial, 1)
    changes += 1
    print('OK: Added dial log')
else:
    print('WARN: dial block not found')

# 2. httpFirst 写入失败日志 (patch 07 生成的代码)
old_first_err = '''_, err = conn.Write([]byte(c.httpFirst))
\t\t\tif err != nil {
\t\t\t\tconn.Close()
\t\t\t\treturn nil, err
\t\t\t}'''
new_first_err = '''_, err = conn.Write([]byte(firstContent))
\t\t\tif err != nil {
\t\t\t\tfmt.Fprintf(os.Stderr, "[KunBox-HTTP] httpFirst write FAILED: err=%v\\n", err)
\t\t\t\tconn.Close()
\t\t\t\treturn nil, err
\t\t\t}'''
# Also handle the patch 07 version with c.httpFirst
old_first_err2 = '''_, err = conn.Write([]byte(c.httpFirst))
\t\tif err != nil {
\t\t\tconn.Close()
\t\t\treturn nil, err
\t\t}'''
new_first_err2 = '''_, err = conn.Write([]byte(c.httpFirst))
\t\tif err != nil {
\t\t\tfmt.Fprintf(os.Stderr, "[KunBox-HTTP] httpFirst write FAILED: err=%v\\n", err)
\t\t\tconn.Close()
\t\t\treturn nil, err
\t\t}'''
if old_first_err in content:
    content = content.replace(old_first_err, new_first_err, 1)
    changes += 1
    print('OK: Added httpFirst write error log (patch 10 version)')
elif old_first_err2 in content:
    content = content.replace(old_first_err2, new_first_err2, 1)
    changes += 1
    print('OK: Added httpFirst write error log (patch 07 version)')
else:
    print('WARN: httpFirst write block not found')

# 3. CONNECT 写入失败日志 + 请求日志
old_connect = '''_, err = conn.Write([]byte(raw.String()))
\t\tif err != nil {
\t\t\tconn.Close()
\t\t\treturn nil, err
\t\t}'''
new_connect = '''fmt.Fprintf(os.Stderr, "[KunBox-HTTP] CONNECT >>> %s", raw.String())
\t\t_, err = conn.Write([]byte(raw.String()))
\t\tif err != nil {
\t\t\tfmt.Fprintf(os.Stderr, "[KunBox-HTTP] CONNECT write FAILED: err=%v\\n", err)
\t\t\tconn.Close()
\t\t\treturn nil, err
\t\t}'''
if old_connect in content:
    content = content.replace(old_connect, new_connect, 1)
    changes += 1
    print('OK: Added CONNECT write log')
else:
    print('WARN: CONNECT write block not found')

# 4. ReadResponse 失败日志
old_read = '''response, err := http.ReadResponse(reader, request)
\t\tif err != nil {
\t\t\tconn.Close()
\t\t\treturn nil, err
\t\t}'''
new_read = '''response, err := http.ReadResponse(reader, request)
\t\tif err != nil {
\t\t\tfmt.Fprintf(os.Stderr, "[KunBox-HTTP] ReadResponse FAILED: err=%v\\n", err)
\t\t\tconn.Close()
\t\t\treturn nil, err
\t\t}
\t\tfmt.Fprintf(os.Stderr, "[KunBox-HTTP] proxy response: %d %s\\n", response.StatusCode, response.Status)'''
if old_read in content:
    content = content.replace(old_read, new_read, 1)
    changes += 1
    print('OK: Added ReadResponse log')
else:
    print('WARN: ReadResponse block not found')

# 5. ReadFullFrom 失败日志
old_full = '''_, err = buffer.ReadFullFrom(reader, buffer.FreeLen())
\t\t\tif err != nil {
\t\t\t\tconn.Close()
\t\t\t\treturn nil, err
\t\t\t}'''
new_full = '''_, err = buffer.ReadFullFrom(reader, buffer.FreeLen())
\t\t\tif err != nil {
\t\t\t\tfmt.Fprintf(os.Stderr, "[KunBox-HTTP] ReadFullFrom FAILED: err=%v\\n", err)
\t\t\t\tconn.Close()
\t\t\t\treturn nil, err
\t\t\t}'''
if old_full in content:
    content = content.replace(old_full, new_full, 1)
    changes += 1
    print('OK: Added ReadFullFrom log')
else:
    print('WARN: ReadFullFrom block not found')

with open(target, 'w') as f:
    f.write(content)

print(f'\\nTotal changes: {changes}')
if changes < 4:
    print('WARNING: Expected at least 4 changes, got', changes)
PYEOF

# --- 验证 ---
echo ""
echo "=== Verification ==="
echo "dial FAILED:     $(grep -c 'dial to proxy FAILED' "$CLIENT_GO" || true)"
echo "dial OK:         $(grep -c 'dial to proxy OK' "$CLIENT_GO" || true)"
echo "httpFirst FAIL:  $(grep -c 'httpFirst write FAILED' "$CLIENT_GO" || true)"
echo "CONNECT >>>:     $(grep -c 'CONNECT >>>' "$CLIENT_GO" || true)"
echo "CONNECT FAIL:    $(grep -c 'CONNECT write FAILED' "$CLIENT_GO" || true)"
echo "ReadResponse OK: $(grep -c 'proxy response:' "$CLIENT_GO" || true)"
echo "ReadResp FAIL:   $(grep -c 'ReadResponse FAILED' "$CLIENT_GO" || true)"
echo "ReadFull FAIL:   $(grep -c 'ReadFullFrom FAILED' "$CLIENT_GO" || true)"
echo ""
echo "=== Patch 11 done ==="
