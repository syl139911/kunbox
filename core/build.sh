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

# --- Step 3: Apply DelHost patches to sing-box upstream ---
# Patch 01: option/simple.go - add DelHost to HTTPOutboundOptions
# Patch 02: protocol/http/outbound.go - pass DelHost to HTTP client
echo "=== Applying DelHost patches (01, 02) ==="
bash "$SCRIPT_DIR/patches/01-delhost-option.sh" "$UPSTREAM_DIR"
bash "$SCRIPT_DIR/patches/02-delhost-outbound.sh" "$UPSTREAM_DIR"
echo "OK: DelHost patches applied"

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

# Patch 03: sing protocol/http/client.go - add DelHost field + logic
echo "--- Applying patch 03 (client DelHost) ---"
bash "$SCRIPT_DIR/patches/03-client-delhost.sh" "$CLIENT_GO"

# Patch 04: sing protocol/http/client.go - raw TCP CONNECT (TPBox style)
echo "--- Applying patch 04 (raw TCP CONNECT) ---"
bash "$SCRIPT_DIR/patches/04-connect-raw.sh" "$CLIENT_GO"

# Add replace directive
echo "" >> go.mod
echo "replace github.com/sagernet/sing => $LOCAL_SING_DIR" >> go.mod
echo "OK: sing dependency patched (03-delhost + 04-raw-connect)"

# --- Step 5: Apply Path patches to sing-box upstream ---
# Patch 04a: option/simple.go - add Path field
# Patch 04b: protocol/http/outbound.go - pass Path to client
echo "=== Applying Path patches (04a, 04b) ==="
bash "$SCRIPT_DIR/patches/04a-option-path.sh" "$UPSTREAM_DIR"
bash "$SCRIPT_DIR/patches/04b-outbound-path.sh" "$UPSTREAM_DIR"
echo "OK: Path patches applied"

# --- Step 6: Add gomobile dependencies ---
echo "=== Adding gomobile dependencies ==="
go get -tool github.com/sagernet/gomobile/cmd/gobind
go get github.com/sagernet/gomobile

# --- Step 7: Build libbox.aar ---
echo "=== Building libbox.aar ==="
gomobile bind -v -androidapi 24 -javapkg=io.nekohasekai \
    -tags "with_clash_api,with_gvisor" \
    -target android -libname=box \
    -trimpath -ldflags='-s -w -checklinkname=0' \
    -o "$PROJECT_ROOT/app/libs/libbox.aar" \
    ./experimental/libbox

cd "$PROJECT_ROOT"

# --- Step 8: Verify ---
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
echo "  01-delhost-option.sh    → option/simple.go: DelHost field"
echo "  02-delhost-outbound.sh  → outbound.go: pass DelHost"
echo "  03-client-delhost.sh    → client.go: DelHost logic"
echo "  04-connect-raw.sh       → client.go: raw TCP CONNECT"
echo "  04a-option-path.sh      → option/simple.go: Path field"
echo "  04b-outbound-path.sh    → outbound.go: pass Path"
