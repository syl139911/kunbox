package com.kunk.singbox.repository.config

import com.kunk.singbox.model.Outbound
import com.kunk.singbox.model.TlsConfig
import com.kunk.singbox.model.TransportConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
}
