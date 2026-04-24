package com.kunk.singbox.repository.config

import com.kunk.singbox.model.DomainResolveConfig
import com.kunk.singbox.model.ObfsConfig
import com.kunk.singbox.model.Outbound
import com.kunk.singbox.model.TlsConfig
import com.kunk.singbox.model.TransportConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Test

class OutboundFixerTest {

    @Test
    fun testFixNaivePreservesH2NetworkForRuntime() {
        val outbound = Outbound(
            type = "naive",
            tag = "naive-node",
            server = "naive.example.com",
            serverPort = 443,
            username = "u",
            password = "p",
            network = "h2"
        )

        val fixed = OutboundFixer.buildRuntimeNaiveOutbound(
            fixed = outbound,
            tcpKeepAliveEnabled = false,
            tcpKeepAliveInterval = null,
            connectTimeout = null
        )

        assertEquals(null, fixed.network)
        assertEquals(false, fixed.quic)
        assertEquals("h2", outbound.network)
    }

    @Test
    fun testBuildRuntimeHysteria2PreservesCriticalFields() {
        val outbound = Outbound(
            type = "hysteria2",
            tag = "hy2-node",
            server = "hy2.example.com",
            serverPort = 443,
            password = "secret",
            upMbps = 100,
            downMbps = 200,
            obfs = ObfsConfig(type = "salamander", password = "obfs-pass"),
            serverPorts = listOf("20000", "20001"),
            hopInterval = "30s",
            tls = TlsConfig(enabled = true, serverName = "edge.example.com")
        )

        val runtime = OutboundFixer.buildRuntimeHysteriaOutbound(
            outbound,
            null,
            null
        )

        assertEquals("hysteria2", runtime.type)
        assertEquals("secret", runtime.password)
        assertEquals(100, runtime.upMbps)
        assertEquals(200, runtime.downMbps)
        assertEquals("salamander", runtime.obfs?.type)
        assertEquals("obfs-pass", runtime.obfs?.password)
        assertEquals(listOf("20000", "20001"), runtime.serverPorts)
        assertEquals("30s", runtime.hopInterval)
        assertEquals("edge.example.com", runtime.tls?.serverName)
    }

    @Test
    fun testBuildRuntimeHysteria2AddsBootstrapDomainResolverForHostnames() {
        val outbound = Outbound(
            type = "hysteria2",
            tag = "hy2-node",
            server = "hy2.example.com",
            serverPort = 443,
            password = "secret"
        )

        val runtime = OutboundFixer.buildRuntimeHysteriaOutbound(
            outbound,
            null,
            null
        )

        assertEquals("dns-bootstrap", runtime.domainResolver?.server)
    }

    @Test
    fun testBuildRuntimeHysteria2PreservesExistingDomainResolver() {
        val outbound = Outbound(
            type = "hysteria2",
            tag = "hy2-node",
            server = "hy2.example.com",
            serverPort = 443,
            password = "secret",
            domainResolver = DomainResolveConfig(server = "custom-bootstrap", strategy = "prefer_ipv4")
        )

        val runtime = OutboundFixer.buildRuntimeHysteriaOutbound(
            outbound,
            null,
            null
        )

        assertEquals("custom-bootstrap", runtime.domainResolver?.server)
        assertEquals("prefer_ipv4", runtime.domainResolver?.strategy)
    }

    @Test
    fun testBuildRuntimeHysteria2DoesNotAddBootstrapDomainResolverForIpLiteral() {
        val outbound = Outbound(
            type = "hysteria2",
            tag = "hy2-node",
            server = "1.2.3.4",
            serverPort = 443,
            password = "secret"
        )

        val runtime = OutboundFixer.buildRuntimeHysteriaOutbound(
            outbound,
            null,
            null
        )

        assertNull(runtime.domainResolver)
    }

    @Test
    fun testBuildRuntimeHysteria2DefaultsBandwidthTo50WhenMissing() {
        val outbound = Outbound(
            type = "hysteria2",
            tag = "hy2-node",
            server = "hy2.example.com",
            serverPort = 443,
            password = "secret"
        )

        val runtime = OutboundFixer.buildRuntimeHysteriaOutbound(
            outbound,
            null,
            null
        )

        assertEquals(50, runtime.upMbps)
        assertEquals(50, runtime.downMbps)
    }

    @Test
    fun testBuildRuntimeHysteria2DoesNotInjectTcpFields() {
        val outbound = Outbound(
            type = "hysteria2",
            tag = "hy2-node",
            server = "hy2.example.com",
            serverPort = 443,
            password = "secret"
        )

        val runtime = OutboundFixer.buildRuntimeHysteriaOutbound(
            outbound,
            "15s",
            "5s"
        )

        assertNull(runtime.tcpKeepAlive)
        assertNull(runtime.tcpKeepAliveInterval)
        assertNull(runtime.connectTimeout)
    }

    @Test
    fun testBuildForRuntimeClearsTuicServerNameWhenDisableSniEnabled() {
        val outbound = Outbound(
            type = "tuic",
            tag = "tuic-node",
            server = "tuic.example.com",
            serverPort = 443,
            uuid = "uuid",
            password = "secret",
            disableSni = true,
            tls = TlsConfig(
                enabled = true,
                serverName = "tuic.example.com"
            )
        )

        val runtime = OutboundFixer.buildForRuntimeWithDialConfigForTest(outbound)

        assertEquals(true, runtime?.disableSni)
        assertEquals(true, runtime?.tls?.enabled)
        assertNull(runtime?.tls?.serverName)
    }

