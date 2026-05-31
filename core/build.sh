#!/bin/bash
# KunBox libbox.aar 本地编译脚本
#
# 用法: ./core/build.sh [sing-box-tag]
# 示例: ./core/build.sh v1.13.11
#
# 前置要求:
#   - Go 1.24+
#   - Android SDK + NDK 29
#   - gomobile/gobind (sagernet fork): go install github.com/sagernet/gomobile/cmd/gomobile@v0.1.12
#
# 编译产物: app/libs/libbox.aar

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

SING_BOX_TAG="${1:-v1.13.11}"
WORK_DIR="$PROJECT_ROOT/build-sing-box"
UPSTREAM_DIR="$WORK_DIR/upstream-sing-box"
LOCAL_SING_DIR="$WORK_DIR/local-sing"

echo "========================================="
echo "KunBox libbox.aar Build"
echo "sing-box tag: $SING_BOX_TAG"
echo "========================================="

# --- Step 1: Clone sing-box upstream ---
if [ -d "$UPSTREAM_DIR" ]; then
    echo "Upstream dir exists, removing..."
    rm -rf "$UPSTREAM_DIR"
fi
if [ -d "$LOCAL_SING_DIR" ]; then
    rm -rf "$LOCAL_SING_DIR"
fi

echo "=== Cloning sing-box $SING_BOX_TAG ==="
git clone --branch "$SING_BOX_TAG" --depth 1 \
    https://github.com/SagerNet/sing-box.git "$UPSTREAM_DIR"

# --- Step 2: Inject KunBox custom libbox methods ---
echo "=== Injecting kunbox_custom.go ==="
cp "$SCRIPT_DIR/kunbox_custom.go" "$UPSTREAM_DIR/experimental/libbox/kunbox_custom.go"
echo "OK: kunbox_custom.go injected"

# --- Step 3: Apply option + outbound patches (sing-box upstream) ---
# Patch 01: option/simple.go — DelHost + Path fields
# Patch 02: protocol/http/outbound.go — pass DelHost + Path to client
echo "=== Applying upstream patches (01-options, 02-outbound) ==="
bash "$SCRIPT_DIR/patches/01-options.sh" "$UPSTREAM_DIR"
bash "$SCRIPT_DIR/patches/02-outbound.sh" "$UPSTREAM_DIR"
echo "OK: upstream patches applied"

# --- Step 4: Patch sing dependency (client.go) ---
echo "=== Patching sing dependency ==="
cd "$UPSTREAM_DIR"

SING_VERSION=$(grep 'github.com/sagernet/sing ' go.mod | awk '{print $2}')
echo "sing version: $SING_VERSION"

go mod download "github.com/sagernet/sing@${SING_VERSION}"
SING_CACHE="$(go env GOMODCACHE)/github.com/sagernet/sing@${SING_VERSION}"

cp -r "$SING_CACHE" "$LOCAL_SING_DIR"
chmod -R u+w "$LOCAL_SING_DIR"

CLIENT_GO="$LOCAL_SING_DIR/protocol/http/client.go"

# Patch 03: sing protocol/http/client.go — DelHost + raw TCP CONNECT
echo "--- Applying patch 03 (client: delhost + raw TCP CONNECT) ---"
bash "$SCRIPT_DIR/patches/03-client.sh" "$CLIENT_GO"

# Patch 05/06/07: HttpFirst (HTTP preface) — option + outbound + client
echo "--- Applying patch 05 (http_first option) ---"
bash "$SCRIPT_DIR/patches/05-httpfirst-option.sh" "$UPSTREAM_DIR"
echo "--- Applying patch 06 (http_first outbound) ---"
bash "$SCRIPT_DIR/patches/06-httpfirst-outbound.sh" "$UPSTREAM_DIR"
echo "--- Applying patch 07 (http_first client + write order) ---"
bash "$SCRIPT_DIR/patches/07-httpfirst-client.sh" "$CLIENT_GO"

