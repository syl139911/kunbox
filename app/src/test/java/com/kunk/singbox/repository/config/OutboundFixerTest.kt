package com.kunk.singbox.repository.config

import com.kunk.singbox.model.ObfsConfig
import com.kunk.singbox.model.Outbound
import com.kunk.singbox.model.TlsConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
            fixed = outbound,
            tcpKeepAliveInterval = null,
            connectTimeout = null
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
    fun testBuildRuntimeHysteria2DoesNotAddBootstrapDomainResolverByDefault() {
        val outbound = Outbound(
            type = "hysteria2",
            tag = "hy2-node",
            server = "hy2.example.com",
            serverPort = 443,
            password = "secret"
        )

        val runtime = OutboundFixer.buildRuntimeHysteriaOutbound(
            fixed = outbound,
            tcpKeepAliveInterval = null,
            connectTimeout = null
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
            fixed = outbound,
            tcpKeepAliveInterval = null,
            connectTimeout = null
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
            fixed = outbound,
            tcpKeepAliveInterval = "15s",
            connectTimeout = "5s"
        )

        assertNull(runtime.tcpKeepAlive)
        assertNull(runtime.tcpKeepAliveInterval)
        assertNull(runtime.connectTimeout)
    }
}
