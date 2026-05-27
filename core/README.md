# KunBox Go Core Patches

本目录包含 KunBox 对 sing-box Go 核心的所有定制改动。

## 文件说明

| 文件 | 作用 | 目标文件 |
|------|------|----------|
| `kunbox_custom.go` | KunBox 自定义 libbox 扩展方法 | `experimental/libbox/kunbox_custom.go` |
| `patches/01-delhost-option.sh` | HTTP 出站选项添加 DelHost 字段 | `option/simple.go` |
| `patches/02-delhost-outbound.sh` | HTTP 出站传递 DelHost 到客户端 | `protocol/http/outbound.go` |
| `patches/03-client-delhost.sh` | sing HTTP 客户端支持 DelHost 逻辑 | `protocol/http/client.go`（sing 依赖） |
| `build.sh` | 本地编译 libbox.aar | - |

## 上游版本

当前基于 sing-box **v1.13.11**，sing **v0.8.9**。

## 如何查看改动

```bash
# 查看所有 Go 核心相关提交
git log --oneline -- core/

# 查看某个 patch 的具体内容
cat core/patches/01-delhost-option.sh

# 查看 kunbox_custom.go 的变更历史
git log --oneline -- core/kunbox_custom.go
```

## 如何更新上游版本

1. 修改 `build.sh` 中的 `SING_BOX_TAG` 变量
2. 确认 patch 是否需要适配新版本的代码结构
3. 运行 `./core/build.sh` 本地测试编译
4. 提交到仓库
