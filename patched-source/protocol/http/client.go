// KunBox patched: sing/protocol/http/client.go
// 基于 sing v0.8.9 (sing-box v1.13.11 依赖)
// 改动:
//   Patch 03: +delHost 字段 + raw TCP CONNECT (替换 Go http.Request.Write)
//   Patch 07: +httpFirst 字段 + http_first 写入
//   Patch 04: +httpsFirst +httpDel +httpsDel — HTTP/HTTPS 分离处理
//   Patch 05: +removePort +host — CONNECT 不带端口 + Host 强制覆盖 + 宽松响应解析
//
// === TPBox 执行顺序 ===
//   1. conn.Write(first)    ← preface (http_first 或 https_first，按端口选择)
//   2. conn.Write(raw CONNECT) ← 拼接后的 CONNECT 行 + headers
//   3. 宽松读取响应 (手动 ReadString，接受非标准格式)
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
	"crypto/tls"
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
	// ========== KunBox 新增字段 (Patch 05) ==========
	removePort bool   // CONNECT 行不带端口
	hostOption string // 强制替换 Host header (独立于 headers)
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
	// ========== KunBox 新增选项 (Patch 05) ==========
	RemovePort bool   // CONNECT 行不带端口
	Host       string // 强制替换 Host header
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
		// ========== KunBox 赋值 (Patch 05) ==========
		removePort: options.RemovePort,
		hostOption: options.Host,
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

