#!/bin/bash
# Patch 03: sing protocol/http/client.go — DelHost + Raw TCP CONNECT
#
# 合并自: 03-client-delhost.sh + 04-connect-raw.sh
# 核心改动：用 raw TCP write 替换 Go http.Request.Write()，实现 TPBox 免流
# 目标文件: sing 库的 protocol/http/client.go (通过 go mod download 缓存)
#
# 注意: 原始 sing v0.8.9 已有 path/Path 字段，仅需注入 delHost/DelHost

set -e
CLIENT_GO="$1"
[ -f "$CLIENT_GO" ] || { echo "ERROR: $CLIENT_GO not found"; exit 1; }

echo "=== Patch 03: client.go - DelHost + Raw TCP CONNECT ==="
echo "Target: $CLIENT_GO"

# --- Step 1: 仅注入 delHost (原始文件已有 path/Path) ---
# Client struct: 在 host 字段后加 delHost
if ! grep -q 'delHost' "$CLIENT_GO"; then
    sed -i '/^\thost.*string$/a\	delHost    bool' "$CLIENT_GO"
    echo "  + Client.delHost added"
else
    echo "  ~ Client.delHost already exists, skip"
fi

# Options struct: 在 Headers 后加 DelHost
if ! grep -q 'DelHost.*bool' "$CLIENT_GO"; then
    sed -i '/^\tHeaders.*http\.Header$/a\	DelHost  bool' "$CLIENT_GO"
    echo "  + Options.DelHost added"
else
    echo "  ~ Options.DelHost already exists, skip"
fi

# NewClient: 传递 delHost (仅在未注入时)
if ! grep -q 'delHost:.*options\.DelHost' "$CLIENT_GO"; then
    sed -i '/^\t\theaders:.*options\.Headers,$/a\			delHost:    options.DelHost,' "$CLIENT_GO"
    echo "  + NewClient delHost assignment added"
else
    echo "  ~ NewClient delHost assignment already exists, skip"
fi

echo "  + Struct fields & assignments done"

# --- Step 2: 用 Python 替换 request 块为 raw TCP ---
# 修复: 使用 c.headers 替代 options.Headers，自建 request 用于 ReadResponse
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
    if 'request := &http.Request{' in line and not replaced:
        j = i
        brace_depth = 0
        request_write_line = -1
        while j < len(lines):
            brace_depth += lines[j].count('{') - lines[j].count('}')
            if brace_depth <= 0 and j > i:
                if 'request.Write(conn)' in lines[j]:
                    request_write_line = j
                    break
                if j + 1 < len(lines) and 'request.Write(conn)' in lines[j + 1]:
                    request_write_line = j + 1
                    break
            j += 1
        if request_write_line >= 0:
            indent = '\t\t\t'
            # Build raw TCP CONNECT + minimal request for ReadResponse
            new_lines.append(indent + '// === KunBox raw TCP CONNECT ===\n')
            new_lines.append(indent + '// Bypass Go http.Request.Write() normalization for TPBox\n')
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
            new_lines.append(indent + 'skipHeaders := map[string]bool{\n')
            new_lines.append(indent + '\t\"user-agent\":       true,\n')
            new_lines.append(indent + '\t\"proxy-connection\": true,\n')
            new_lines.append(indent + '\t\"host\":             true,\n')
            new_lines.append(indent + '}\n')
            new_lines.append(indent + 'if c.headers != nil {\n')
            new_lines.append(indent + '\tfor key, values := range c.headers {\n')
            new_lines.append(indent + '\t\tif skipHeaders[strings.ToLower(key)] {\n')
            new_lines.append(indent + '\t\t\tcontinue\n')
            new_lines.append(indent + '\t\t}\n')
            new_lines.append(indent + '\t\tfor _, value := range values {\n')
            new_lines.append(indent + '\t\t\traw += fmt.Sprintf(\"%s: %s\\r\\n\", key, value)\n')
            new_lines.append(indent + '\t\t}\n')
            new_lines.append(indent + '\t}\n')
            new_lines.append(indent + '}\n')
            new_lines.append(indent + '\n')
            new_lines.append(indent + 'if c.username != \"\" {\n')
            new_lines.append(indent + '\tauth := c.username + \":\" + c.password\n')
            new_lines.append(indent + '\traw += fmt.Sprintf(\"Proxy-Authorization: Basic %s\\r\\n\", base64.StdEncoding.EncodeToString([]byte(auth)))\n')
            new_lines.append(indent + '}\n')
            new_lines.append(indent + '\n')
            new_lines.append(indent + 'raw += \"\\r\\n\"\n')
            new_lines.append(indent + '\n')
            new_lines.append(indent + '_, err = conn.Write([]byte(raw))\n')
            new_lines.append(indent + 'if err != nil {\n')
            new_lines.append(indent + '\tconn.Close()\n')
            new_lines.append(indent + '\treturn nil, err\n')
            new_lines.append(indent + '}\n')
            new_lines.append(indent + '\n')
            new_lines.append(indent + '// Minimal request for http.ReadResponse\n')
            new_lines.append(indent + 'request := (&http.Request{Method: http.MethodConnect}).WithURL(&url.URL{Host: destination.String()})\n')
            i = request_write_line + 1
            replaced = True
            continue
    new_lines.append(line)
    i += 1

with open(target, 'w') as f:
    f.writelines(new_lines)
print('OK: Replaced request block with raw TCP CONNECT' if replaced else 'ERROR: request block not found')
sys.exit(0 if replaced else 1)
" "$CLIENT_GO"

# --- Step 3: 添加缺失的 imports ---
python3 -c "
import sys
target = sys.argv[1]
with open(target, 'r') as f:
    content = f.read()
needed = {'fmt': '\"fmt\"', 'strings': '\"strings\"'}
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
" "$CLIENT_GO"

# --- 验证 ---
echo ""
echo "=== Verification ==="
grep -n 'CONNECT.*HTTP/1.1\|delHost\|c\.path\|skipHeaders\|KunBox.*raw\|c\.headers' "$CLIENT_GO" | head -20
echo ""
echo "=== Duplicate check ==="
DUPES=$(grep -c 'delHost\|path.*string' "$CLIENT_GO" || true)
echo "delHost/path occurrences: $DUPES (expect ~4)"
echo ""
echo "=== Patch 03 done ==="
