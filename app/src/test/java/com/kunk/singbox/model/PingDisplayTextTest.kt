package com.kunk.singbox.model

import org.junit.Assert.assertEquals
import org.junit.Test

class PingDisplayTextTest {
    @Test
    fun resolve_returnsDash_whenDisconnected() {
        val text = PingDisplayText.resolve(
            isConnected = false,
            displayPing = 123L,
            timeoutText = "超时",
            unavailableText = "不可用"
        )

        assertEquals("-", text)
    }

    @Test
    fun resolve_returnsMs_whenPositivePing() {
        val text = PingDisplayText.resolve(
            isConnected = true,
            displayPing = 88L,
            timeoutText = "超时",
            unavailableText = "不可用"
        )

        assertEquals("88 ms", text)
    }

    @Test
    fun resolve_returnsTimeout_whenFailedTimeoutCode() {
        val text = PingDisplayText.resolve(
            isConnected = true,
            displayPing = PingResultCode.FAILED_TIMEOUT,
            timeoutText = "超时",
            unavailableText = "不可用"
        )

        assertEquals("超时", text)
    }

    @Test
    fun resolve_returnsUnavailable_whenUnavailableCode() {
        val text = PingDisplayText.resolve(
            isConnected = true,
            displayPing = PingResultCode.UNAVAILABLE,
            timeoutText = "超时",
            unavailableText = "不可用"
        )

        assertEquals("不可用", text)
    }
}
