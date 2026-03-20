package com.kunk.singbox.repository

import com.google.gson.Gson
import com.kunk.singbox.model.AppSettings
import com.kunk.singbox.model.DomainResolveConfig
import com.kunk.singbox.model.RuleSetOutboundMode
import com.kunk.singbox.utils.parser.Base64Parser
import com.kunk.singbox.utils.parser.ClashYamlParser
import com.kunk.singbox.utils.parser.NodeLinkParser
import com.kunk.singbox.utils.parser.SingBoxParser
import com.kunk.singbox.utils.parser.SubscriptionManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.SocketTimeoutException

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
    fun testLooksLikeHtmlSubscriptionPageByContentType() {
        val result = ConfigRepository.looksLikeHtmlSubscriptionPage(
            contentType = "text/html; charset=utf-8",
            body = "mixed-port: 7890"
        )

        assertTrue(result)
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
    fun testBuildDnsServerPreservesDetourAndResolverForRemoteDns() {
        val server = ConfigRepository.buildDnsServer(
            address = "https://dns.google/dns-query",
            tag = "remote",
            detour = "PROXY",
            domainStrategy = "prefer_ipv4",
            domainResolver = DomainResolveConfig(server = "dns-bootstrap")
        )

        assertEquals("remote", server.tag)
        assertEquals("PROXY", server.detour)
        assertEquals("dns-bootstrap", server.domainResolver?.server)
    }
}
