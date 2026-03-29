package com.kunk.singbox.repository

import com.google.gson.Gson
import com.kunk.singbox.model.AppGroup
import com.kunk.singbox.model.AppSettings
import com.kunk.singbox.model.CustomRule
import com.kunk.singbox.model.DomainResolveConfig
import com.kunk.singbox.model.Outbound
import com.kunk.singbox.model.OutboundTag
import com.kunk.singbox.model.RuleSet
import com.kunk.singbox.model.RuleSetConfig
import com.kunk.singbox.model.RuleSetOutboundMode
import com.kunk.singbox.model.RuleSetType
import com.kunk.singbox.model.RuleType
import com.kunk.singbox.model.BatchUpdateResult
import com.kunk.singbox.model.SubscriptionUpdateStage
import com.kunk.singbox.model.SubscriptionUpdateResult
import com.kunk.singbox.utils.parser.Base64Parser
import com.kunk.singbox.utils.parser.ClashYamlParser
import com.kunk.singbox.utils.parser.NodeLinkParser
import com.kunk.singbox.utils.parser.SingBoxParser
import com.kunk.singbox.utils.parser.SubscriptionManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

@Suppress("LargeClass")
class ConfigRepositoryTest {

    private val gson = Gson()
    private val nodeLinkParser = NodeLinkParser(gson)
    private val subscriptionManager = SubscriptionManager(
        listOf(
            SingBoxParser(gson),
            ClashYamlParser(),
            Base64Parser { nodeLinkParser.parse(it) }
        )
    )

    private fun invokeAppliedRemoteRuleSetFilter(
        ruleSets: List<RuleSet>,
        validRuleSets: List<RuleSetConfig>
    ): List<RuleSet> {
        val validTags = validRuleSets.mapNotNull { it.tag }.toSet()
        return ConfigRepository.filterAppliedRemoteRuleSetsForTest(ruleSets, validTags)
    }

