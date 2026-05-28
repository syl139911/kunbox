// KunBox patched: option/simple.go
// 基于 sing-box v1.13.11
// 改动: +DelHost +HttpFirst (KunBox TPBox 扩展)
// 原始文件: https://github.com/SagerNet/sing-box/blob/v1.13.11/option/simple.go

package option

import (
	"github.com/sagernet/sing/common/auth"
	"github.com/sagernet/sing/common/json/badoption"
)

type SocksInboundOptions struct {
	ListenOptions
	Users          []auth.User           `json:"users,omitempty"`
	DomainResolver *DomainResolveOptions `json:"domain_resolver,omitempty"`
}

type HTTPMixedInboundOptions struct {
	ListenOptions
	Users          []auth.User           `json:"users,omitempty"`
	DomainResolver *DomainResolveOptions `json:"domain_resolver,omitempty"`
	SetSystemProxy bool                  `json:"set_system_proxy,omitempty"`
	InboundTLSOptionsContainer
}

type SOCKSOutboundOptions struct {
	DialerOptions
	ServerOptions
	Version    string             `json:"version,omitempty"`
	Username   string             `json:"username,omitempty"`
	Password   string             `json:"password,omitempty"`
	Network    NetworkList        `json:"network,omitempty"`
	UDPOverTCP *UDPOverTCPOptions `json:"udp_over_tcp,omitempty"`
}

type HTTPOutboundOptions struct {
	DialerOptions
	ServerOptions
	Username string `json:"username,omitempty"`
	Password string `json:"password,omitempty"`
	OutboundTLSOptionsContainer
	Path      string               `json:"path,omitempty"`
	Headers   badoption.HTTPHeader `json:"headers,omitempty"`
	// ========== KunBox 新增字段 (Patch 01) ==========
	DelHost   bool                 `json:"del_host,omitempty"`   // TPBox: 隐藏真实 host，CONNECT 行用 path 替代
	HttpFirst string               `json:"http_first,omitempty"` // TPBox: HTTP preface，写在 CONNECT 之前的伪装请求
	// ===============================================
}
