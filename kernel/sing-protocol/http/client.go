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
	delHost    bool
}

type Options struct {
	Dialer   N.Dialer
	Server   M.Socksaddr
	Username string
	Password string
	Path     string
	Headers  http.Header
	DelHost  bool
}

func NewClient(options Options) *Client {
	client := &Client{
		dialer:     options.Dialer,
		serverAddr: options.Server,
		username:   options.Username,
		password:   options.Password,
		path:       options.Path,
		headers:    options.Headers,
		delHost:    options.DelHost,
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

	destinationStr := destination.String()

	// 用于 http.ReadResponse 的伪请求
	readRequest := &http.Request{Method: http.MethodConnect}

	if c.delHost {
		// del_host=true: 手写 CONNECT 包，不发 Host 头（TPBox 行为）
		connectTarget := destinationStr
		if c.path != "" {
			connectTarget = destinationStr + c.path
		}

		raw := fmt.Sprintf("CONNECT %s HTTP/1.1\r\n", connectTarget)
		raw += "Proxy-Connection: Keep-Alive\r\n"
		if c.username != "" {
			auth := c.username + ":" + c.password
			raw += "Proxy-Authorization: Basic " + base64.StdEncoding.EncodeToString([]byte(auth)) + "\r\n"
		}
		for key, valueList := range c.headers {
			for _, value := range valueList {
				raw += key + ": " + value + "\r\n"
			}
		}
		raw += "\r\n"

		_, err = conn.Write([]byte(raw))
		if err != nil {
			conn.Close()
			return nil, err
		}
	} else {
		// 默认行为: 使用 net/http 标准库
		request := &http.Request{
			Method: http.MethodConnect,
			Header: http.Header{
				"Proxy-Connection": []string{"Keep-Alive"},
			},
		}
		if c.host != "" && c.host != destination.Fqdn {
			if c.path != "" {
				_ = conn.Close()
				return nil, E.New("Host header and path are not allowed at the same time")
			}
			request.Host = c.host
			request.URL = &url.URL{Opaque: destinationStr}
		} else {
			request.URL = &url.URL{Host: destinationStr}
		}
		if c.path != "" {
			err = URLSetPath(request.URL, c.path)
			if err != nil {
				_ = conn.Close()
				return nil, err
			}
		}
		for key, valueList := range c.headers {
			request.Header.Set(key, valueList[0])
			for _, value := range valueList[1:] {
				request.Header.Add(key, value)
			}
		}
		if c.username != "" {
			auth := c.username + ":" + c.password
			request.Header.Add("Proxy-Authorization", "Basic "+base64.StdEncoding.EncodeToString([]byte(auth)))
		}
		err = request.Write(conn)
		if err != nil {
			conn.Close()
			return nil, err
		}
		readRequest = request
	}

	reader := std_bufio.NewReader(conn)
	response, err := http.ReadResponse(reader, readRequest)
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