    @Test
    fun testStableNodeIdConsistency() {
        val profileId = "profile-123"
        val outboundTag = "node-abc"

        val id1 = ConfigRepository.stableNodeId(profileId, outboundTag)
        val id2 = ConfigRepository.stableNodeId(profileId, outboundTag)

        assertEquals(id1, id2)
        assertTrue(id1.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))
    }

    @Test
    fun testStableNodeIdDifferentInputs() {
        val id1 = ConfigRepository.stableNodeId("profile-1", "node-a")
        val id2 = ConfigRepository.stableNodeId("profile-1", "node-b")
        val id3 = ConfigRepository.stableNodeId("profile-2", "node-a")

        assertNotEquals(id1, id2)
        assertNotEquals(id1, id3)
        assertNotEquals(id2, id3)
    }

    @Test
    fun testStableNodeIdSpecialCharacters() {
        val id = ConfigRepository.stableNodeId("profile/with/slashes", "node#with#hash")

        assertNotNull(id)
        assertTrue(id.isNotBlank())
    }

    @Test
    fun testStableNodeIdEmptyInputs() {
        val id1 = ConfigRepository.stableNodeId("", "node")
        val id2 = ConfigRepository.stableNodeId("profile", "")
        val id3 = ConfigRepository.stableNodeId("", "")

        assertNotNull(id1)
        assertNotNull(id2)
        assertNotNull(id3)
        assertNotEquals(id1, id2)
    }

    @Test
    fun testStableNodeIdUnicodeCharacters() {
        val id = ConfigRepository.stableNodeId("日本配置", "香港节点-01")

        assertNotNull(id)
        assertTrue(id.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))

        val id2 = ConfigRepository.stableNodeId("日本配置", "香港节点-01")
        assertEquals(id, id2)
    }

    @Test
    fun testStableNodeIdCacheEfficiency() {
        val profileId = "cache-test-profile"
        val outboundTag = "cache-test-node"

        val startTime = System.nanoTime()
        repeat(10000) {
            ConfigRepository.stableNodeId(profileId, outboundTag)
        }
        val duration = System.nanoTime() - startTime

        assertTrue(duration < 100_000_000L)
    }

    @Test
    fun testExtractSubscriptionUrlFromHtml() {
        val html = """
            <html>
            <body>
              <input
                type="text"
                value="https://conf1.example.com/token-123"
                readonly
                id="sub_url"
                class="link-input">
            </body>
            </html>
        """.trimIndent()

        val actual = ConfigRepository.extractSubscriptionUrlFromHtml(html)

        assertEquals("https://conf1.example.com/token-123", actual)
    }

    @Test
    fun testLooksLikeHtmlSubscriptionPageDoesNotTrustContentTypeAloneForYamlBody() {
        val result = ConfigRepository.looksLikeHtmlSubscriptionPage(
            contentType = "text/html; charset=utf-8",
            body = "mixed-port: 7890"
        )

        assertFalse(result)
    }

    @Test
    fun testLooksLikeHtmlSubscriptionPageDoesNotTrustContentTypeAloneForBase64Body() {
        val result = ConfigRepository.looksLikeHtmlSubscriptionPage(
            contentType = "text/html; charset=utf-8",
            body = "c3M6Ly9ZV1Z6TFRFeU9DMW5ZMjA9QDEyNy4wLjAuMTo0NDMjVEVTVA=="
        )

        assertFalse(result)
    }

    @Test
    fun testLooksLikeHtmlSubscriptionPageByBodyPrefix() {
        val result = ConfigRepository.looksLikeHtmlSubscriptionPage(
            contentType = null,
            body = "<!DOCTYPE html><html><body>订阅信息</body></html>"
        )

        assertTrue(result)
    }

    @Test
    fun testLooksLikeHtmlSubscriptionPageByHtmlTagPrefixEvenWhenContentTypeIsTextHtml() {
        val result = ConfigRepository.looksLikeHtmlSubscriptionPage(
            contentType = "text/html; charset=utf-8",
            body = "<html><body>订阅信息</body></html>"
        )

        assertTrue(result)
    }

    @Test
    fun testExtractSubscriptionHost() {
        val host = ConfigRepository.extractSubscriptionHost(
            "https://1.811200.xyz/api/v1/client/subscribe?token=abc"
        )

        assertEquals("1.811200.xyz", host)
    }

    @Test
    fun testPrioritizeUserAgentsWithPreferredValue() {
        val prioritized = ConfigRepository.prioritizeUserAgents("sing-box/1.13.1")

        assertEquals("sing-box/1.13.1", prioritized.first())
        assertEquals(prioritized.size, prioritized.distinct().size)
        assertTrue(prioritized.contains("ClashMeta/1.18.0"))
    }

    @Test
    fun testPrioritizeUserAgentsWithoutPreferredValue() {
        val prioritized = ConfigRepository.prioritizeUserAgents(null)

        assertEquals("ClashMeta/1.18.0", prioritized.first())
        assertTrue(prioritized.contains("sing-box/1.13.1"))
    }

    @Test
    fun testFilterCircuitBrokenUserAgents() {
        val result = ConfigRepository.filterCircuitBrokenUserAgents(
            userAgents = listOf("ClashMeta/1.18.0", "Clash/1.18.0", "sing-box/1.13.1"),
            circuitBrokenUserAgents = setOf("ClashMeta/1.18.0", "Clash/1.18.0")
        )

        assertEquals(listOf("sing-box/1.13.1"), result)
    }

    @Test
    fun testFilterCircuitBrokenUserAgentsFallsBackWhenAllBlocked() {
        val original = listOf("ClashMeta/1.18.0", "Clash/1.18.0")
        val result = ConfigRepository.filterCircuitBrokenUserAgents(
            userAgents = original,
            circuitBrokenUserAgents = original.toSet()
        )

        assertEquals(original, result)
    }

    @Test
    fun testBuildSubscriptionAttemptUserAgentsKeepsRememberedUserAgentFirst() {
        val userAgents = ConfigRepository.buildSubscriptionAttemptUserAgents(
            preferredUserAgent = "sing-box/1.13.1",
            circuitBrokenUserAgents = setOf("Clash/1.18.0")
        )

        assertEquals("sing-box/1.13.1", userAgents.first())
        assertTrue(!userAgents.contains("Clash/1.18.0"))
    }

    @Test
    fun testResolveSubscriptionUpdateBudgetSecondsFallsBackToDefaultWhenNonPositive() {
        val budgetSeconds = ConfigRepository.resolveSubscriptionUpdateBudgetSeconds(0)

        assertEquals(AppSettings().subscriptionUpdateTimeout.toLong(), budgetSeconds)
    }

    @Test
    fun testResolveSubscriptionAttemptTimeoutBudgetUsesFullRemainingBudget() {
        val budget = ConfigRepository.resolveSubscriptionAttemptTimeoutBudget(
            totalBudgetSeconds = 30,
            elapsedMs = 0
        )

        assertNotNull(budget)
        assertEquals(30L, budget?.connectTimeoutSeconds)
        assertEquals(30L, budget?.readTimeoutSeconds)
        assertEquals(30L, budget?.writeTimeoutSeconds)
        assertEquals(30L, budget?.callTimeoutSeconds)
    }

    @Test
    fun testResolveSubscriptionAttemptTimeoutBudgetRoundsUpRemainingBudget() {
        val budget = ConfigRepository.resolveSubscriptionAttemptTimeoutBudget(
            totalBudgetSeconds = 30,
            elapsedMs = 29_100
        )

        assertNotNull(budget)
        assertEquals(1L, budget?.connectTimeoutSeconds)
        assertEquals(1L, budget?.readTimeoutSeconds)
        assertEquals(1L, budget?.writeTimeoutSeconds)
        assertEquals(1L, budget?.callTimeoutSeconds)
    }

    @Test
    fun testResolveSubscriptionAttemptTimeoutBudgetReturnsNullWhenBudgetExhausted() {
        val budget = ConfigRepository.resolveSubscriptionAttemptTimeoutBudget(
            totalBudgetSeconds = 30,
            elapsedMs = 30_000
        )

        assertNull(budget)
    }

    @Test
    fun testResolveSubscriptionUpdateStageMapsKnownStages() {
        assertEquals(
            SubscriptionUpdateStage.Requesting,
            ConfigRepository.resolveSubscriptionUpdateStage("requesting")
        )
        assertEquals(
            SubscriptionUpdateStage.Parsing,
            ConfigRepository.resolveSubscriptionUpdateStage("parsing")
        )
        assertEquals(
            SubscriptionUpdateStage.Saving,
            ConfigRepository.resolveSubscriptionUpdateStage("saving")
        )
        assertEquals(
            SubscriptionUpdateStage.DnsBackground,
            ConfigRepository.resolveSubscriptionUpdateStage("dns_background")
        )
        assertNull(ConfigRepository.resolveSubscriptionUpdateStage("unknown"))
    }

    @Test
    fun testLaunchSubscriptionDnsPreResolveReturnsWithoutWaitingForResolution() {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        try {
            val startNs = System.nanoTime()
            val job = ConfigRepository.launchSubscriptionDnsPreResolve(
                scope = scope,
                profileId = "profile-1",
                enabled = true
            ) {
                started.complete(Unit)
                release.await()
                true
            }
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs)

            runBlocking { started.await() }

            assertNotNull(job)
            assertTrue(elapsedMs < 200)
            assertTrue(job?.isActive == true)

            release.complete(Unit)
            runBlocking { job?.join() }

            assertTrue(job?.isCompleted == true)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun testLaunchSubscriptionDnsPreResolveSwallowsBackgroundFailure() {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

        try {
            val job = ConfigRepository.launchSubscriptionDnsPreResolve(
                scope = scope,
                profileId = "profile-2",
                enabled = true
            ) {
                throw SocketTimeoutException("dns timeout")
            }

            runBlocking { job?.join() }

            assertNotNull(job)
            assertTrue(job?.isCompleted == true)
            assertFalse(job?.isCancelled == true)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun testBatchUpdateResultAggregatesMixedSubscriptionResults() {
        val result = BatchUpdateResult(
            successWithChanges = 1,
            successNoChanges = 2,
            failed = 1,
            details = listOf(
                SubscriptionUpdateResult.SuccessWithChanges("A", 1, 0, 3),
                SubscriptionUpdateResult.SuccessNoChanges("B", 2),
                SubscriptionUpdateResult.SuccessNoChanges("C", 2),
                SubscriptionUpdateResult.Failed("D", "timeout")
            )
        )

        assertEquals(4, result.totalCount)
        assertEquals(3, result.successCount)
        assertEquals(4, result.details.size)
    }

    @Test
    fun testResolveAppRuleOutboundModeDefaultsToProxy() {
        val resolved = ConfigRepository.resolveAppRuleOutboundMode(null)

        assertEquals(RuleSetOutboundMode.PROXY, resolved)
    }

    @Test
    fun testResolveAppRuleOutboundModeKeepsExplicitMode() {
        val resolved = ConfigRepository.resolveAppRuleOutboundMode(RuleSetOutboundMode.DIRECT)

        assertEquals(RuleSetOutboundMode.DIRECT, resolved)
    }

    @Test
    fun testShouldRecordSubscriptionNetworkFailureForConnectException() {
        assertTrue(
            ConfigRepository.shouldRecordSubscriptionNetworkFailure(
                ConnectException("failed to connect")
            )
        )
    }

    @Test
    fun testShouldRecordSubscriptionNetworkFailureForTimeoutException() {
        assertTrue(
            ConfigRepository.shouldRecordSubscriptionNetworkFailure(
                SocketTimeoutException("timeout")
            )
        )
    }

    @Test
    fun testShouldRecordSubscriptionNetworkFailureForParseError() {
        val result = ConfigRepository.shouldRecordSubscriptionNetworkFailure(
            IllegalArgumentException("parse failed")
        )

        assertTrue(!result)
    }

    @Test
    fun testShouldStopSubscriptionFallbackForHtmlInfoPage() {
        val result = ConfigRepository.shouldStopSubscriptionFallback(
            looksLikeHtmlInfoPage = true
        )

        assertTrue(result)
    }

    @Test
    fun testShouldStopSubscriptionFallbackForHttp429() {
        val result = ConfigRepository.shouldStopSubscriptionFallback(
            httpStatusCode = 429
        )

        assertTrue(result)
    }

    @Test
    fun testShouldNotStopSubscriptionFallbackForOrdinaryParseFailureOrOtherHttpErrors() {
        val parseFailureResult = ConfigRepository.shouldStopSubscriptionFallback()
        val serverErrorResult = ConfigRepository.shouldStopSubscriptionFallback(httpStatusCode = 503)

        assertFalse(parseFailureResult)
        assertFalse(serverErrorResult)
    }

    @Test
    fun testBuildBootstrapDnsRulesOnlyTargetsResolverDomains() {
        val rules = ConfigRepository.buildBootstrapDnsRules(
            serverAddresses = listOf(
                "https://dns.google/dns-query",
                "https://dns.alidns.com/dns-query",
                "https://1.1.1.1/dns-query",
                "119.29.29.29",
                "local"
            ),
            bootstrapV4Tag = "dns-bootstrap-v4",
            bootstrapV6Tag = "dns-bootstrap-v6",
            bootstrapTag = "dns-bootstrap"
        )

        assertEquals(3, rules.size)
        assertEquals(listOf("dns.google", "dns.alidns.com"), rules[0].domain)
        assertEquals(listOf("A"), rules[0].queryType)
        assertEquals("dns-bootstrap-v4", rules[0].server)
        assertNull(rules[0].outboundRaw)

        assertEquals(listOf("dns.google", "dns.alidns.com"), rules[1].domain)
        assertEquals(listOf("AAAA"), rules[1].queryType)
        assertEquals("dns-bootstrap-v6", rules[1].server)
        assertNull(rules[1].outboundRaw)

        assertEquals(listOf("dns.google", "dns.alidns.com"), rules[2].domain)
        assertEquals("dns-bootstrap", rules[2].server)
        assertNull(rules[2].outboundRaw)
    }

    @Test
    fun testBuildBootstrapDnsRulesSkipsIpAndLocalAddresses() {
        val rules = ConfigRepository.buildBootstrapDnsRules(
            serverAddresses = listOf(
                "local",
                "223.5.5.5",
                "https://1.1.1.1/dns-query",
                "https://[2606:4700:4700::1111]/dns-query"
            ),
            bootstrapV4Tag = "dns-bootstrap-v4",
            bootstrapV6Tag = "dns-bootstrap-v6",
            bootstrapTag = "dns-bootstrap"
        )

        assertTrue(rules.isEmpty())
    }

    @Test
    fun testBuildBootstrapDnsRulesStripsPortFromBareHostAddress() {
        val rules = ConfigRepository.buildBootstrapDnsRules(
            serverAddresses = listOf(
                "dns.google:853",
                "dns.alidns.com:443"
            ),
            bootstrapV4Tag = "dns-bootstrap-v4",
            bootstrapV6Tag = "dns-bootstrap-v6",
            bootstrapTag = "dns-bootstrap"
        )

        assertEquals(3, rules.size)
        assertEquals(listOf("dns.google", "dns.alidns.com"), rules[0].domain)
        assertEquals(listOf("dns.google", "dns.alidns.com"), rules[1].domain)
        assertEquals(listOf("dns.google", "dns.alidns.com"), rules[2].domain)
    }

    @Test
    fun testNormalizeLocalDnsReplacesLegacyLocalValue() {
        val normalized = ConfigRepository.normalizeLocalDns(AppSettings.LEGACY_LOCAL_DNS)

        assertEquals(AppSettings.DEFAULT_LOCAL_DNS, normalized)
    }

    @Test
    fun testNormalizeLocalDnsReplacesBlankValue() {
        val normalized = ConfigRepository.normalizeLocalDns("   ")

        assertEquals(AppSettings.DEFAULT_LOCAL_DNS, normalized)
    }

    @Test
    fun testBuildDnsResolverForDomainUrlReturnsBootstrapResolver() {
        val resolver = ConfigRepository.buildDnsResolverForAddress("https://dns.alidns.com/dns-query")

        assertNotNull(resolver)
        assertEquals("dns-bootstrap", resolver?.server)
    }

    @Test
    fun testBuildDnsResolverForIpUrlReturnsNull() {
        val resolver = ConfigRepository.buildDnsResolverForAddress("https://1.1.1.1/dns-query")

        assertNull(resolver)
    }

    @Test
    fun testBuildDnsResolverForLocalValueReturnsNull() {
        val resolver = ConfigRepository.buildDnsResolverForAddress("local")

        assertNull(resolver)
    }

    @Test
    fun testSubscriptionManagerPreservesTlsCertificateFromYamlImport() {
        val certificatePem = "-----BEGIN CERTIFICATE-----\nMIIBYAMLTEST\n-----END CERTIFICATE-----"
        val yaml = """
            proxies:
              - name: "yaml-anytls-cert"
                type: anytls
                server: anytls.example.com
                port: 443
                password: test-pass
                cert: |
                  -----BEGIN CERTIFICATE-----
                  MIIBYAMLTEST
                  -----END CERTIFICATE-----
        """.trimIndent()

        val config = subscriptionManager.parse(yaml)
        val anytls = config?.outbounds?.find { it.tag == "yaml-anytls-cert" }
        assertNotNull(anytls)
        assertEquals(certificatePem, anytls?.tls?.certificate?.trim())
    }

    @Test
    fun testSubscriptionManagerDoesNotTreatTlsCertificateAsNodeLink() {
        val yaml = """
            proxies:
              - name: "user-info-cert"
                type: anytls
                server: anytls.example.com
                port: 443
                password: test-pass
                cert: |
                  -----BEGIN CERTIFICATE-----
                  MIIBNOTUSERINFO
                  -----END CERTIFICATE-----
        """.trimIndent()

        val config = subscriptionManager.parse(yaml)

        assertNotNull(config?.outbounds?.find { it.tag == "user-info-cert" }?.tls?.certificate)
        assertEquals(1, config?.outbounds?.size)
    }

    @Test
    fun testSubscriptionManagerPreservesJsonTlsCertificateFields() {
        val certificatePem = "-----BEGIN CERTIFICATE-----\nMIIBJSONCERT\n-----END CERTIFICATE-----"
        val caPem = "-----BEGIN CERTIFICATE-----\nMIIBJSONCA\n-----END CERTIFICATE-----"
        val keyPem = "-----BEGIN PRIVATE KEY-----\nMIIBJSONKEY\n-----END PRIVATE KEY-----"
        val json = """
            {
              "outbounds": [
                {
                  "type": "anytls",
                  "tag": "json-anytls-cert",
                  "server": "json.example.com",
                  "server_port": 443,
                  "password": "test-pass",
                  "tls": {
                    "enabled": true,
                    "server_name": "edge.example.com",
                    "certificate": "-----BEGIN CERTIFICATE-----\nMIIBJSONCERT\n-----END CERTIFICATE-----",
                    "ca": "-----BEGIN CERTIFICATE-----\nMIIBJSONCA\n-----END CERTIFICATE-----",
                    "key": "-----BEGIN PRIVATE KEY-----\nMIIBJSONKEY\n-----END PRIVATE KEY-----"
                  }
                }
              ]
            }
        """.trimIndent()

        val config = subscriptionManager.parse(json)

        val anytls = config?.outbounds?.find { it.tag == "json-anytls-cert" }
        assertNotNull(anytls)
        assertEquals(certificatePem, anytls?.tls?.certificate)
        assertEquals(caPem, anytls?.tls?.ca)
        assertEquals(keyPem, anytls?.tls?.key)
    }

    @Test
    fun testSubscriptionManagerParsesYamlImportWithMultipleNodes() {
        val yaml = """
            proxies:
              - name: "hk-regression"
                type: ss
                server: hk.example.com
                port: 443
                cipher: aes-128-gcm
                password: pass-a
              - name: "us-regression"
                type: trojan
                server: us.example.com
                port: 443
                password: pass-b
        """.trimIndent()

        val config = subscriptionManager.parse(yaml)

        assertNotNull(config)
        assertEquals(2, config?.outbounds?.size)
        assertEquals(listOf("hk-regression", "us-regression"), config?.outbounds?.map { it.tag })
    }

    @Test
    fun testBuildDnsServerPreservesDomainResolverInJson() {
        val server = ConfigRepository.buildDnsServer(
            address = "https://dns.alidns.com/dns-query",
            tag = "local",
            domainStrategy = "prefer_ipv4",
            domainResolver = DomainResolveConfig(server = "dns-bootstrap")
        )

        assertEquals("local", server.tag)
        assertEquals("https", server.type)
        assertEquals("dns.alidns.com", server.server)
        assertEquals("/dns-query", server.path)
        assertNotNull(server.domainResolver)
        assertEquals("dns-bootstrap", server.domainResolver?.server)

        val json = Gson().toJson(server)
        assertTrue(json.contains("\"domain_resolver\""))
        assertTrue(json.contains("\"server\":\"dns-bootstrap\""))
    }

    @Test
    fun testBuildDynamicDnsServersDeduplicatesSameDetour() {
        val servers = ConfigRepository.buildDynamicDnsServersForTest(
            semantics = listOf(
                ConfigRepository.OutboundSemantic.RouteTag("P:HK"),
                ConfigRepository.OutboundSemantic.RouteTag("P:HK")
            ),
            remoteDnsAddr = "https://dns.google/dns-query",
            remoteStrategy = "prefer_ipv4",
            remoteResolver = DomainResolveConfig(server = "dns-bootstrap")
        )

        assertEquals(1, servers.size)
        assertEquals("P:HK", servers.first().detour)
    }

    @Test
    fun testBuildDynamicDnsServersIncludesDifferentDetours() {
        val servers = ConfigRepository.buildDynamicDnsServersForTest(
            semantics = listOf(
                ConfigRepository.OutboundSemantic.RouteTag("P:HK"),
                ConfigRepository.OutboundSemantic.RouteTag("node-tag-1")
            ),
            remoteDnsAddr = "https://dns.google/dns-query",
            remoteStrategy = "prefer_ipv4",
            remoteResolver = DomainResolveConfig(server = "dns-bootstrap")
        )

        assertEquals(2, servers.size)
        assertTrue(servers.any { it.detour == "P:HK" })
        assertTrue(servers.any { it.detour == "node-tag-1" })
    }

    @Test
    fun testBuildDynamicDnsServerTagIsStableForSameDetour() {
        val tag1 = ConfigRepository.buildDynamicDnsServerTag("P:HK")
        val tag2 = ConfigRepository.buildDynamicDnsServerTag("P:HK")

        assertEquals(tag1, tag2)
        assertTrue(tag1.startsWith("dns-remote-"))
    }

    @Test
    fun testBuildDynamicDnsServerTagDiffersForDifferentDetours() {
        val tag1 = ConfigRepository.buildDynamicDnsServerTag("P:HK")
        val tag2 = ConfigRepository.buildDynamicDnsServerTag("P/HK")

        assertNotEquals(tag1, tag2)
    }

    @Test
    fun testBuildDynamicDnsServerUsesGivenDetour() {
        val server = ConfigRepository.buildDynamicRemoteDnsServerForTest(
            detourTag = "P:HK",
            remoteDnsAddr = "https://dns.google/dns-query",
            remoteStrategy = "prefer_ipv4",
            remoteResolver = DomainResolveConfig(server = "dns-bootstrap")
        )

        assertEquals("P:HK", server.detour)
        assertEquals("https", server.type)
        assertEquals("dns.google", server.server)
        assertEquals("dns-bootstrap", server.domainResolver?.server)
    }

    @Test
    fun testDnsServerTagForRouteTagUsesDynamicServerWhenFakeDnsDisabled() {
        val serverTag = ConfigRepository.dnsServerTagForSemanticForTest(
            semantic = ConfigRepository.OutboundSemantic.RouteTag("P:HK"),
            fakeDnsEnabled = false
        )

        assertEquals(ConfigRepository.buildDynamicDnsServerTag("P:HK"), serverTag)
    }

    @Test
    fun testDnsServerTagForRouteTagUsesDynamicServerWhenFakeDnsEnabled() {
        val serverTag = ConfigRepository.dnsServerTagForSemanticForTest(
            semantic = ConfigRepository.OutboundSemantic.RouteTag("P:HK"),
            fakeDnsEnabled = true
        )

        assertEquals(ConfigRepository.buildDynamicDnsServerTag("P:HK"), serverTag)
    }

    @Test
    fun testDnsServerTagForFallbackProxyUsesProxyServer() {
        val serverTag = ConfigRepository.dnsServerTagForSemanticForTest(
            semantic = ConfigRepository.OutboundSemantic.FallbackProxy("PROXY"),
            fakeDnsEnabled = false
        )

        assertEquals("remote", serverTag)
    }

    @Test
    fun testDnsServerTagForFakeIpExcludeDomainUsesDynamicServerWhenFakeDnsEnabled() {
        val serverTag = ConfigRepository.dnsServerTagForSemanticForTest(
            semantic = ConfigRepository.OutboundSemantic.RouteTag("P:HK"),
            fakeDnsEnabled = true
        )

        assertEquals(ConfigRepository.buildDynamicDnsServerTag("P:HK"), serverTag)
    }

    @Test
    fun testResolveRouteModeForRuleSetUsesProxyDefault() {
        val resolved = ConfigRepository.resolveRouteModeForRuleSetForTest(
            RuleSet(
                tag = "geo-test",
                type = RuleSetType.LOCAL,
                path = "/tmp/geo.srs",
                outboundMode = null
            )
        )

        assertEquals(RuleSetOutboundMode.PROXY, resolved)
    }

    @Test
    fun testResolveRouteModeForAppGroupUsesDirectDefault() {
        val resolved = ConfigRepository.resolveRouteModeForAppGroupForTest(
            AppGroup(name = "group", outboundMode = null)
        )

        assertEquals(RuleSetOutboundMode.DIRECT, resolved)
    }

    @Test
    fun testResolveRouteModeForCustomRuleUsesLegacyOutboundDefault() {
        val resolved = ConfigRepository.resolveRouteModeForCustomRuleForTest(
            CustomRule(
                name = "rule",
                type = RuleType.DOMAIN,
                value = "example.com",
                outbound = OutboundTag.BLOCK,
                outboundMode = null
            )
        )

        assertEquals(RuleSetOutboundMode.BLOCK, resolved)
    }

    @Test
    fun testResolveOutboundSemanticDirect() {
        val semantic = ConfigRepository.resolveOutboundSemanticForTest(
            ConfigRepository.Companion.OutboundSemanticTestInput(
                mode = RuleSetOutboundMode.DIRECT,
                value = null,
                selectorTag = "PROXY",
                outbounds = emptyList(),
                profiles = emptyList(),
                nodeTagResolver = { null }
            )
        )

        assertEquals(ConfigRepository.OutboundSemantic.Direct, semantic)
    }

    @Test
    fun testResolveOutboundSemanticBlock() {
        val semantic = ConfigRepository.resolveOutboundSemanticForTest(
            ConfigRepository.Companion.OutboundSemanticTestInput(
                mode = RuleSetOutboundMode.BLOCK,
                value = null,
                selectorTag = "PROXY",
                outbounds = emptyList(),
                profiles = emptyList(),
                nodeTagResolver = { null }
            )
        )

        assertEquals(ConfigRepository.OutboundSemantic.Block, semantic)
    }

    @Test
    fun testResolveOutboundSemanticProxy() {
        val semantic = ConfigRepository.resolveOutboundSemanticForTest(
            ConfigRepository.Companion.OutboundSemanticTestInput(
                mode = RuleSetOutboundMode.PROXY,
                value = null,
                selectorTag = "PROXY",
                outbounds = emptyList(),
                profiles = emptyList(),
                nodeTagResolver = { null }
            )
        )

        assertEquals(ConfigRepository.OutboundSemantic.Proxy, semantic)
    }

    @Test
    fun testResolveOutboundSemanticNodeValid() {
        val semantic = ConfigRepository.resolveOutboundSemanticForTest(
            ConfigRepository.Companion.OutboundSemanticTestInput(
                mode = RuleSetOutboundMode.NODE,
                value = "node-id-1",
                selectorTag = "PROXY",
                outbounds = emptyList(),
                profiles = emptyList(),
                nodeTagResolver = { id -> if (id == "node-id-1") "node-tag-1" else null }
            )
        )

        assertEquals(ConfigRepository.OutboundSemantic.RouteTag("node-tag-1"), semantic)
    }

    @Test
    fun testResolveOutboundSemanticNodeInvalid() {
        val semantic = ConfigRepository.resolveOutboundSemanticForTest(
            ConfigRepository.Companion.OutboundSemanticTestInput(
                mode = RuleSetOutboundMode.NODE,
                value = "missing-node",
                selectorTag = "PROXY",
                outbounds = emptyList(),
                profiles = emptyList(),
                nodeTagResolver = { null }
            )
        )

        assertEquals(ConfigRepository.OutboundSemantic.FallbackProxy("PROXY"), semantic)
    }

    @Test
    fun testResolveOutboundSemanticProfileValid() {
        val semantic = ConfigRepository.resolveOutboundSemanticForTest(
            ConfigRepository.Companion.OutboundSemanticTestInput(
                mode = RuleSetOutboundMode.PROFILE,
                value = "profile-1",
                selectorTag = "PROXY",
                outbounds = listOf(com.kunk.singbox.model.Outbound(tag = "P:HK", type = "selector")),
                profiles = listOf(
                    com.kunk.singbox.database.entity.ProfileEntity(
                        id = "profile-1",
                        name = "HK",
                        type = com.kunk.singbox.model.ProfileType.Subscription,
                        url = "",
                        lastUpdated = 0L,
                        enabled = true
                    )
                ),
                nodeTagResolver = { null }
            )
        )

        assertEquals(ConfigRepository.OutboundSemantic.RouteTag("P:HK"), semantic)
    }

    @Test
    fun testResolveOutboundSemanticProfileInvalid() {
        val semantic = ConfigRepository.resolveOutboundSemanticForTest(
            ConfigRepository.Companion.OutboundSemanticTestInput(
                mode = RuleSetOutboundMode.PROFILE,
                value = "missing-profile",
                selectorTag = "PROXY",
                outbounds = emptyList(),
                profiles = emptyList(),
                nodeTagResolver = { null }
            )
        )

        assertEquals(ConfigRepository.OutboundSemantic.FallbackProxy("PROXY"), semantic)
    }

    @Test
    fun testResolveProfileSelectorDefaultPrefersLowestLatencyOverRememberedNode() {
        val defaultTag = ConfigRepository.resolveProfileSelectorDefault(
            nodeIds = listOf("node-1", "node-2", "node-3"),
            nodeTagMap = mapOf(
                "node-1" to "tag-a",
                "node-2" to "tag-b",
                "node-3" to "tag-c"
            ),
            rememberedNodeId = "node-2",
            savedNodeLatencies = mapOf(
                "node-1" to 20L,
                "node-2" to 10L,
                "node-3" to 5L
            )
        )

        assertEquals("tag-c", defaultTag)
    }

    @Test
    fun testResolveProfileSelectorDefaultIgnoresRememberedNodeOutsideCurrentProfile() {
        val defaultTag = ConfigRepository.resolveProfileSelectorDefault(
            nodeIds = listOf("node-1", "node-2"),
            nodeTagMap = mapOf(
                "node-1" to "tag-a",
                "node-2" to "tag-b",
                "node-3" to "tag-c"
            ),
            rememberedNodeId = "node-3",
            savedNodeLatencies = mapOf(
                "node-1" to 80L,
                "node-2" to 30L,
                "node-3" to 5L
            )
        )

        assertEquals("tag-b", defaultTag)
    }

    @Test
    fun testResolveProfileSelectorDefaultFallsBackToRememberedNodeWhenLatencyUnavailable() {
        val defaultTag = ConfigRepository.resolveProfileSelectorDefault(
            nodeIds = listOf("node-1", "node-2", "node-3"),
            nodeTagMap = mapOf(
                "node-1" to "tag-a",
                "node-2" to "tag-b",
                "node-3" to "tag-c"
            ),
            rememberedNodeId = "node-2",
            savedNodeLatencies = mapOf(
                "node-1" to 0L,
                "node-3" to -1L
            )
        )

        assertEquals("tag-b", defaultTag)
    }

    @Test
    fun testResolveProfileSelectorDefaultUsesLowestPositiveLatency() {
        val defaultTag = ConfigRepository.resolveProfileSelectorDefault(
            nodeIds = listOf("node-1", "node-2", "node-3"),
            nodeTagMap = mapOf(
                "node-1" to "tag-a",
                "node-2" to "tag-b",
                "node-3" to "tag-c"
            ),
            rememberedNodeId = null,
            savedNodeLatencies = mapOf(
                "node-1" to 120L,
                "node-2" to 45L,
                "node-3" to 60L
            )
        )

        assertEquals("tag-b", defaultTag)
    }

    @Test
    fun testResolveProfileSelectorDefaultFallsBackToFirstTag() {
        val defaultTag = ConfigRepository.resolveProfileSelectorDefault(
            nodeIds = listOf("node-1", "node-2"),
            nodeTagMap = mapOf(
                "node-1" to "tag-a",
                "node-2" to "tag-b"
            ),
            rememberedNodeId = null,
            savedNodeLatencies = mapOf(
                "node-1" to 0L,
                "node-2" to -1L
            )
        )

        assertEquals("tag-a", defaultTag)
    }

    @Test
    fun testBuildProfileRouteGroupOutboundsCreatesNestedAutoStructure() {
        val outbounds = ConfigRepository.buildProfileRouteGroupOutboundsForTest(
            groupTag = "P:HK",
            nodeTags = listOf("node-a", "node-b")
        )

        assertEquals(2, outbounds.size)

        val autoGroup = outbounds[0]
        assertEquals("urltest", autoGroup.type)
        assertEquals("P:HK#AUTO", autoGroup.tag)
        assertEquals(listOf("node-a", "node-b"), autoGroup.outbounds)
        assertNull(autoGroup.default)
        assertEquals("https://www.gstatic.com/generate_204", autoGroup.url)
        assertEquals("10m", autoGroup.interval)
        assertEquals(50, autoGroup.tolerance)

        val outerGroup = outbounds[1]
        assertEquals("selector", outerGroup.type)
        assertEquals("P:HK", outerGroup.tag)
        assertEquals(listOf("P:HK#AUTO", "PROXY"), outerGroup.outbounds)
        assertEquals("P:HK#AUTO", outerGroup.default)
    }

    @Test
    fun testApplySelectorSafeOutboundsKeepsUrlTestDefaultNull() {
        val safeOutbounds = ConfigRepository.applySelectorSafeOutboundsForTest(
            listOf(
                Outbound(
                    type = "urltest",
                    tag = "P:HK#AUTO",
                    outbounds = listOf("node-a", "missing-node"),
                    default = "node-a"
                ),
                Outbound(type = "direct", tag = "direct"),
                Outbound(type = "shadowsocks", tag = "node-a"),
                Outbound(
                    type = "selector",
                    tag = "P:HK",
                    outbounds = listOf("P:HK#AUTO", "missing-selector-ref"),
                    default = "missing-selector-ref"
                )
            )
        )

        val autoGroup = safeOutbounds.first { it.tag == "P:HK#AUTO" }
        assertEquals(listOf("node-a"), autoGroup.outbounds)
        assertNull(autoGroup.default)

        val selectorGroup = safeOutbounds.first { it.tag == "P:HK" }
        assertEquals(listOf("P:HK#AUTO"), selectorGroup.outbounds)
        assertEquals("P:HK#AUTO", selectorGroup.default)
    }

    @Test
    fun testBuildAppRoutingRulesUsesSemanticRejectForBlockRule() {
        val routeRule = ConfigRepository.toRouteRuleForTest(
            ConfigRepository.OutboundSemantic.Block,
            "PROXY"
        )

        assertEquals("reject", routeRule.action)
        assertNull(routeRule.outbound)
    }

    @Test
    fun testAppliedRemoteRuleSetFilterIncludesEnabledRemoteRuleSet() {
        val ruleSet = RuleSet(tag = "remote-enabled", type = RuleSetType.REMOTE, enabled = true)

        val filtered = invokeAppliedRemoteRuleSetFilter(
            ruleSets = listOf(ruleSet),
            validRuleSets = listOf(RuleSetConfig(tag = "remote-enabled"))
        )

        assertEquals(listOf("remote-enabled"), filtered.map { it.tag })
    }

    @Test
    fun testAppliedRemoteRuleSetFilterExcludesDisabledRemoteRuleSet() {
        val ruleSet = RuleSet(tag = "remote-disabled", type = RuleSetType.REMOTE, enabled = false)

        val filtered = invokeAppliedRemoteRuleSetFilter(
            ruleSets = listOf(ruleSet),
            validRuleSets = listOf(RuleSetConfig(tag = "remote-disabled"))
        )

        assertTrue(filtered.isEmpty())
    }

    @Test
    fun testAppliedRemoteRuleSetFilterExcludesRemoteRuleSetOutsideValidTags() {
        val ruleSet = RuleSet(tag = "remote-missing", type = RuleSetType.REMOTE, enabled = true)

        val filtered = invokeAppliedRemoteRuleSetFilter(
            ruleSets = listOf(ruleSet),
            validRuleSets = listOf(RuleSetConfig(tag = "another-tag"))
        )

        assertTrue(filtered.isEmpty())
    }

    @Test
    fun testAppliedRemoteRuleSetFilterExcludesLocalRuleSet() {
        val ruleSet = RuleSet(
            tag = "local-enabled",
            type = RuleSetType.LOCAL,
            path = "/tmp/local-enabled.srs",
            enabled = true
        )

        val filtered = invokeAppliedRemoteRuleSetFilter(
            ruleSets = listOf(ruleSet),
            validRuleSets = listOf(RuleSetConfig(tag = "local-enabled"))
        )

        assertTrue(filtered.isEmpty())
    }
}
