package com.kunk.singbox.service.manager

import com.kunk.singbox.model.Outbound
import com.kunk.singbox.model.RouteConfig
import com.kunk.singbox.model.RouteRule
import com.kunk.singbox.model.SingBoxConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
                    outbounds = listOf("node-a", "direct", "node-b", "PROXY", "node-a")
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
        assertEquals("PROXY", targets.first().fallbackTag)
        assertEquals("P:HK", targets.first().testGroupTag)
        assertNull(targets.first().autoGroupTag)
    }

    @Test
    fun testCollectRouteGroupTargetsPrefersNestedAutoUrlTestGroup() {
        val config = SingBoxConfig(
            outbounds = listOf(
                Outbound(
                    type = "selector",
                    tag = "P:HK",
                    outbounds = listOf("P:HK#AUTO", "PROXY")
                ),
                Outbound(
                    type = "urltest",
                    tag = "P:HK#AUTO",
                    outbounds = listOf("node-a", "node-b")
                ),
                Outbound(type = "selector", tag = "PROXY", outbounds = listOf("node-c")),
                Outbound(type = "vmess", tag = "node-a"),
                Outbound(type = "vmess", tag = "node-b")
            ),
            route = RouteConfig(
                rules = listOf(RouteRule(outbound = "P:HK"))
            )
        )

        val targets = RouteGroupSelector.collectRouteGroupTargets(config)

        assertEquals(1, targets.size)
        assertEquals("P:HK", targets.first().groupTag)
        assertEquals("P:HK#AUTO", targets.first().testGroupTag)
        assertEquals("P:HK#AUTO", targets.first().autoGroupTag)
        assertEquals(listOf("node-a", "node-b"), targets.first().candidates)
        assertEquals("PROXY", targets.first().fallbackTag)
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
    fun testSelectBestCandidateMatchesNormalizedAndFingerprintUrlTestKeys() {
        val best = RouteGroupSelector.selectBestCandidate(
            candidates = listOf("💙新加坡005｜0.15元/G｜XHTTP｜", "  node a  "),
            urlTestResults = mapOf(
                "新加坡005｜0.15元/G｜XHTTP｜" to 64,
                "node\u00A0a" to 88
            )
        )

        assertEquals("💙新加坡005｜0.15元/G｜XHTTP｜", best?.first)
        assertEquals(64L, best?.second)
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
    fun testShouldNotifyFallbackWhenSwitchSucceededFirstTime() {
        val shouldNotify = RouteGroupSelector.shouldNotifyFallback(
            currentSelected = "node-a",
            fallbackTag = "PROXY",
            switchSucceeded = true,
            wasFallbackActive = false
        )

        assertTrue(shouldNotify)
    }

    @Test
    fun testShouldNotifyFallbackWhenAlreadyUsingFallbackFirstSeen() {
        val shouldNotify = RouteGroupSelector.shouldNotifyFallback(
            currentSelected = "PROXY",
            fallbackTag = "PROXY",
            switchSucceeded = true,
            wasFallbackActive = false
        )

        assertTrue(shouldNotify)
    }

    @Test
    fun testShouldNotNotifyFallbackWhenSwitchFailed() {
        val shouldNotify = RouteGroupSelector.shouldNotifyFallback(
            currentSelected = "node-a",
            fallbackTag = "PROXY",
            switchSucceeded = false,
            wasFallbackActive = false
        )

        assertFalse(shouldNotify)
    }

    @Test
    fun testShouldNotNotifyFallbackRepeatedlyWithinSameEpisode() {
        val shouldNotify = RouteGroupSelector.shouldNotifyFallback(
            currentSelected = "PROXY",
            fallbackTag = "PROXY",
            switchSucceeded = true,
            wasFallbackActive = true
        )

        assertFalse(shouldNotify)
    }

    @Test
    fun testAutoSelectStillStartsImmediatelyAndKeepsThirtyMinuteInterval() {
        assertEquals(0L, RouteGroupSelector.INITIAL_AUTO_SELECT_DELAY_MS)
        assertEquals(30L * 60L * 1000L, RouteGroupSelector.AUTO_SELECT_INTERVAL_MS)
    }

    @Test
    fun testComputeImmediateReselectDelayReturnsZeroWithoutPreviousTrigger() {
        val delayMs = RouteGroupSelector.computeImmediateReselectDelayMs(
            lastRequestedAtMs = 0L,
            nowAtMs = 5_000L
        )

        assertEquals(0L, delayMs)
    }

    @Test
    fun testComputeImmediateReselectDelayReturnsRemainingDebounceWindow() {
        val delayMs = RouteGroupSelector.computeImmediateReselectDelayMs(
            lastRequestedAtMs = 10_000L,
            nowAtMs = 10_400L,
            debounceMs = 1_500L
        )

        assertEquals(1_100L, delayMs)
    }

    @Test
    fun testComputeImmediateReselectDelayReturnsZeroAfterDebounceElapsed() {
        val delayMs = RouteGroupSelector.computeImmediateReselectDelayMs(
            lastRequestedAtMs = 10_000L,
            nowAtMs = 12_000L,
            debounceMs = 1_500L
        )

        assertEquals(0L, delayMs)
    }

    @Test
    fun testNetworkImmediateReselectTriggerOnlyMatchesNetworkReasons() {
        assertTrue(RouteGroupSelector.isNetworkImmediateReselectTrigger("immediate:network_type_changed"))
        assertTrue(RouteGroupSelector.isNetworkImmediateReselectTrigger("immediate:network_validated"))
        assertFalse(RouteGroupSelector.isNetworkImmediateReselectTrigger("periodic"))
        assertFalse(RouteGroupSelector.isNetworkImmediateReselectTrigger("immediate:app_foreground"))
    }

    @Test
    fun testShouldTriggerConnectionConvergenceOnlyWhenImmediateSwitchActuallyChangesSelection() {
        val shouldTrigger = RouteGroupSelector.shouldTriggerConnectionConvergence(
            trigger = "immediate:network_type_changed",
            previousSelectedTag = "node-a",
            newSelectedTag = "node-b"
        )

        assertTrue(shouldTrigger)
    }

    @Test
    fun testShouldNotTriggerConnectionConvergenceWithoutActualSwitchOrNetworkImmediateTrigger() {
        assertFalse(
            RouteGroupSelector.shouldTriggerConnectionConvergence(
                trigger = "immediate:network_type_changed",
                previousSelectedTag = "node-a",
                newSelectedTag = "node-a"
            )
        )
        assertFalse(
            RouteGroupSelector.shouldTriggerConnectionConvergence(
                trigger = "periodic",
                previousSelectedTag = "node-a",
                newSelectedTag = "node-b"
            )
        )
        assertFalse(
            RouteGroupSelector.shouldTriggerConnectionConvergence(
                trigger = "immediate:network_validated",
                previousSelectedTag = null,
                newSelectedTag = "node-b"
            )
        )
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

    @Test
    fun testCollectRouteGroupTargetsLeavesFallbackNullWhenProxyMissing() {
        val config = SingBoxConfig(
            outbounds = listOf(
                Outbound(type = "selector", tag = "P:HK", outbounds = listOf("node-a", "node-b")),
                Outbound(type = "vmess", tag = "node-a"),
                Outbound(type = "vmess", tag = "node-b")
            ),
            route = RouteConfig(
                rules = listOf(RouteRule(outbound = "P:HK"))
            )
        )

        val targets = RouteGroupSelector.collectRouteGroupTargets(config)

        assertEquals(1, targets.size)
        assertNull(targets.first().fallbackTag)
    }
}
