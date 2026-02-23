package com.kunk.singbox.service.manager

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class UrlTestTagMatcherTest {

    @Test
    fun resolveDelayDetail_directHitContainsMatchedKey() {
        val detail = UrlTestTagMatcher.resolveDelayDetail(
            results = mapOf("node-a" to 123),
            queryTag = "node-a"
        )

        assertNotNull(detail)
        assertEquals(123, detail?.delay)
        assertEquals("direct", detail?.matchType)
        assertEquals("node-a", detail?.matchedKey)
    }

    @Test
    fun resolveDelay_directHit() {
        val result = UrlTestTagMatcher.resolveDelay(
            results = mapOf("node-a" to 123),
            queryTag = "node-a"
        )

        assertNotNull(result)
        assertEquals(123, result?.first)
        assertEquals("direct", result?.second)
    }

    @Test
    fun resolveDelay_normalizedHit() {
        val result = UrlTestTagMatcher.resolveDelay(
            results = mapOf("node a" to 88),
            queryTag = "  node\u00A0a  "
        )

        assertNotNull(result)
        assertEquals(88, result?.first)
        assertEquals("normalized", result?.second)
    }

    @Test
    fun resolveDelay_fingerprintHitWithEmojiPrefix() {
        val result = UrlTestTagMatcher.resolveDelay(
            results = mapOf("新加坡005｜0.15元/G｜XHTTP｜" to 64),
            queryTag = "💙新加坡005｜0.15元/G｜XHTTP｜"
        )

        assertNotNull(result)
        assertEquals(64, result?.first)
        assertEquals("fingerprint", result?.second)
    }

    @Test
    fun resolveDelay_aliasHit() {
        val result = UrlTestTagMatcher.resolveDelay(
            results = mapOf("actual-node-tag" to 45),
            queryTag = "ui-node-name",
            aliasTags = listOf("actual-node-tag")
        )

        assertNotNull(result)
        assertEquals(45, result?.first)
        assertEquals("normalized", result?.second)
    }

    @Test
    fun resolveDelayDetail_aliasHitContainsMatchedKey() {
        val detail = UrlTestTagMatcher.resolveDelayDetail(
            results = mapOf("actual-node-tag" to 45),
            queryTag = "ui-node-name",
            aliasTags = listOf("actual-node-tag")
        )

        assertNotNull(detail)
        assertEquals(45, detail?.delay)
        assertEquals("normalized", detail?.matchType)
        assertEquals("actual-node-tag", detail?.matchedKey)
    }

    @Test
    fun resolveDelay_returnsNullWhenNoPositiveDelay() {
        val result = UrlTestTagMatcher.resolveDelay(
            results = mapOf("node-a" to -1, "node-b" to 0),
            queryTag = "node-a"
        )

        assertNull(result)
    }
}