// ========== KunBox Debug Getters ==========
func (c *Client) ServerAddr() M.Socksaddr { return c.serverAddr }
func (c *Client) DelHost() bool            { return c.delHost }
func (c *Client) RemovePort() bool         { return c.removePort }
func (c *Client) Path() string             { return c.path }
func (c *Client) Host() string             { return c.hostOption }
func (c *Client) HttpFirst() string        { return c.httpFirst }
func (c *Client) HttpsFirst() string       { return c.httpsFirst }
// ==========================================


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
		fmt.Fprintf(os.Stderr, "[KunBox-HTTP] dial to proxy FAILED: %s err=%v\n", c.serverAddr, err)
		return nil, err
	}
	fmt.Fprintf(os.Stderr, "[KunBox-HTTP] dial to proxy OK: %s\n", c.serverAddr)
	if tlsConn, ok := conn.(*tls.Conn); ok {
		state := tlsConn.ConnectionState()
		fmt.Fprintf(os.Stderr, "[KunBox-HTTP] TLS: version=%x cipher=%x server=%q\n",
			state.Version, state.CipherSuite, state.ServerName)
	}

	// ============================================================
	// === TPBox 级 HTTP CONNECT 链路重写                       ===
	// ============================================================

	isHttps := destination.Port == 443 || c.httpsFirst != ""

	// === Step 1: http_first / https_first (preface) ===
	var firstContent string
	if isHttps {
		firstContent = c.httpsFirst
	} else {
		firstContent = c.httpFirst
	}
	if firstContent != "" {
		fmt.Fprintf(os.Stderr, "[KunBox-HTTP] first >>> %q\n", firstContent)
		_, err = conn.Write([]byte(firstContent))
		if err != nil {
			fmt.Fprintf(os.Stderr, "[KunBox-HTTP] httpFirst write FAILED: err=%v\n", err)
			conn.Close()
			return nil, err
		}
	}

	// === Step 2: 构建 raw TCP CONNECT ===

	// --- 构建 CONNECT 目标 ---
	// 模式判断:
	//   del_host=true:  target = path (仅 path，如 /@dingtalk.com)
	//   remove_port=true: target = host (不带端口)
	//   默认:           target = host:port
	var target string

	if c.delHost {
		// del_host 模式: CONNECT 行只用 path，完全隐藏真实目标
		target = c.path
		if target == "" {
			// del_host 但没配 path，fallback 到 host only
			target = destination.Fqdn
		} else {
			// path 没带 / 开头则自动补
			if !strings.HasPrefix(target, "/") {
				target = "/" + target
			}
		}
	} else if c.removePort {
		// remove_port 模式: CONNECT 行不带端口
		target = destination.Fqdn
		if c.path != "" {
			target += c.path
		}
	} else {
		// 标准模式: host:port
		target = destination.String()
		if c.path != "" {
			target += c.path
		}
	}

	var raw strings.Builder
	fmt.Fprintf(&raw, "CONNECT %s HTTP/1.1\r\n", target)

	// --- Host header ---
	// 优先级: hostOption > headers 中的 Host > destination
	var hostValue string
	if c.hostOption != "" {
		hostValue = c.hostOption
	} else if c.host != "" {
		hostValue = c.host
	} else if !c.delHost {
		hostValue = destination.String()
	}
	if hostValue != "" {
		fmt.Fprintf(&raw, "Host: %s\r\n", hostValue)
	}

	// User-Agent
	fmt.Fprintf(&raw, "User-Agent: Go-http-client/1.1\r\n")

	// --- del headers ---
	delHeaders := make(map[string]bool)
	delHeaders["proxy-connection"] = true
	delHeaders["host"] = true
	if isHttps {
		for _, h := range c.httpsDel {
			delHeaders[strings.ToLower(h)] = true
		}
	} else {
		for _, h := range c.httpDel {
			delHeaders[strings.ToLower(h)] = true
		}
	}

	// 自定义 headers
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

	// Proxy-Authorization
	if c.username != "" {
		auth := c.username + ":" + c.password
		fmt.Fprintf(&raw, "Proxy-Authorization: Basic %s\r\n", base64.StdEncoding.EncodeToString([]byte(auth)))
	}

	raw.WriteString("\r\n")

	connectLog := raw.String()
	if len(connectLog) > 300 {
		connectLog = connectLog[:300] + "...(truncated)"
	}
	fmt.Fprintf(os.Stderr, "[KunBox-HTTP] CONNECT >>> %s", connectLog)

	_, err = conn.Write([]byte(raw.String()))
	if err != nil {
		fmt.Fprintf(os.Stderr, "[KunBox-HTTP] CONNECT write FAILED: err=%v\n", err)
		conn.Close()
		return nil, err
	}

	// === Step 3: 宽松响应解析 ===
	// 替换 http.ReadResponse()，接受非标准响应:
	//   "HTTP/1.1 200 OK"
	//   "HTTP/1.0 200 OK"
	//   "200 OK"
	//   甚至只包含 "200" 的行
	reader := std_bufio.NewReader(conn)

	// 读取状态行
	statusLine, err := reader.ReadString('\n')
	if err != nil {
		fmt.Fprintf(os.Stderr, "[KunBox-HTTP] read status line FAILED: err=%v\n", err)
		conn.Close()
		return nil, E.New("failed to read proxy response: ", err)
	}
	statusLine = strings.TrimSpace(statusLine)
	fmt.Fprintf(os.Stderr, "[KunBox-HTTP] proxy status line: %q\n", statusLine)

	// 检查是否包含 200
	if !strings.Contains(statusLine, "200") {
		// 吃掉剩余 headers 再报错
		for {
			line, readErr := reader.ReadString('\n')
			if line == "\r\n" || line == "\n" || readErr != nil {
				break
			}
		}
		conn.Close()
		return nil, E.New("connect failed: ", statusLine)
	}

	// 吃掉剩余 response headers 直到空行
	for {
		line, readErr := reader.ReadString('\n')
		if line == "\r\n" || line == "\n" || readErr != nil {
			break
		}
	}

	// 连接建立成功
	if reader.Buffered() > 0 {
		buffer := buf.NewSize(reader.Buffered())
		_, err = buffer.ReadFullFrom(reader, buffer.FreeLen())
		if err != nil {
			fmt.Fprintf(os.Stderr, "[KunBox-HTTP] ReadFullFrom FAILED: err=%v\n", err)
			conn.Close()
			return nil, err
		}
		conn = bufio.NewCachedConn(conn, buffer)
	}
	return conn, nil
}

func (c *Client) ListenPacket(ctx context.Context, destination M.Socksaddr) (net.PacketConn, error) {
	return nil, os.ErrInvalid
}
