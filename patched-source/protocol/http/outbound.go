// KunBox patched: protocol/http/outbound.go
// 基于 sing-box v1.13.11
// 改动: +DelHost +HttpFirst 传递到 sHTTP.Client (Patch 02)
// 原始文件: https://github.com/SagerNet/sing-box/blob/v1.13.11/protocol/http/outbound.go

package http

import (
	"context"
	"net"
	"fmt"
	"os"

	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/adapter/outbound"
	"github.com/sagernet/sing-box/common/dialer"
	"github.com/sagernet/sing-box/common/tls"
	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/log"
	"github.com/sagernet/sing-box/option"
	"github.com/sagernet/sing/common"
	"github.com/sagernet/sing/common/logger"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	sHTTP "github.com/sagernet/sing/protocol/http"
)

func RegisterOutbound(registry *outbound.Registry) {
	outbound.Register[option.HTTPOutboundOptions](registry, C.TypeHTTP, NewOutbound)
}

type Outbound struct {
	outbound.Adapter
	logger logger.ContextLogger
	client *sHTTP.Client
}

func NewOutbound(ctx context.Context, router adapter.Router, logger log.ContextLogger, tag string, options option.HTTPOutboundOptions) (adapter.Outbound, error) {
	outboundDialer, err := dialer.New(ctx, options.DialerOptions, options.ServerIsDomain())
	if err != nil {
		return nil, err
	}
	detour, err := tls.NewDialerFromOptions(ctx, logger, outboundDialer, options.Server, common.PtrValueOrDefault(options.TLS))
	if err != nil {
		return nil, err
	}
	return &Outbound{
		Adapter: outbound.NewAdapterWithDialerOptions(C.TypeHTTP, tag, []string{N.NetworkTCP}, options.DialerOptions),
		logger:  logger,
		client: sHTTP.NewClient(sHTTP.Options{
			Dialer:   detour,
			Server:   options.ServerOptions.Build(),
			Username: options.Username,
			Password: options.Password,
			Path:     options.Path,
			Headers:  options.Headers.Build(),
			// ========== KunBox 新增传递 (Patch 02) ==========
			DelHost:  options.DelHost,
			HttpFirst: options.HttpFirst,
			// ===============================================
			// ========== KunBox 新增传递 (Patch 04) ==========
			HttpsFirst: options.HttpsFirst,
			HttpDel:    options.HttpDel,
			HttpsDel:   options.HttpsDel,
			// ===============================================
		}),
	}, nil
}

func (h *Outbound) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	ctx, metadata := adapter.ExtendContext(ctx)
	metadata.Outbound = h.Tag()
	metadata.Destination = destination
	h.logger.InfoContext(ctx, "outbound connection to ", destination)
	// [KunBox Debug] outbound 连接详情
	fmt.Fprintf(os.Stderr, "[KunBox-OUT] dial %s -> server=%s delHost=%v path=%q httpFirst=%q httpsFirst=%q\n",
		network, h.client.ServerAddr(), h.client.DelHost(), h.client.Path(), h.client.HttpFirst(), h.client.HttpsFirst())
	return h.client.DialContext(ctx, network, destination)
}

func (h *Outbound) ListenPacket(ctx context.Context, destination M.Socksaddr) (net.PacketConn, error) {
	return nil, os.ErrInvalid
}