    @Test
    fun testBuildForRuntimeKeepsTuicServerNameWhenDisableSniDisabled() {
        val outbound = Outbound(
            type = "tuic",
            tag = "tuic-node",
            server = "tuic.example.com",
            serverPort = 443,
            uuid = "uuid",
            password = "secret",
            disableSni = null,
            tls = TlsConfig(
                enabled = true,
                serverName = "edge.example.com"
            )
        )

        val runtime = OutboundFixer.buildForRuntimeWithDialConfigForTest(outbound)

        assertNull(runtime?.disableSni)
        assertEquals("edge.example.com", runtime?.tls?.serverName)
    }

    @Test
    fun testFixPreservesWhitelistedAutoUrlTestWithoutDefault() {
        val outbound = Outbound(
            type = "urltest",
            tag = "P:HK#AUTO",
            outbounds = listOf("node-a", "node-b"),
            default = "node-b",
            url = "https://www.gstatic.com/generate_204",
            interval = "10m",
            tolerance = 50
        )

        val fixed = OutboundFixer.fix(outbound)

        assertEquals("urltest", fixed.type)
        assertEquals(listOf("node-a", "node-b"), fixed.outbounds)
        assertNull(fixed.default)
        assertEquals("https://www.gstatic.com/generate_204", fixed.url)
        assertEquals("10m", fixed.interval)
        assertEquals(50, fixed.tolerance)
    }

    @Test
    fun testBuildForRuntimeKeepsWhitelistedUrlTestWithoutDefault() {
        val outbound = Outbound(
            type = "urltest",
            tag = "P:HK#AUTO",
            outbounds = listOf("node-a", "node-b"),
            default = "node-b",
            url = "https://www.gstatic.com/generate_204",
            interval = "10m",
            tolerance = 50
        )

        val runtime = OutboundFixer.buildForRuntimeWithDialConfigForTest(outbound)

        assertEquals("urltest", runtime?.type)
        assertEquals(listOf("node-a", "node-b"), runtime?.outbounds)
        assertNull(runtime?.default)
        assertEquals("https://www.gstatic.com/generate_204", runtime?.url)
        assertEquals("10m", runtime?.interval)
        assertEquals(50, runtime?.tolerance)
    }

    @Test
    fun testBuildForRuntimePreservesVlessCustomEncryption() {
        val outbound = Outbound(
            type = "vless",
            tag = "encrypted-xhttp",
            server = "xhttp.example.com",
            serverPort = 443,
            uuid = "uuid",
            flow = "xtls-rprx-vision",
            encryption = "mlkem768x25519plus.native.0rtt.sample"
        )

        val runtime = OutboundFixer.buildForRuntimeWithDialConfigForTest(outbound)

        assertEquals("vless", runtime?.type)
        assertEquals("mlkem768x25519plus.native.0rtt.sample", runtime?.encryption)
    }

    @Test
    fun testBuildForRuntimeClearsImplicitXudpForVlessXhttp() {
        val outbound = Outbound(
            type = "vless",
            tag = "xhttp-node",
            server = "xhttp.example.com",
            serverPort = 443,
            uuid = "uuid",
            transport = com.kunk.singbox.model.TransportConfig(type = "xhttp", path = "/x")
        )

        val runtime = OutboundFixer.buildForRuntimeWithDialConfigForTest(outbound)

        assertEquals("", runtime?.packetEncoding)
    }

    @Test
    fun testBuildForRuntimeKeepsVmessHttpTransport() {
        val outbound = Outbound(
            type = "vmess",
            tag = "vmess-http-node",
            server = "18.225.57.7",
            serverPort = 32721,
            uuid = "c31a559b-8285-4b11-db99-d1edfc2b2b70",
            transport = TransportConfig(type = "http")
        )

        val runtime = OutboundFixer.buildForRuntimeWithDialConfigForTest(outbound)

        assertEquals("vmess", runtime?.type)
        assertEquals("http", runtime?.transport?.type)
    }

    @Test
    fun testFixPreservesOuterSelectorDefaultForRouteGroup() {
        val outbound = Outbound(
            type = "selector",
            tag = "P:HK",
            outbounds = listOf("P:HK#AUTO", "PROXY"),
            default = "P:HK#AUTO"
        )

        val fixed = OutboundFixer.fix(outbound)

        assertEquals("selector", fixed.type)
        assertEquals(listOf("P:HK#AUTO", "PROXY"), fixed.outbounds)
        assertEquals("P:HK#AUTO", fixed.default)
        assertFalse(OutboundFixer.shouldKeepUrlTestForRuntime(fixed.tag))
    }

    @Test
    fun testFixConvertsNonWhitelistedUrlTestToSelector() {
        val outbound = Outbound(
            type = "urltest",
            tag = "manual-auto",
            outbounds = listOf("node-a", "node-b"),
            url = "https://example.com/test",
            interval = "5m",
            tolerance = 10
        )

        val fixed = OutboundFixer.fix(outbound)

        assertEquals("selector", fixed.type)
        assertEquals(listOf("node-a", "node-b"), fixed.outbounds)
        assertEquals("node-a", fixed.default)
        assertNull(fixed.url)
        assertNull(fixed.interval)
        assertNull(fixed.tolerance)
    }
}
