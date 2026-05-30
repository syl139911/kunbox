# Patched Source (KunBox TPBox HTTP)

这个目录存放的是 **patch 应用后的 Go 源码**，供直接阅读和参考。

不是编译用的——编译由 `../build.sh` 自动 clone + patch 完成。

## 文件对照

| 文件 | 原始来源 | KunBox 改动 |
|------|---------|------------|
| `option/simple.go` | sing-box v1.13.11 | +DelHost +HttpFirst 字段 |
| `protocol/http/outbound.go` | sing-box v1.13.11 | 传递 DelHost/HttpFirst 到 client |
| `protocol/http/client.go` | sing v0.8.9 | **核心**: raw TCP CONNECT + http_first + flush 顺序 |

## TPBox HTTP 执行流

```
conn established
  │
  ├─ conn.Write(httpFirst)        ← "GET / HTTP/1.1\r\nHost: xxx\r\n\r\n"
  ├─ flush (if bufio.Writer)
  │
  ├─ conn.Write(raw CONNECT)      ← "CONNECT host:443/path HTTP/1.1\r\nHost: gw.com\r\n\r\n"
  │
  ├─ http.ReadResponse()
  │
  └─ tunnel established
```

## 配置字段

```json
{
  "type": "http",
  "server": "x.x.x.x",
  "server_port": 443,
  "del_host": true,
  "path": "/@dingtalk",
  "http_first": "GET / HTTP/1.1\r\nHost: dingtalk.com\r\n\r\n",
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
| `http_first` | string | CONNECT 之前发送的 HTTP preface |

## 改动标记

所有 KunBox 改动用以下注释标记，方便搜索：

```
// ========== KunBox 新增 ==========
// ========== KunBox 新增字段 (Patch 01) ==========
// ========== KunBox 新增传递 (Patch 02) ==========
```
