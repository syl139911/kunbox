#!/bin/bash
# Patch 07: sing protocol/http/client.go — HttpFirst + Write Order
#
# 核心改动：在 raw TCP CONNECT 块之前插入 http_first 写入
# 实现 TPBox 的写入顺序：http_first → flush → CONNECT → flush
#
# 目标文件: sing 库的 protocol/http/client.go (通过 go mod download 缓存)

set -e
CLIENT_GO="$1"
[ -f "$CLIENT_GO" ] || { echo "ERROR: $CLIENT_GO not found"; exit 1; }

echo "=== Patch 07: client.go - HttpFirst + Write Order ==="

# --- Step 1: 注入 httpFirst 字段到 Client struct ---
if ! grep -q 'httpFirst' "$CLIENT_GO"; then
    sed -i '/delHost.*bool$/a\	httpFirst  string' "$CLIENT_GO"
    echo "  + Client.httpFirst added"
else
    echo "  ~ Client.httpFirst already exists, skip"
fi

# --- Step 2: 注入 HttpFirst 到 Options struct ---
if ! grep -q 'HttpFirst.*string' "$CLIENT_GO"; then
    sed -i '/DelHost.*bool$/a\	HttpFirst  string' "$CLIENT_GO"
    echo "  + Options.HttpFirst added"
else
    echo "  ~ Options.HttpFirst already exists, skip"
fi

# --- Step 3: 注入赋值到 NewClient ---
if ! grep -q 'httpFirst:.*options\.HttpFirst' "$CLIENT_GO"; then
    sed -i '/delHost:.*options\.DelHost,/a\			httpFirst:    options.HttpFirst,' "$CLIENT_GO"
    echo "  + NewClient httpFirst assignment added"
else
    echo "  ~ NewClient httpFirst assignment already exists, skip"
fi

# --- Step 4: 用 Python 在 raw TCP CONNECT 块前插入 http_first 写入 ---
python3 - "$CLIENT_GO" << 'PYEOF'
import sys
target = sys.argv[1]
with open(target, 'r') as f:
    lines = f.readlines()

new_lines = []
i = 0
inserted = False

while i < len(lines):
    line = lines[i]
    # 在 KunBox raw TCP CONNECT 注释前插入 http_first
    if '// === KunBox raw TCP CONNECT ===' in line and not inserted:
        indent = '\t\t\t'
        new_lines.append(indent + '// === KunBox http_first (HTTP preface) ===\n')
        new_lines.append(indent + 'if c.httpFirst != "" {\n')
        new_lines.append(indent + '\t_, err = conn.Write([]byte(c.httpFirst))\n')
        new_lines.append(indent + '\tif err != nil {\n')
        new_lines.append(indent + '\t\tconn.Close()\n')
        new_lines.append(indent + '\t\treturn nil, err\n')
        new_lines.append(indent + '\t}\n')
        new_lines.append(indent + '\t// Flush http_first before CONNECT\n')
        new_lines.append(indent + '\tif bw, ok := conn.(*bufio.Writer); ok {\n')
        new_lines.append(indent + '\t\tbw.Flush()\n')
        new_lines.append(indent + '\t}\n')
        new_lines.append(indent + '}\n')
        new_lines.append(indent + '\n')
        inserted = True
        print('OK: Inserted http_first block before raw TCP CONNECT')
    new_lines.append(line)
    i += 1

with open(target, 'w') as f:
    f.writelines(new_lines)

if not inserted:
    print('ERROR: KunBox raw TCP CONNECT marker not found')
    sys.exit(1)
PYEOF

# --- Step 5: 确保 bufio import 存在 ---
python3 - "$CLIENT_GO" << 'PYEOF'
import sys
target = sys.argv[1]
with open(target, 'r') as f:
    content = f.read()
if '"bufio"' not in content:
    lines = content.split('\n')
    new_lines = []
    for line in lines:
        new_lines.append(line)
        if line.strip() == 'import (':
            new_lines.append('\t"bufio"')
    with open(target, 'w') as f:
        f.write('\n'.join(new_lines))
    print('OK: Added bufio import')
else:
    print('OK: bufio import present')
PYEOF

# --- 验证 ---
echo ""
echo "=== Verification ==="
grep -n 'httpFirst\|HttpFirst\|KunBox.*http_first\|bufio' "$CLIENT_GO" | head -15
echo ""
echo "=== Patch 07 done ==="
