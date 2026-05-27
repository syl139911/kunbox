# Patch 04: Raw TCP CONNECT (TPBox style)

## 问题根因

Go 标准库 `http.Request.Write()` 会自动 normalize HTTP：

```
你想要的: CONNECT google.com:443@dingtalk.com HTTP/1.1
实际发出: CONNECT google.com:443 HTTP/1.1          ← Go 修正了 URL
          Host: google.com:443                       ← Go 自动补了 Host
```

运营商看的是 CONNECT 第一行，不是 Host。所以 `del_host` 生效也没用。

## 解决方案

**绕过 Go 标准库，直接 `conn.Write([]byte(raw))`**

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

对比官方 sing-box:
  CONNECT www.google.com:443 HTTP/1.1\r\n
  Host: www.google.com:443\r\n
  \r\n
```

## 原理

| 配置 | CONNECT 行 | Host 行 | 效果 |
|------|-----------|---------|------|
| 无改动 | `CONNECT host:443 HTTP/1.1` | `Host: host:443` | 标准，运营商识别 |
| del_host only | `CONNECT host:443 HTTP/1.1` | _(删除)_ | CONNECT 行仍暴露 |
| **path + del_host** | **`CONNECT host:443@dingtalk.com HTTP/1.1`** | _(删除)_ | **运营商误判为钉钉** |

## 兼容性

- path 为空时 → 行为与官方 sing-box 完全一致（raw CONNECT 无额外后缀）
- del_host 为 false 时 → 正常发送 Host 头
- 不影响 SOCKS 出站
- 不影响 HTTP 非 CONNECT 请求
