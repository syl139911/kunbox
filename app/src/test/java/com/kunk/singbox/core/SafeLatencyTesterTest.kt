package com.kunk.singbox.core

import com.kunk.singbox.model.Outbound
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class SafeLatencyTesterTest {

    @Test
    fun testConcurrentRequestsReuseInFlightGroupResults() = runBlocking {
        val tester = createTester()
        val activeGroupTestField = tester.javaClass.getDeclaredField("activeGroupTest").apply {
            isAccessible = true
        }
        val inFlightTest = CompletableDeferred<Map<String, Int>>()
        activeGroupTestField.set(tester, inFlightTest)
        launch {
            inFlightTest.complete(
                mapOf(
                    "node-a" to 120,
                    "node-b" to 0
                )
            )
        }

        val results = linkedMapOf<String, Long>()
        tester.testOutboundsLatencySafe(
            outbounds = listOf(
                Outbound(type = "vless", tag = "node-a"),
                Outbound(type = "vless", tag = "node-b"),
                Outbound(type = "vless", tag = "node-c")
            ),
            targetUrl = "https://example.com/generate_204",
            timeoutMs = 1000
        ) { tag, latency ->
            results[tag] = latency
        }

        assertEquals(
            linkedMapOf(
                "node-a" to 120L,
                "node-b" to -1L,
                "node-c" to -1L
            ),
            results
        )
    }

    private fun createTester(): SafeLatencyTester {
        val constructor = SafeLatencyTester::class.java.getDeclaredConstructor()
        constructor.isAccessible = true
        return constructor.newInstance()
    }
}
