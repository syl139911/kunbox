#!/bin/bash
# Patch 04: Replace Go HTTP CONNECT with raw TCP write (TPBox style)
#
# 核心原理：
# Go 标准库 req.Write() 会自动 normalize HTTP：
#   - 修正 URL 格式
#   - 自动补 Host
#   - 规范化 CONNECT 行
# 导致 CONNECT 始终是标准格式，运营商能识别。
#
# TPBox 做法：绕过标准库，直接 conn.Write([]byte(raw))
#   CONNECT host:port@dingtalk.com HTTP/1.1\r\n
#   \r\n
#
# 效果：运营商看到 @dingtalk.com，误判为钉钉流量，放行。
#
# 前置条件：Patch 01-03 已应用（DelHost/Path 字段已存在）
# 目标文件: sing 协议的 protocol/http/client.go

set -e

CLIENT_GO="$1"

if [ ! -f "$CLIENT_GO" ]; then
    echo "ERROR: $CLIENT_GO not found"
    find "$(dirname "$CLIENT_GO")/../../.." -name "client.go" -path "*/http/*" 2>/dev/null || true
    exit 1
fi

echo "=== Patch 04: raw TCP CONNECT (TPBox style) ==="
echo "Target: $CLIENT_GO"

echo "=== Before patch ==="
grep -n 'request.Write\|http.NewRequest\|MethodConnect' "$CLIENT_GO" || true

# --- Step 1: 确认 Path 字段存在 (Patch 01 应该已经加了) ---
if ! grep -q 'Path.*string.*json:"path' "$CLIENT_GO"; then
    echo "NOTE: Path field not found in Options, adding..."
    sed -i '/Headers.*http.Header/a\\tPath          string              `json:"path,omitempty"`' "$CLIENT_GO"
fi

# 确认 path 在 Client 结构体中
if ! grep -q 'path.*string' "$CLIENT_GO"; then
    echo "NOTE: path field not found in Client struct, adding..."
    # 下面 Python 脚本会处理，这里只是标记
fi

# --- Step 2: 用 Python 精准替换 request 块为 raw TCP ---
python3 -c "
import sys

target = sys.argv[1]
with open(target, 'r') as f:
    lines = f.readlines()

new_lines = []
i = 0
replaced = False

while i < len(lines):
    line = lines[i]

    # 找到 request := &http.Request{ 开始行
    if 'request := &http.Request{' in line and not replaced:
        # 找到 request.Write(conn) 结束行
        j = i
        brace_depth = 0
        request_write_line = -1

        while j < len(lines):
            # 计算大括号深度
            brace_depth += lines[j].count('{') - lines[j].count('}')

            # request 块闭合后，找 request.Write
            if brace_depth <= 0 and j > i:
                if 'request.Write(conn)' in lines[j]:
                    request_write_line = j
                    break
                # 如果闭合后不是 Write，可能 Write 在下一行
                if j + 1 < len(lines) and 'request.Write(conn)' in lines[j + 1]:
                    request_write_line = j + 1
                    break
            j += 1

        if request_write_line >= 0:
            # 找到了，替换从 request := 到 request.Write(conn) 的全部内容
            indent = '\t\t\t'
            new_lines.append(indent + '// === TPBox raw TCP CONNECT (Patch 04) ===\n')
            new_lines.append(indent + '// Bypass Go http.Request.Write() normalization\n')
            new_lines.append(indent + '// Go 会自动修正 CONNECT 格式，导致免流失效\n')
            new_lines.append(indent + 'target := destination.String()\n')
            new_lines.append(indent + 'if c.path != \"\" {\n')
            new_lines.append(indent + '\ttarget += c.path\n')
            new_lines.append(indent + '}\n')
            new_lines.append(indent + '\n')
            new_lines.append(indent + 'raw := fmt.Sprintf(\"CONNECT %s HTTP/1.1\\r\\n\", target)\n')
            new_lines.append(indent + '\n')
            new_lines.append(indent + 'if !c.delHost {\n')
            new_lines.append(indent + '\thostHeader := destination.String()\n')
            new_lines.append(indent + '\tif c.host != \"\" && c.host != destination.Fqdn {\n')
            new_lines.append(indent + '\t\thostHeader = c.host\n')
            new_lines.append(indent + '\t}\n')
            new_lines.append(indent + '\traw += fmt.Sprintf(\"Host: %s\\r\\n\", hostHeader)\n')
            new_lines.append(indent + '}\n')
            new_lines.append(indent + '\n')
            new_lines.append(indent + '// 只保留用户自定义 headers，跳过 Go 自动添加的\n')
            new_lines.append(indent + '// (Go 标准库会加 User-Agent: Go-http-client/1.1 等，暴露身份)\n')
            new_lines.append(indent + 'skipHeaders := map[string]bool{\n')
            new_lines.append(indent + '\t\"user-agent\":       true,\n')
            new_lines.append(indent + '\t\"proxy-connection\": true,\n')
            new_lines.append(indent + '\t\"host\":             true, // Host 已在上面处理\n')
            new_lines.append(indent + '}\n')
            new_lines.append(indent + 'if options.Headers != nil {\n')
            new_lines.append(indent + '\tfor key, values := range options.Headers {\n')
            new_lines.append(indent + '\t\tif skipHeaders[strings.ToLower(key)] {\n')
            new_lines.append(indent + '\t\t\tcontinue\n')
            new_lines.append(indent + '\t\t}\n')
            new_lines.append(indent + '\t\tfor _, value := range values {\n')
            new_lines.append(indent + '\t\t\traw += fmt.Sprintf(\"%s: %s\\r\\n\", key, value)\n')
            new_lines.append(indent + '\t\t}\n')
            new_lines.append(indent + '\t}\n')
            new_lines.append(indent + '}\n')
            new_lines.append(indent + '\n')
            new_lines.append(indent + 'raw += \"\\r\\n\"\n')
            new_lines.append(indent + '\n')
            new_lines.append(indent + '// Debug: uncomment below to log raw CONNECT in logcat\n')
            new_lines.append(indent + '// log.Println(\"[KunBox] raw CONNECT:\", raw)\n')
            new_lines.append(indent + '\n')
            new_lines.append(indent + '// 直接写 TCP，不经过 Go HTTP 格式化\n')
            new_lines.append(indent + '_, err = conn.Write([]byte(raw))\n')
            new_lines.append(indent + 'if err != nil {\n')
            new_lines.append(indent + '\tconn.Close()\n')
            new_lines.append(indent + '\treturn nil, err\n')
            new_lines.append(indent + '}\n')
            # 跳过已替换的行
            i = request_write_line + 1
            replaced = True
            continue
        else:
            # 没找到 Write，不替换
            pass

    new_lines.append(line)
    i += 1

