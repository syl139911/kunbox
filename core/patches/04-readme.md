# Patch 04: Raw TCP CONNECT (TPBox style)

## 问题根因

Go 标准库 `http.Request.Write()` 会自动 normalize HTTP：

```
你想要的: CONNECT google.com:443@dingtalk.com HTTP/1.1
实际发出: CONNECT google.com:443 HTTP/1.1          ← Go 修正了 URL
          Host: google.com:443                       ← Go 自动补了 Host
          User-Agent: Go-http-client/1.1             ← Go 自动加的
```

运营商看的是 CONNECT 第一行，不是 Host。所以 `del_host` 生效也没用。

## 解决方案

**绕过 Go 标准库，直接 `conn.Write([]byte(raw))`**

## 关键改动

1. **Raw TCP CONNECT** — 不走 `req.Write()`，直接拼接并写入
2. **Debug log** — `log.Println("[KunBox] raw CONNECT:", raw)` 输出实际发包内容
3. **过滤 Go 自动 Header** — 跳过 `User-Agent`, `Proxy-Connection`, `Host`（已单独处理）
4. **Path 拼接** — `target = destination.String() + c.path`

## 文件结构

```
core/patches/
├── 04a-option-path.sh    # option/simple.go: 加 Path 字段
├── 04b-outbound-path.sh  # protocol/http/outbound.go: 传递 Path
└── 04-connect-raw.sh     # protocol/http/client.go: 替换 CONNECT 为 raw TCP
```

## 应用顺序

```bash
# 先应用 01-03 (DelHost 基础)
bash core/patches/01-delhost-option.sh .
bash core/patches/02-delhost-outbound.sh .
bash core/patches/03-client-delhost.sh .

# 再应用 04 (Path + Raw CONNECT)
bash core/patches/04a-option-path.sh .
bash core/patches/04b-outbound-path.sh .
bash core/patches/04-connect-raw.sh ./protocol/http/client.go
```

## 配置格式

```json
{
  "type": "http",
  "server": "1.1.1.1",
  "server_port": 443,
  "path": "@dingtalk.com",
  "del_host": true
}
```

## 最终效果

```
配置:  "path": "@dingtalk.com", "del_host": true

TCP 实际发包:
  CONNECT www.google.com:443@dingtalk.com HTTP/1.1\r\n
  \r\n
  (仅此一行，无 Host、无 User-Agent、无 Proxy-Connection)

对比官方 sing-box:
  CONNECT www.google.com:443 HTTP/1.1\r\n
  Host: www.google.com:443\r\n
  User-Agent: Go-http-client/1.1\r\n
  \r\n
```

## Debug

构建后 logcat 过滤 `KunBox` 可以看到实际发包：

```
adb logcat | grep KunBox
```

输出示例：
```
[KunBox] raw CONNECT: CONNECT www.google.com:443@dingtalk.com HTTP/1.1
```

## path 拼接模式

当前实现：简单拼接 `host:port + path`

| 配置 | 结果 |
|------|------|
| `"path": "@dingtalk.com"` | `CONNECT host:443@dingtalk.com HTTP/1.1` |
| `"path": "/@dingtalk.com"` | `CONNECT host:443/@dingtalk.com HTTP/1.1` |
| `"path": ""` | `CONNECT host:443 HTTP/1.1`（标准格式） |

> 如果 TPBox 用的是 `/@dingtalk.com` 而不是 `@dingtalk.com`，
> 只需要改配置里的 path 即可，代码不用动。

## 被过滤的 Go 自动 Header

| Header | 原因 |
|--------|------|
| `User-Agent: Go-http-client/1.1` | 暴露 Go 客户端身份 |
| `Proxy-Connection` | 免流场景不需要 |
| `Host` | 已由 delHost 逻辑单独控制 |

## 兼容性

- path 为空时 → 行为与官方 sing-box 完全一致
- del_host 为 false 时 → 正常发送 Host 头
- 不影响 SOCKS 出站
- 不影响 HTTP 非 CONNECT 请求
