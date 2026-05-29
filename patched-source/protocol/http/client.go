// KunBox patched: sing/protocol/http/client.go
// 基于 sing v0.8.9 (sing-box v1.13.11 依赖)
// 改动:
//   Patch 03: +delHost 字段 + raw TCP CONNECT (替换 Go http.Request.Write)
//   Patch 07: +httpFirst 字段 + http_first 写入
//   Patch 04: +httpsFirst +httpDel +httpsDel — HTTP/HTTPS 分离处理
//
// 原始文件: https://github.com/sagernet/sing/blob/v0.8.9/protocol/http/client.go
//
// === TPBox 执行顺序 ===
//   1. conn.Write(first)    ← preface (http_first 或 https_first，按端口选择)
//   2. conn.Write(raw CONNECT) ← 拼接后的 CONNECT 行 + headers
//   3. http.ReadResponse
//   4. tunnel established

package http

import (
	std_bufio "bufio"
	"context"
	"encoding/base64"
	"fmt"
	"net"
	"net/http"
	"net/url"
	"os"
	"strings"

	"github.com/sagernet/sing/common/buf"
	"github.com/sagernet/sing/common/bufio"
	E "github.com/sagernet/sing/common/exceptions"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
)

var _ N.Dialer = (*Client)(nil)

type Client struct {
	dialer     N.Dialer
	serverAddr M.Socksaddr
	username   string
	password   string
	host       string
	path       string
	headers    http.Header
	// ========== KunBox 新增字段 ==========
	delHost    bool     // Patch 03: TPBox del_host 模式
	httpFirst  string   // Patch 07: HTTP preface 内容
	// ====================================
	// ========== KunBox 新增字段 (Patch 04) ==========
	httpsFirst string   // HTTPS CONNECT 独立 preface
	httpDel    []string // HTTP 删除指定 header
	httpsDel   []string // HTTPS 删除指定 header
	// ====================================
}

type Options struct {
	Dialer   N.Dialer
	Server   M.Socksaddr
	Username string
	Password string
	Path     string
	Headers  http.Header
	// ========== KunBox 新增选项 ==========
	DelHost   bool     // Patch 03
	HttpFirst string   // Patch 07
	// ====================================
	// ========== KunBox 新增选项 (Patch 04) ==========
	HttpsFirst string   // HTTPS CONNECT 独立 preface
	HttpDel    []string // HTTP 删除指定 header
	HttpsDel   []string // HTTPS 删除指定 header
	// ====================================
}

func NewClient(options Options) *Client {
	client := &Client{
		dialer:     options.Dialer,
		serverAddr: options.Server,
		username:   options.Username,
		password:   options.Password,
		path:       options.Path,
		headers:    options.Headers,
		// ========== KunBox 赋值 ==========
		delHost:    options.DelHost,
		httpFirst:  options.HttpFirst,
		// ================================
		// ========== KunBox 赋值 (Patch 04) ==========
		httpsFirst: options.HttpsFirst,
		httpDel:    options.HttpDel,
		httpsDel:   options.HttpsDel,
		// ==========================================
	}
	if options.Dialer == nil {
		client.dialer = N.SystemDialer
	}
	var host string
	if client.headers != nil {
		host = client.headers.Get("Host")
		client.headers.Del("Host")
		client.host = host
	}
	return client
}

