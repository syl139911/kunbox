# HTTP Proxy `del_host` 补丁

## 问题
KunBox 使用 HTTP 代理节点时，当配置了 `del_host=true` 和 `path` 参数，
Go 核心的 `net/http` 标准库仍会自动补 `Host` 头，导致机场 squid 返回 403。

## 修改文件

### 1. `option/simple.go`
- `HTTPOutboundOptions` 新增 `DelHost bool` 字段

### 2. `protocol/http/outbound.go`
- 将 `options.DelHost` 传递给 `sHTTP.Options`

### 3. `sing-protocol/http/client.go`（sing 库）
- `Options` 新增 `DelHost` 字段
- `del_host=true` 时手写 CONNECT 包，不走 `request.Write()`
- 发出的包格式与 TPBox 一致：
  ```
  CONNECT <destination><path> HTTP/1.1\r\n
  Proxy-Connection: Keep-Alive\r\n
  \r\n
  ```

## 使用方法
将以下文件替换到对应位置后重新编译 `libbox.aar`：
- `option/simple.go` → `sing-box/option/simple.go`
- `protocol/http/outbound.go` → `sing-box/protocol/http/outbound.go`
- `sing-protocol/http/client.go` → `sing/protocol/http/client.go`
