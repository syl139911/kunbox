#!/bin/bash
# Patch 03: sing protocol/http/client.go — DelHost + Path + Raw TCP CONNECT
#
# 合并自: 03-client-delhost.sh + 04-connect-raw.sh
# 核心改动：用 raw TCP write 替换 Go http.Request.Write()，实现 TPBox 免流
# 目标文件: sing 库的 protocol/http/client.go (通过 go mod download 缓存)

set -e
CLIENT_GO="$1"
[ -f "$CLIENT_GO" ] || { echo "ERROR: $CLIENT_GO not found"; exit 1; }

echo "=== Patch 03: client.go - DelHost + Raw TCP CONNECT ==="
echo "Target: $CLIENT_GO"

# --- Step 1: 结构体字段 ---
# Client: delHost, path
sed -i '/^\thost.*string$/a\	delHost    bool' "$CLIENT_GO" 2>/dev/null || true
sed -i '/delHost.*bool/a\	path       string' "$CLIENT_GO" 2>/dev/null || true

# Options: DelHost, Path
sed -i '/^\tHeaders.*http\.Header$/a\	DelHost  bool' "$CLIENT_GO" 2>/dev/null || true
sed -i '/DelHost.*bool/a\	Path     string' "$CLIENT_GO" 2>/dev/null || true

# NewClient: 传递 delHost, path
sed -i '/^\theaders:.*options\.Headers,$/a\			delHost:    options.DelHost,' "$CLIENT_GO" 2>/dev/null || true
sed -i '/delHost:.*options.DelHost/a\			path:       options.Path,' "$CLIENT_GO" 2>/dev/null || true

echo "  + Struct fields & assignments added"

# --- Step 2: 用 Python 替换 request 块为 raw TCP ---
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
            new_lines.append(indent + '// === TPBox raw TCP CONNECT ===\n')
            new_lines.append(indent + '// Bypass Go http.Request.Write() normalization\n')
            new_lines.append(indent + 'target := destination.String()\n')
            new_lines.append(indent + 'if c.path != \"\" {\n')
            new_lines.append(indent + '\ttarget += c.path\n')
            new_lines.append(indent + '}\n')
            new_lines.append(indent + '\n')
            new_lines.append(indent + 'raw := fmt.Sprintf(\"CONNECT %s HTTP/1.1\\x0d\\x0a\", target)\n')
            new_lines.append(indent + '\n')
            new_lines.append(indent + 'if !c.delHost {\n')
            new_lines.append(indent + '\thostHeader := destination.String()\n')
            new_lines.append(indent + '\tif c.host != \"\" && c.host != destination.Fqdn {\n')
            new_lines.append(indent + '\t\thostHeader = c.host\n')
            new_lines.append(indent + '\t}\n')
            new_lines.append(indent + '\traw += fmt.Sprintf(\"Host: %s\\x0d\\x0a\", hostHeader)\n')
            new_lines.append(indent + '}\n')
            new_lines.append(indent + '\n')
            new_lines.append(indent + 'skipHeaders := map[string]bool{\n')
            new_lines.append(indent + '\t\"user-agent\":       true,\n')
            new_lines.append(indent + '\t\"proxy-connection\": true,\n')
            new_lines.append(indent + '\t\"host\":             true,\n')
            new_lines.append(indent + '}\n')
            new_lines.append(indent + 'if options.Headers != nil {\n')
            new_lines.append(indent + '\tfor key, values := range options.Headers {\n')
            new_lines.append(indent + '\t\tif skipHeaders[strings.ToLower(key)] {\n')
            new_lines.append(indent + '\t\t\tcontinue\n')
            new_lines.append(indent + '\t\t}\n')
            new_lines.append(indent + '\t\tfor _, value := range values {\n')
            new_lines.append(indent + '\t\t\traw += fmt.Sprintf(\"%s: %s\\x0d\\x0a\", key, value)\n')
            new_lines.append(indent + '\t\t}\n')
            new_lines.append(indent + '\t}\n')
            new_lines.append(indent + '}\n')
            new_lines.append(indent + '\n')
            new_lines.append(indent + 'raw += \"\\x0d\\x0a\"\n')
            new_lines.append(indent + '\n')
            new_lines.append(indent + 'log.Warn(\"[KunBox] raw CONNECT:\", raw)\n')
            new_lines.append(indent + '\n')
            new_lines.append(indent + '_, err = conn.Write([]byte(raw))\n')
            new_lines.append(indent + 'if err != nil {\n')
            new_lines.append(indent + '\tconn.Close()\n')
            new_lines.append(indent + '\treturn nil, err\n')
            new_lines.append(indent + '}\n')
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
needed = {'fmt': '\"fmt\"', 'log': '\"log\"', 'strings': '\"strings\"'}
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
grep -n 'CONNECT.*HTTP/1.1\|x0d.*x0a\|delHost\|c\.path\|skipHeaders\|KunBox.*raw' "$CLIENT_GO" | head -20
echo ""
echo "=== Patch 03 done ==="