func (c *Client) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	network = N.NetworkName(network)
	switch network {
	case N.NetworkTCP:
	case N.NetworkUDP:
		return nil, os.ErrInvalid
	default:
		return nil, E.Extend(N.ErrUnknownNetwork, network)
	}
	var conn net.Conn
	conn, err := c.dialer.DialContext(ctx, N.NetworkTCP, c.serverAddr)
	if err != nil {
		return nil, err
	}

	// ============================================================
	// === KunBox: 以下全部替换原始 request.Write(conn) 逻辑 ===
	// === 实现 TPBox 级 HTTP CONNECT 链路重写               ===
	// === Patch 04: HTTP/HTTPS 分离处理                      ===
	// ============================================================

	// 判断目标是否为 HTTPS
	// 443 端口或用户显式配置了 httpsFirst 的都视为 HTTPS
	isHttps := destination.Port == 443 || c.httpsFirst != ""

	// === Step 1: http_first / https_first (preface) ===
	// HTTP 和 HTTPS 各自独立的 preface，互不 fallback
	// 用户可以只给 HTTP 加 preface 而不给 HTTPS 加，反之亦然
	var firstContent string
	if isHttps {
		firstContent = c.httpsFirst
	} else {
		firstContent = c.httpFirst
	}
	if firstContent != "" {
		// http_first write (debug logging removed for production)
		_, err = conn.Write([]byte(firstContent))
		if err != nil {
			conn.Close()
			return nil, err
		}
		// conn 是原始 TCP 连接，Write 直接进内核 socket buffer，无需 flush
	}

	// === Step 2: 构建 raw TCP CONNECT ===
	// 绕过 Go http.Request.Write() 的标准化，直接拼接原始 HTTP 报文
	// 这是 TPBox 的核心——控制 CONNECT 行的每一个字节

	// 构建 CONNECT 目标
	// 标准: CONNECT host:443 HTTP/1.1
	// TPBox: CONNECT host:443/@dingtalk HTTP/1.1  (带 path)
	// TPBox: CONNECT /@dingtalk HTTP/1.1           (del_host 模式)
	target := destination.String()
	if c.path != "" {
		if !strings.HasPrefix(c.path, "/") {
			target += "/"
		}
		target += c.path
	}

	var raw strings.Builder
	fmt.Fprintf(&raw, "CONNECT %s HTTP/1.1\r\n", target)

	// Host header 构造
	// 标准: Host = 真实目标
	// TPBox: Host = 伪装域名 (c.host)
	// del_host 模式: Host = c.host (伪装域名，必须写，否则代理服务器可能返回 400)
	if c.host != "" {
		fmt.Fprintf(&raw, "Host: %s\r\n", c.host)
	} else if !c.delHost {
		fmt.Fprintf(&raw, "Host: %s\r\n", destination.String())
	}

	// === Step 2.5: 构建 del headers 集合 ===
	// 根据 HTTP/HTTPS 选择不同的 del 列表，合并为统一的 skip 集合
	delHeaders := make(map[string]bool)
	// 内置 skip (始终跳过)
	delHeaders["user-agent"] = true
	delHeaders["proxy-connection"] = true
	delHeaders["host"] = true // host 已在上方单独处理
	// 用户配置的 del
	if isHttps {
		for _, h := range c.httpsDel {
			delHeaders[strings.ToLower(h)] = true
		}
	} else {
		for _, h := range c.httpDel {
			delHeaders[strings.ToLower(h)] = true
		}
	}

	// 写入自定义 headers
	if c.headers != nil {
		for key, values := range c.headers {
			if delHeaders[strings.ToLower(key)] {
				continue
			}
			for _, value := range values {
				fmt.Fprintf(&raw, "%s: %s\r\n", key, value)
			}
		}
	}

	// Proxy-Authorization (如果需要认证)
	if c.username != "" {
		auth := c.username + ":" + c.password
		fmt.Fprintf(&raw, "Proxy-Authorization: Basic %s\r\n", base64.StdEncoding.EncodeToString([]byte(auth)))
	}

	// 结束 header
	raw.WriteString("\r\n")


	// 一次性写入整条 CONNECT 请求
	_, err = conn.Write([]byte(raw.String()))
	if err != nil {
		conn.Close()
		return nil, err
	}

	// === Step 3: 读取响应 ===
	// 用最小的 request 对象让 http.ReadResponse 工作
	request := &http.Request{
		Method: http.MethodConnect,
		URL:    &url.URL{Host: destination.String()},
	}

	reader := std_bufio.NewReader(conn)
	response, err := http.ReadResponse(reader, request)
	if err != nil {
		conn.Close()
		return nil, err
	}
	if response.StatusCode == http.StatusOK {
		if reader.Buffered() > 0 {
			buffer := buf.NewSize(reader.Buffered())
			_, err = buffer.ReadFullFrom(reader, buffer.FreeLen())
			if err != nil {
				conn.Close()
				return nil, err
			}
			conn = bufio.NewCachedConn(conn, buffer)
		}
		return conn, nil
	} else {
		conn.Close()
		switch response.StatusCode {
		case http.StatusProxyAuthRequired:
			return nil, E.New("authentication required")
		case http.StatusMethodNotAllowed:
			return nil, E.New("method not allowed")
		default:
			return nil, E.New("unexpected status: ", response.Status)
		}
	}
}

func (c *Client) ListenPacket(ctx context.Context, destination M.Socksaddr) (net.PacketConn, error) {
	return nil, os.ErrInvalid
}
