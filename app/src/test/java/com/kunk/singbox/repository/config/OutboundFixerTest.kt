package com.kunk.singbox.repository.config

import com.kunk.singbox.model.DomainResolveConfig
import com.kunk.singbox.model.MultiplexConfig
import com.kunk.singbox.model.Outbound
import com.kunk.singbox.model.RealityConfig
import com.kunk.singbox.model.TlsConfig
import com.kunk.singbox.model.TransportConfig
import com.kunk.singbox.model.UdpOverTcpConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OutboundFixerTest {

    @Test
    fun fixShouldNormalizeXhttpPathAndAlignSniWithHost() {
        val outbound = Outbound(
            type = "vless",
            tag = "xhttp-node",
            server = "sg-005-x.xiaoxiaobujidao.xyz",
            serverPort = 443,
            uuid = "uuid-1",
            packetEncoding = "xudp",
            tls = TlsConfig(enabled = true, serverName = "sg-005-x.xiaoxiaobujidao.xyz"),
            transport = TransportConfig(
                type = "xhttp",
                path = "/kwejr32",
                host = listOf("edge.xhttp.cdn.example.com"),
                mode = "packet-up"
            )
        )

        val fixed = OutboundFixer.fix(outbound)

        assertEquals("/kwejr32", fixed.transport?.path)
        assertEquals("edge.xhttp.cdn.example.com", fixed.tls?.serverName)
        assertEquals("", fixed.packetEncoding)
    }

    @Test
    fun fixShouldKeepXhttpPathWithTrailingSlashUnchanged() {
        val outbound = Outbound(
            type = "trojan",
            tag = "trojan-xhttp",
            server = "trojan.example.com",
            serverPort = 443,
            password = "pwd",
            tls = TlsConfig(enabled = true, serverName = "cdn.example.com"),
            transport = TransportConfig(
                type = "xhttp",
                path = "/api/",
                host = listOf("cdn.example.com")
            )
        )

        val fixed = OutboundFixer.fix(outbound)

        assertNotNull(fixed.transport)
        assertEquals("/api/", fixed.transport?.path)
        assertEquals("cdn.example.com", fixed.tls?.serverName)
    }

    @Test
    fun fixShouldForceH2AlpnForXhttp() {
        val outbound = Outbound(
            type = "vless",
            tag = "xhttp-alpn",
            server = "sg-005-x.xiaoxiaobujidao.xyz",
            serverPort = 443,
            uuid = "uuid-1",
            tls = TlsConfig(
                enabled = true,
                serverName = "sg-005-x.xiaoxiaobujidao.xyz",
                alpn = listOf("h2", "http/1.1") // Link has both
            ),
            transport = TransportConfig(
                type = "xhttp",
                path = "/kwejr32",
                host = listOf("sg-005-x.xiaoxiaobujidao.xyz")
            )
        )

        val fixed = OutboundFixer.fix(outbound)

        assertNotNull(fixed.tls)
        assertEquals(listOf("h2"), fixed.tls?.alpn)
    }

    @Test
    fun fixShouldTuneMuxForVlessVisionRealityWithNonXhttpTransport() {
        val outbound = Outbound(
            type = "vless",
            tag = "vision-reality-ws",
            server = "edge.example.com",
            serverPort = 443,
            uuid = "uuid-vision",
            flow = "xtls-rprx-vision",
            tls = TlsConfig(
                enabled = true,
                serverName = "edge.example.com",
                reality = RealityConfig(enabled = true, publicKey = "pbk", shortId = "ab")
            ),
            transport = TransportConfig(type = "ws", path = "/ws"),
            multiplex = MultiplexConfig(
                enabled = true,
                protocol = "smux",
                maxConnections = 8,
                minStreams = 0,
                maxStreams = 64,
                padding = true
            )
        )

        val fixed = OutboundFixer.fix(outbound)

        assertNotNull(fixed.multiplex)
        assertEquals(true, fixed.multiplex?.enabled)
        assertEquals("h2mux", fixed.multiplex?.protocol)
        assertEquals(2, fixed.multiplex?.maxConnections)
        assertEquals(1, fixed.multiplex?.minStreams)
        assertEquals(8, fixed.multiplex?.maxStreams)
        assertEquals(false, fixed.multiplex?.padding)
    }

    @Test
    fun fixShouldKeepMuxForVlessVisionRealityWithXhttpTransport() {
        val outbound = Outbound(
            type = "vless",
            tag = "vision-reality-xhttp",
            server = "edge.example.com",
            serverPort = 443,
            uuid = "uuid-vision",
            flow = "xtls-rprx-vision",
            tls = TlsConfig(
                enabled = true,
                serverName = "edge.example.com",
                reality = RealityConfig(enabled = true, publicKey = "pbk", shortId = "ab")
            ),
            transport = TransportConfig(type = "xhttp", path = "/x"),
            multiplex = MultiplexConfig(enabled = true, protocol = "yamux", maxConnections = 8, padding = true)
        )

        val fixed = OutboundFixer.fix(outbound)

        assertNotNull(fixed.multiplex)
        assertEquals(true, fixed.multiplex?.enabled)
        assertEquals("yamux", fixed.multiplex?.protocol)
        assertEquals(8, fixed.multiplex?.maxConnections)
        assertEquals(true, fixed.multiplex?.padding)
    }

    @Test
    fun fixNaiveShouldMoveHeadersAndQuicToRuntimeFields() {
        val outbound = Outbound(
            type = "naive",
            tag = "naive-quic",
            server = "naive.example.com",
            serverPort = 443,
            username = "u",
            password = "p",
            network = "quic",
            insecureConcurrency = 2,
            extraHeaders = mapOf("Host" to "h.example.com", "User-Agent" to "naive"),
            congestionControl = "bbr",
            udpOverTcp = UdpOverTcpConfig(enabled = true),
            tls = TlsConfig(enabled = true)
        )

        val fixed = OutboundFixer.fix(outbound)

        assertEquals("quic", fixed.network)
        assertNull(fixed.path)
        assertNull(fixed.headers)
        assertEquals(2, fixed.insecureConcurrency)
        assertEquals("h.example.com", fixed.extraHeaders?.get("Host"))
        assertEquals("naive", fixed.extraHeaders?.get("User-Agent"))
        assertTrue(fixed.quic == true)
        assertEquals("bbr", fixed.quicCongestionControl)
        assertEquals(true, fixed.udpOverTcp?.enabled)
    }

    @Test
    fun fixNaiveShouldDefaultToH2WhenNoNetworkSpecified() {
        val outbound = Outbound(
            type = "naive",
            tag = "naive-h2",
            server = "naive.example.com",
            serverPort = 443,
            username = "u",
            password = "p",
            tls = TlsConfig(enabled = true)
        )

        val fixed = OutboundFixer.fix(outbound)

        assertEquals("h2", fixed.network)
        assertFalse(fixed.quic == true)
        assertEquals("dns-bootstrap", fixed.domainResolver?.server)
    }

    @Test
    fun fixNaiveShouldNotAddResolverWhenServerIsIpLiteral() {
        val outbound = Outbound(
            type = "naive",
            tag = "naive-ip",
            server = "1.1.1.1",
            serverPort = 443,
            username = "u",
            password = "p",
            tls = TlsConfig(enabled = true)
        )

        val fixed = OutboundFixer.fix(outbound)

        assertNull(fixed.domainResolver)
    }

    @Test
    fun fixNaiveShouldKeepExplicitResolver() {
        val outbound = Outbound(
            type = "naive",
            tag = "naive-custom-resolver",
            server = "naive.example.com",
            serverPort = 443,
            username = "u",
            password = "p",
            domainResolver = DomainResolveConfig(server = "dns-direct")
        )

        val fixed = OutboundFixer.fix(outbound)

        assertEquals("dns-direct", fixed.domainResolver?.server)
    }
}
