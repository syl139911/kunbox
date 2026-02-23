package com.kunk.singbox.model

import org.junit.Assert.assertEquals
import org.junit.Test

class PingDisplayTextTest {
    private val timeoutText = "超时"
    private val unavailableText = "不可用"
    private val ipv6OnlyText = "仅IPv6可用"
    @Test
    fun resolve_returnsDash_whenDisconnected() {
        val text = PingDisplayText.resolve(
            isConnected = false,
            displayPing = 123L,
            timeoutText = timeoutText,
            unavailableText = unavailableText,
            ipv6OnlyText = ipv6OnlyText
        )

        assertEquals("-", text)
    }

    @Test
    fun resolve_returnsMs_whenPositivePing() {
        val text = PingDisplayText.resolve(
            isConnected = true,
            displayPing = 88L,
            timeoutText = timeoutText,
            unavailableText = unavailableText,
            ipv6OnlyText = ipv6OnlyText
        )

        assertEquals("88 ms", text)
    }

    @Test
    fun resolve_returnsTimeout_whenFailedTimeoutCode() {
        val text = PingDisplayText.resolve(
            isConnected = true,
            displayPing = PingResultCode.FAILED_TIMEOUT,
            timeoutText = timeoutText,
            unavailableText = unavailableText,
            ipv6OnlyText = ipv6OnlyText
        )

        assertEquals("超时", text)
    }

    @Test
    fun resolve_returnsUnavailable_whenUnavailableCode() {
        val text = PingDisplayText.resolve(
            isConnected = true,
            displayPing = PingResultCode.UNAVAILABLE,
            timeoutText = timeoutText,
            unavailableText = unavailableText,
            ipv6OnlyText = ipv6OnlyText
        )

        assertEquals("不可用", text)
    }
}
