# KunBox Go Core Patches

## 功能补丁

### 1. del_host 功能 (补丁 01-03)
- **option/simple.go** — 给 `HTTPOutboundOptions` 加 `del_host` 字段
- **protocol/http/outbound.go** — 传递 `DelHost` 给 HTTP Client
- **sagernet/sing/protocol/http/client.go** — 用 Opaque URL 阻止 Host 自动填充

## 构建方式
使用 `.github/workflows/go-build.yml` 工作流自动构建，所有补丁在 CI 中自动应用。
