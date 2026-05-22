# KunBox del_host 编译指南

## 前提条件

| 工具 | 版本要求 |
|---|---|
| Go | 1.24+ |
| Android NDK | r29+ |
| Java | 17+ |
| Android SDK | API 24+ |
| gomobile | 最新版 |

## 步骤

### 1. 克隆 KunBox 仓库

```bash
git clone https://github.com/syl139911/kunbox.git
cd kunbox
```

### 2. 克隆 sing-box 源码

```bash
git clone --branch v1.13.11 --depth 1 https://github.com/SagerNet/sing-box.git upstream-sing-box
```

### 3. 安装 gomobile

```bash
go install golang.org/x/mobile/cmd/gomobile@latest
go install golang.org/x/mobile/cmd/gobind@latest
gomobile init
```

### 4. 应用 Go 补丁

```bash
cd upstream-sing-box

# 补丁 1: option/simple.go — 给 HTTPOutboundOptions 加 DelHost 字段
cat >> option/simple.go << 'PATCH'
// --- KunBox patch: del_host support ---
PATCH

# 手动修改 option/simple.go，在 HTTPOutboundOptions 结构体末尾加一行：
#   DelHost bool `json:"del_host,omitempty"`
# 改完后：
# type HTTPOutboundOptions struct {
#     DialerOptions
#     ServerOptions
#     Username string `json:"username,omitempty"`
#     Password string `json:"password,omitempty"`
#     OutboundTLSOptionsContainer
#     Path    string               `json:"path,omitempty"`
#     Headers badoption.HTTPHeader `json:"headers,omitempty"`
#     DelHost bool                 `json:"del_host,omitempty"`  ← 加这行
# }

# 补丁 2: protocol/http/outbound.go — 传递 DelHost 给 HTTP Client
# 找到 sHTTP.NewClient(sHTTP.Options{...}) 部分，加一行 DelHost: options.DelHost
# 改完后：
# client: sHTTP.NewClient(sHTTP.Options{
#     Dialer:   detour,
#     Server:   options.ServerOptions.Build(),
#     Username: options.Username,
#     Password: options.Password,
#     Path:     options.Path,
#     Headers:  options.Headers.Build(),
#     DelHost:  options.DelHost,  ← 加这行
# }),

# 补丁 3: Go module 依赖中的 sing 库
# 找到你的 Go module cache 中的 sing 库：
#   $(go env GOMODCACHE)/github.com/sagernet/sing@vXXX/protocol/http/client.go
# 或者用 replace 指令指向本地修改版

# 修改 protocol/http/client.go：

# 3a. Client 结构体加字段：
#     delHost    bool

# 3b. Options 结构体加字段：
#     DelHost  bool

# 3c. NewClient 函数加赋值：
#     delHost:    options.DelHost,

# 3d. DialContext 函数，把：
#     if c.host != "" && c.host != destination.Fqdn {
# 改成：
#     if c.delHost {
#         request.URL = &url.URL{Opaque: destination.String()}
#         request.Host = ""
#     } else if c.host != "" && c.host != destination.Fqdn {

cd ..
```

### 5. 编译 libbox.aar

```bash
cd upstream-sing-box

# 设置环境
export ANDROID_HOME=$HOME/Android/Sdk
export ANDROID_NDK_HOME=$ANDROID_HOME/ndk/29.xxx  # 你的 NDK 版本

# 编译
gomobile bind -v -androidapi 24 -javapkg=com.github.sagernet \
  -trimpath -ldflags='-s -w' \
  -o ../app/libs/libbox.aar \
  ./experimental/libbox

cd ..
```

### 6. 验证新 libbox.aar

```bash
# 检查文件大小（应该 70MB+）
ls -lh app/libs/libbox.aar
```

### 7. 构建 APK

```bash
./gradlew assembleDebug
# 或
./gradlew assembleRelease
```

---

## 最简单的方式

如果你不想手动改 Go 代码，可以用 `sed` 一键打补丁：

```bash
cd upstream-sing-box

# 补丁 1
sed -i '/Headers badoption.HTTPHeader/a\\tDelHost bool `json:"del_host,omitempty"`' option/simple.go

# 补丁 2
sed -i '/Headers:  options.Headers.Build(),/a\\t\t\tDelHost:  options.DelHost,' protocol/http/outbound.go

# 补丁 3 — 需要找到 sing 库的实际路径
SING_PATH=$(go env GOMODCACHE)/github.com/sagernet/sing*/protocol/http/client.go
# 如果用了 go mod replace，路径可能不同

# 3a: 结构体加字段
sed -i '/headers    http.Header/a\\tdelHost    bool' $SING_PATH
# 3b: Options 加字段
sed -i '/Headers  http.Header/a\\tDelHost  bool' $SING_PATH
# 3c: NewClient 加赋值
sed -i '/headers:    options.Headers,/a\\t\tdelHost:    options.DelHost,' $SING_PATH
# 3d: DialContext 加 delHost 判断
sed -i 's/if c.host != "" && c.host != destination.Fqdn {/if c.delHost {\n\t\trequest.URL = \&url.URL{Opaque: destination.String()}\n\t\trequest.Host = ""\n\t} else if c.host != "" \&\& c.host != destination.Fqdn {/' $SING_PATH

cd ..
```

然后直接编译 libbox.aar 即可。
