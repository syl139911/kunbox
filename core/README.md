# KunBox Go Core Patches

本目录包含 KunBox 对 sing-box Go 核心的所有定制改动。

## 文件说明

| 文件 | 作用 | 目标文件 |
|------|------|----------|
| `kunbox_custom.go` | KunBox 自定义 libbox 扩展方法（连接计数、重置、网络恢复） | `experimental/libbox/kunbox_custom.go` |
| `build.sh` | 本地一键编译 libbox.aar（自动 clone + patch + build） | — |

## 补丁列表

所有补丁位于 `patches/` 目录，按执行顺序编号：

| 补丁 | 目标文件 | 改动内容 |
|------|----------|----------|
| `01-options.sh` | `option/simple.go` | HTTPOutboundOptions 新增 `DelHost` + `Path` 字段 |
| `02-outbound.sh` | `protocol/http/outbound.go` | 传递 `DelHost` / `Path` 到 sing HTTP Client |
| `03-client.sh` | sing `protocol/http/client.go` | **核心**：raw TCP CONNECT 替代 Go `http.Request.Write()`，实现 TPBox 免流 |
| `05-httpfirst-option.sh` | `option/simple.go` | 新增 `HttpFirst` 字段（HTTP preface） |
| `06-httpfirst-outbound.sh` | `protocol/http/outbound.go` | 传递 `HttpFirst` 到 Client |
| `07-httpfirst-client.sh` | sing `protocol/http/client.go` | http_first 写入逻辑 + Host header 修复 |
| `08-httpsfirst-option.sh` | `option/simple.go` | 新增 `HttpsFirst` / `HttpDel` / `HttpsDel` 字段 |
| `09-httpsfirst-outbound.sh` | `protocol/http/outbound.go` | 传递 `HttpsFirst` / `HttpDel` / `HttpsDel` |
| `10-httpsfirst-client.sh` | sing `protocol/http/client.go` | 端口感知（443=HTTPS）+ 动态 del headers |

> 注：编号 04 已合并到 01-03 和 08-10 中，故跳过。

## TPBox HTTP 执行流

```
conn established
  │
  ├─ conn.Write(firstContent)    ← http_first 或 https_first（按目标端口选择）
  │
  ├─ conn.Write(raw CONNECT)     ← "CONNECT host:443/path HTTP/1.1\r\n..."
  │                                 DelHost 模式下隐藏真实 host
  │                                 HttpDel/HttpsDel 动态删除指定 header
  │
  ├─ http.ReadResponse()
  │
  └─ tunnel established
```

## 配置字段一览

```json
{
  "type": "http",
  "server": "x.x.x.x",
  "server_port": 443,
  "del_host": true,
  "path": "/@dingtalk",
  "http_first": "GET / HTTP/1.1\r\nHost: dingtalk.com\r\n\r\n",
  "https_first": "GET / HTTP/1.1\r\nHost: gw.example.com\r\n\r\n",
  "http_del": ["user-agent"],
  "https_del": ["proxy-connection"],
  "tls": {
    "enabled": true,
    "server_name": "dingtalk.com"
  }
}
```

| 字段 | 类型 | 作用 |
|------|------|------|
| `del_host` | bool | 隐藏真实 host，CONNECT 行只用 path |
| `path` | string | 拼接到 CONNECT 目标后面 |
| `http_first` | string | HTTP 目标（非443端口）CONNECT 之前发送的 preface |
| `https_first` | string | HTTPS 目标（443端口）CONNECT 之前发送的 preface |
| `http_del` | []string | HTTP 请求中删除的 header 列表 |
| `https_del` | []string | HTTPS CONNECT 中删除的 header 列表 |

## 上游版本

当前基于 sing-box **v1.13.11**，sing **v0.8.9**。

## 本地编译

```bash
# 前置要求：Go 1.24+, Android SDK + NDK 29, gomobile (sagernet fork)
./core/build.sh           # 默认使用 v1.13.11
./core/build.sh v1.13.12  # 指定上游 tag
```

编译产物：`app/libs/libbox.aar`
