package com.kunk.singbox.repository.config

import com.kunk.singbox.model.Outbound
import org.junit.Assert.assertEquals
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
}
