package com.kunk.singbox.repository.config

import com.kunk.singbox.model.AppSettings
import com.kunk.singbox.model.TunStack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InboundBuilderTest {

    @Test
    fun buildOmitsLegacySniffFieldsWhileKeepingExpectedInbounds() {
        val inbounds = InboundBuilder.build(
            settings = AppSettings(
                tunEnabled = true,
                proxyPort = 7890
            ),
            effectiveTunStack = TunStack.MIXED
        )

        assertEquals(listOf("mixed-in", "tun-in"), inbounds.mapNotNull { it.tag })
        assertTrue(inbounds.all { it.sniff == null })
        assertTrue(inbounds.all { it.sniffOverrideDestination == null })
        assertTrue(inbounds.all { it.sniffTimeout == null })
    }

    @Test
    fun buildFallbackMixedInboundAlsoOmitsLegacySniffFields() {
        val inbound = InboundBuilder.build(
            settings = AppSettings(
                tunEnabled = false,
                proxyPort = 0
            ),
            effectiveTunStack = TunStack.SYSTEM
        ).single()

        assertEquals("mixed-in", inbound.tag)
        assertNull(inbound.sniff)
        assertNull(inbound.sniffOverrideDestination)
        assertNull(inbound.sniffTimeout)
    }
}
