# KunBox del_host 功能补丁

## 问题
Kotlin 层设置 `Host: ""` 无效，因为 Go 的 `http.Request.Write` 会自动从目标地址填充 Host 头。

## 需要修改的文件

### Go 核心层 (3个文件)

1. **`option/simple.go`** — 给 `HTTPOutboundOptions` 加 `del_host` 字段
2. **`protocol/http/outbound.go`** — 传递 `DelHost` 给 HTTP Client
3. **`sagernet/sing/protocol/http/client.go`** — 用 Opaque URL 阻止 Host 自动填充

### Kotlin 层 (1个文件)

4. **`OutboundFixer.kt`** — 删除无效的 `httpHeaders["Host"] = ""` 逻辑

## 构建流程
1. 应用 Go 补丁到 sing-box 源码
2. 重新编译 libbox.aar
3. 替换 app/libs/libbox.aar
4. 应用 Kotlin 修改
5. 构建 APK