with open(target, 'w') as f:
    f.writelines(new_lines)

if replaced:
    print('OK: Replaced http.Request block with raw TCP CONNECT')
else:
    print('WARNING: Could not find request block to replace')
    sys.exit(1)
" "$CLIENT_GO"

echo "=== After patch ==="
grep -n 'raw\|fmt.Sprintf.*CONNECT\|conn.Write\|c\.path\|c\.delHost' "$CLIENT_GO" || true

# --- Step 3: 确认 path 和 delHost 在 Client 结构体中 ---
if ! grep -q 'path.*string' "$CLIENT_GO"; then
    echo "NOTE: path field not in Client struct, adding..."
    sed -i '/delHost.*bool/a\\tpath          string' "$CLIENT_GO"
fi

# --- Step 4: 确认 NewClient 中赋值 path ---
if ! grep -q 'path:.*options.Path' "$CLIENT_GO"; then
    echo "NOTE: path assignment not in NewClient, adding..."
    sed -i '/delHost:.*options.DelHost/a\\t\tpath:          options.Path,' "$CLIENT_GO"
fi

# --- Step 5: 确认 fmt/log/strings 已导入 ---
python3 -c "
import sys
target = sys.argv[1]
with open(target, 'r') as f:
    content = f.read()

needed = {'fmt': '\"fmt\"', 'strings': '\"strings\"'}
missing = [pkg for pkg, imp in needed.items() if imp not in content]

if missing:
    print(f'NOTE: Missing imports: {missing}, adding...')
    # Find 'import (' line and add after it
    lines = content.split('\n')
    new_lines = []
    for line in lines:
        new_lines.append(line)
        if line.strip() == 'import (':
            for pkg in missing:
                new_lines.append('\t' + needed[pkg])
    with open(target, 'w') as f:
        f.write('\n'.join(new_lines))
    print(f'OK: Added {missing}')
else:
    print('OK: All imports present')
" "$CLIENT_GO"

# --- 验证 ---
echo ""
echo "=== Verification ==="
echo "--- Raw CONNECT code ---"
grep -n -A3 'CONNECT.*HTTP/1.1' "$CLIENT_GO" || echo "WARNING: raw CONNECT not found!"
echo ""
echo "--- Path + DelHost fields ---"
grep -n 'path\|delHost\|Path\|DelHost' "$CLIENT_GO" || echo "WARNING: fields not found!"
echo ""
echo "--- fmt/log/strings imported ---"
grep -n '"fmt"\|"log"\|"strings"' "$CLIENT_GO" || echo "WARNING: imports not found!"
echo ""
echo "--- Skip headers (Go auto-adds) ---"
grep -n 'skipHeaders\|user-agent\|proxy-connection' "$CLIENT_GO" || echo "WARNING: skip headers not found!"
echo ""
echo "--- Debug log (commented out) ---"
grep -n 'KunBox.*raw' "$CLIENT_GO" || echo "WARNING: debug log not found!"
echo ""
echo "=== Patch 04 applied ==="
