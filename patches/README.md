# KunBox Go Core Patches

## 功能补丁

### 1. del_host 功能 (补丁 01-03)
- **option/simple.go** — 给 `HTTPOutboundOptions` 加 `del_host` 字段
- **protocol/http/outbound.go** — 传递 `DelHost` 给 HTTP Client
- **sagernet/sing/protocol/http/client.go** — 用 Opaque URL 阻止 Host 自动填充

### 2. http_first 功能 (补丁 04-06)
- **option/simple.go** — 给 `HTTPOutboundOptions` 加 `http_first` 字段
- **protocol/http/outbound.go** — 传递 `HttpFirst` 给 HTTP Client
- **sagernet/sing/protocol/http/client.go** — 强制使用 HTTP/1.1 协议

## http_first 行为说明
当 `http_first=true` 时，HTTP CONNECT 请求将强制使用 HTTP/1.1 协议：
- `request.Proto = "HTTP/1.1"`
- `request.ProtoMajor = 1`
- `request.ProtoMinor = 1`

这在某些 CDN/代理服务器不支持 HTTP/2 或需要 HTTP/1.1 优先时非常有用。

## 构建方式
使用 `.github/workflows/go-build.yml` 工作流自动构建，所有补丁在 CI 中自动应用。