# Patch 08/09/10: HttpsFirst + HttpDel + HttpsDel — option + outbound + client
echo "--- Applying patch 08 (https_first + http_del + https_del option) ---"
bash "$SCRIPT_DIR/patches/08-httpsfirst-option.sh" "$UPSTREAM_DIR"
echo "--- Applying patch 09 (https_first outbound) ---"
bash "$SCRIPT_DIR/patches/09-httpsfirst-outbound.sh" "$UPSTREAM_DIR"
echo "--- Applying patch 10 (https_first + del client) ---"
bash "$SCRIPT_DIR/patches/10-httpsfirst-client.sh" "$CLIENT_GO"

echo "--- Applying patch 11 (debug logging) ---"
bash "$SCRIPT_DIR/patches/11-debug-logging.sh" "$CLIENT_GO"

# Patch 12/13/14: RemovePort + Host + DelHost 修复 + 宽松响应解析
echo "--- Applying patch 12 (remove_port + host option) ---"
bash "$SCRIPT_DIR/patches/12-removeport-option.sh" "$UPSTREAM_DIR"
echo "--- Applying patch 13 (remove_port outbound) ---"
bash "$SCRIPT_DIR/patches/13-removeport-outbound.sh" "$UPSTREAM_DIR"
echo "--- Applying patch 14 (remove_port + host + delhost fix + lenient response) ---"
bash "$SCRIPT_DIR/patches/14-removeport-client.sh" "$CLIENT_GO"

# Add replace directive
echo "" >> go.mod
echo "replace github.com/sagernet/sing => $LOCAL_SING_DIR" >> go.mod
echo "OK: sing dependency patched"

# --- Step 5: Add gomobile dependencies ---
echo "=== Adding gomobile dependencies ==="
go get -tool github.com/sagernet/gomobile/cmd/gobind
go get github.com/sagernet/gomobile

# --- Step 6: Build libbox.aar ---
echo "=== Building libbox.aar ==="
gomobile bind -v -androidapi 24 -javapkg=io.nekohasekai \
    -tags "with_clash_api,with_gvisor" \
    -target android -libname=box \
    -trimpath -ldflags='-s -w -checklinkname=0' \
    -o "$PROJECT_ROOT/app/libs/libbox.aar" \
    ./experimental/libbox

cd "$PROJECT_ROOT"

# --- Step 7: Verify ---
AAR="$PROJECT_ROOT/app/libs/libbox.aar"
if [ ! -f "$AAR" ]; then
    echo "ERROR: libbox.aar not found!"
    exit 1
fi

SIZE=$(stat -c%s "$AAR" 2>/dev/null || stat -f%z "$AAR")
MB=$((SIZE / 1048576))
echo "libbox.aar size: $SIZE bytes (${MB} MB)"

if [ "$SIZE" -lt 5000000 ]; then
    echo "ERROR: libbox.aar too small (< 5MB), build likely failed"
    exit 1
fi

echo "========================================="
echo "✅ Build complete: app/libs/libbox.aar (${MB} MB)"
echo "========================================="
echo ""
echo "Applied patches:"
echo "  01-options.sh           → option/simple.go: DelHost + Path fields"
echo "  02-outbound.sh          → outbound.go: pass DelHost + Path"
echo "  03-client.sh            → client.go: DelHost + raw TCP CONNECT"
echo "  05-httpfirst-option.sh  → option/simple.go: HttpFirst field"
echo "  06-httpfirst-outbound.sh→ outbound.go: pass HttpFirst"
echo "  07-httpfirst-client.sh  → client.go: http_first write + flush order"
echo "  08-httpsfirst-option.sh → option/simple.go: HttpsFirst + HttpDel + HttpsDel"
echo "  09-httpsfirst-outbound.sh→ outbound.go: pass HttpsFirst + HttpDel + HttpsDel"
echo "  10-httpsfirst-client.sh → client.go: port-aware CONNECT + dynamic del"
echo "  11-debug-logging.sh     → client.go: debug logging"
echo "  12-removeport-option.sh → option/simple.go: RemovePort + Host fields"
echo "  13-removeport-outbound.sh→ outbound.go: pass RemovePort + Host"
echo "  14-removeport-client.sh → client.go: delHost fix + removePort + host + lenient response"
