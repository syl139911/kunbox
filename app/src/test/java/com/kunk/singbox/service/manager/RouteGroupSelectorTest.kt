package com.kunk.singbox.service.manager

import com.kunk.singbox.model.Outbound
import com.kunk.singbox.model.RouteConfig
import com.kunk.singbox.model.RouteRule
import com.kunk.singbox.model.SingBoxConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteGroupSelectorTest {

    @Test
    fun testCollectRouteGroupTargetsOnlyKeepsRouteReferencedSelectors() {
        val config = SingBoxConfig(
            outbounds = listOf(
                Outbound(
                    type = "selector",
                    tag = "P:HK",
                    outbounds = listOf("node-a", "direct", "node-b", "node-a")
                ),
                Outbound(
                    type = "selector",
                    tag = "PROXY",
                    outbounds = listOf("P:HK")
                ),
                Outbound(
                    type = "selector",
                    tag = "P:Unused",
                    outbounds = listOf("node-c")
                ),
                Outbound(type = "vmess", tag = "node-a"),
                Outbound(type = "vmess", tag = "node-b")
            ),
            route = RouteConfig(
                rules = listOf(
                    RouteRule(outbound = "P:HK"),
                    RouteRule(outbound = "DIRECT")
                )
            )
        )

        val targets = RouteGroupSelector.collectRouteGroupTargets(config)

        assertEquals(1, targets.size)
        assertEquals("P:HK", targets.first().groupTag)
        assertEquals(listOf("node-a", "node-b"), targets.first().candidates)
    }

    @Test
    fun testSelectBestCandidateReturnsLowestPositiveDelay() {
        val best = RouteGroupSelector.selectBestCandidate(
            candidates = listOf("node-a", "node-b", "node-c"),
            urlTestResults = mapOf(
                "node-a" to 180,
                "node-b" to 92,
                "node-c" to 0,
                "other" to 20
            )
        )

        assertEquals("node-b", best?.first)
        assertEquals(92L, best?.second)
    }

    @Test
    fun testSelectBestCandidateReturnsNullWhenNoUsableDelay() {
        val best = RouteGroupSelector.selectBestCandidate(
            candidates = listOf("node-a", "node-b"),
            urlTestResults = mapOf(
                "node-a" to 0,
                "node-b" to -1
            )
        )

        assertNull(best)
    }

    @Test
    fun testCollectRouteGroupTargetsSkipsProxySelectorEvenWhenReferenced() {
        val config = SingBoxConfig(
            outbounds = listOf(
                Outbound(type = "selector", tag = "PROXY", outbounds = listOf("node-a", "node-b")),
                Outbound(type = "vmess", tag = "node-a"),
                Outbound(type = "hysteria2", tag = "node-b")
            ),
            route = RouteConfig(
                rules = listOf(RouteRule(outbound = "PROXY"))
            )
        )

        val targets = RouteGroupSelector.collectRouteGroupTargets(config)

        assertTrue(targets.isEmpty())
    }

    @Test
    fun testCollectRouteGroupTargetsReturnsEmptyWithoutRouteSelectors() {
        val config = SingBoxConfig(
            outbounds = listOf(
                Outbound(type = "selector", tag = "P:HK", outbounds = listOf("node-a"))
            ),
            route = RouteConfig(
                rules = listOf(RouteRule(outbound = "DIRECT"))
            )
        )

        val targets = RouteGroupSelector.collectRouteGroupTargets(config)

        assertTrue(targets.isEmpty())
    }
}
