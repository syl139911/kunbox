package com.kunk.singbox.utils.parser

import com.google.gson.Gson
import com.kunk.singbox.repository.config.OutboundFixer
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * NodeLinkParser 单元测试
 * 覆盖所有支持的协议链接解析
 */
@Suppress("LargeClass")
class NodeLinkParserTest {

    private lateinit var parser: NodeLinkParser
    private val gson = Gson()

    @Before
    fun setUp() {
        parser = NodeLinkParser(gson)
    }

    // ==================== Shadowsocks ====================

    @Test
    fun testParseShadowsocksSIP002() {
        // SIP002 格式: ss://BASE64(method:password)@server:port#name
        val link = "ss://YWVzLTI1Ni1nY206cGFzc3dvcmQ=@1.2.3.4:8388#MySSNode"
        val outbound = parser.parse(link)

        assertNotNull(outbound)
        assertEquals("shadowsocks", outbound?.type)
        assertEquals("MySSNode", outbound?.tag)
        assertEquals("1.2.3.4", outbound?.server)
        assertEquals(8388, outbound?.serverPort)
        assertEquals("aes-256-gcm", outbound?.method)
        assertEquals("password", outbound?.password)
    }

    @Test
    fun testParseShadowsocksLegacy() {
        // Legacy 格式: ss://BASE64(method:password@server:port)#name
        val link = "ss://YWVzLTI1Ni1nY206cGFzc3dvcmRAMS4yLjMuNDo4Mzg4#LegacyNode"
        val outbound = parser.parse(link)

        assertNotNull(outbound)
        assertEquals("shadowsocks", outbound?.type)
        assertEquals("LegacyNode", outbound?.tag)
        assertEquals("1.2.3.4", outbound?.server)
        assertEquals(8388, outbound?.serverPort)
    }

    @Test
    fun testParseShadowsocksWithPlugin() {
        val link = "ss://YWVzLTI1Ni1nY206cGFzc3dvcmQ=@1.2.3.4:8388?plugin=v2ray-plugin%3Bmode%3Dwebsocket#PluginNode"
        val outbound = parser.parse(link)

        assertNotNull(outbound)
        assertEquals("v2ray-plugin", outbound?.plugin)
        assertEquals("mode=websocket", outbound?.pluginOpts)
    }

    @Test
    fun testParseShadowsocksIPv6() {
        val link = "ss://YWVzLTI1Ni1nY206cGFzcw==@[2001:db8::1]:8388#IPv6Node"
        val outbound = parser.parse(link)

        assertNotNull(outbound)
        assertEquals("2001:db8::1", outbound?.server)
        assertEquals(8388, outbound?.serverPort)
    }

    @Test
    fun testParseShadowsocksUrlEncodedName() {
        val link = "ss://YWVzLTI1Ni1nY206cGFzcw==@1.2.3.4:8388#%E6%97%A5%E6%9C%AC%E8%8A%82%E7%82%B9"
        val outbound = parser.parse(link)

        assertNotNull(outbound)
        assertEquals("日本节点", outbound?.tag)
    }

    @Test
    fun testParseShadowsocksUrlEncodedPassword() {
        // 非 Base64 格式，密码中包含特殊字符 : 被 URL 编码为 %3A
        val link = "ss://aes-256-gcm:pass%3Aword@1.2.3.4:8388#UrlEncodedPwd"
        val outbound = parser.parse(link)

        assertNotNull(outbound)
        assertEquals("shadowsocks", outbound?.type)
        assertEquals("UrlEncodedPwd", outbound?.tag)
        assertEquals("aes-256-gcm", outbound?.method)
        assertEquals("pass:word", outbound?.password)
    }

    // ==================== VMess ====================

    @Test
    fun testParseVMessHttpTransportKeepsHttp() {
        val vmessJson = """
            {
              "v":"2",
              "ps":"http vmess",
              "add":"18.225.57.7",
              "port":"32721",
              "id":"c31a559b-8285-4b11-db99-d1edfc2b2b70",
              "aid":"0",
              "net":"http",
              "host":"",
              "path":"",
              "tls":""
            }
        """.trimIndent()
        val encoded = java.util.Base64.getEncoder().encodeToString(vmessJson.toByteArray())
        val link = "vmess://$encoded"

        val outbound = parser.parse(link)

        assertNotNull(outbound)
        assertEquals("vmess", outbound?.type)
        assertNotNull(outbound?.transport)
        assertEquals("http", outbound?.transport?.type)
    }

    @Test
    fun testParseVMessHttpTransportPreservesHostAndPath() {
        val vmessJson = """
            {
              "v":"2",
              "ps":"http with host",
              "add":"vmess.example.com",
              "port":"80",
              "id":"uuid-2000",
              "aid":"0",
              "net":"http",
              "host":"cdn.example.com",
              "path":"/health",
              "tls":""
            }
        """.trimIndent()
        val encoded = java.util.Base64.getEncoder().encodeToString(vmessJson.toByteArray())
        val link = "vmess://$encoded"

        val outbound = parser.parse(link)

        assertNotNull(outbound)
        assertEquals("http", outbound?.transport?.type)
        assertEquals(listOf("cdn.example.com"), outbound?.transport?.host)
        assertEquals("/health", outbound?.transport?.path)
    }

    @Test
    fun testParseVMessUserHttpLinkKeepsHttpTransport() {
        val link = "vmess://" +
            "eyJhZGQiOiIxOC4yMjUuNTcuNyIsImFpZCI6IjAiLCJob3N0IjoiIiwiaWQiOiJjMzFhNTU5Yi04" +
            "Mjg1LTRiMTEtZGI5OS1kMWVkZmMyYjJiNzAiLCJuZXQiOiJodHRwIiwicGF0aCI6IiIsInBv" +
            "cnQiOiIzMjcyMSIsInBzIjoiVVMg8J+HuvCfh7ggIC1cdTAwM2UgIOe+juWbvS3ljp/nlJ8t" +
            "5Lqa6ams6YCKLeWkp+W4puWuvS0zMjcyMSIsInNjeSI6ImF1dG8iLCJzbmkiOiIiLCJ0" +
            "bHMiOiIiLCJ0eXBlIjoibm9uZSIsInYiOiIyIn0="

        val outbound = parser.parse(link)

        assertNotNull(outbound)
        assertEquals("vmess", outbound?.type)
        assertEquals("http", outbound?.transport?.type)
    }

    @Test
    fun testParseVMessBasic() {
        val vmessJson = """{"v":"2","ps":"VMess Node","add":"vmess.example.com","port":"443","id":"uuid-1234","aid":"0","net":"ws","type":"none","host":"","path":"/path","tls":"tls"}"""
        val encoded = java.util.Base64.getEncoder().encodeToString(vmessJson.toByteArray())
        val link = "vmess://$encoded"

        val outbound = parser.parse(link)

        assertNotNull(outbound)
        assertEquals("vmess", outbound?.type)
        assertEquals("VMess Node", outbound?.tag)
        assertEquals("vmess.example.com", outbound?.server)
        assertEquals(443, outbound?.serverPort)
        assertEquals("uuid-1234", outbound?.uuid)
        assertNotNull(outbound?.tls)
        assertEquals(true, outbound?.tls?.enabled)
        assertNotNull(outbound?.transport)
        assertEquals("ws", outbound?.transport?.type)
        assertEquals("/path", outbound?.transport?.path)
    }

    @Test
    fun testParseVMessWithGrpc() {
        val vmessJson = """{"v":"2","ps":"gRPC Node","add":"grpc.example.com","port":"443","id":"uuid-5678","aid":"0","net":"grpc","path":"grpc-service","tls":"tls"}"""
        val encoded = java.util.Base64.getEncoder().encodeToString(vmessJson.toByteArray())
        val link = "vmess://$encoded"

        val outbound = parser.parse(link)

        assertNotNull(outbound)
        assertEquals("grpc", outbound?.transport?.type)
        assertEquals("grpc-service", outbound?.transport?.serviceName)
    }

    @Test
    fun testParseVMessWithXhttpExtendedParams() {
        val vmessJson = """
            {
              "v":"2",
              "ps":"xhttp vmess",
              "add":"vmess.example.com",
              "port":"443",
              "id":"uuid-1000",
              "aid":"0",
              "net":"xhttp",
              "host":"h1.example.com,h2.example.com",
              "path":"/xhttp",
              "mode":"auto",
              "xPaddingBytes":"100-200",
              "scMaxEachPostBytes":"1048576",
              "scMinPostsIntervalMs":"30",
              "scMaxBufferedPosts":"64",
              "noGRPCHeader":"1",
              "noSSEHeader":"true",
              "tls":"tls",
              "sni":"vmess.example.com"
            }
        """.trimIndent()
        val encoded = java.util.Base64.getEncoder().encodeToString(vmessJson.toByteArray())
        val link = "vmess://$encoded"

        val outbound = parser.parse(link)

        assertNotNull(outbound)
        assertEquals("xhttp", outbound?.transport?.type)
        assertEquals(listOf("h1.example.com", "h2.example.com"), outbound?.transport?.host)
        assertEquals("/xhttp", outbound?.transport?.path)
        assertEquals("auto", outbound?.transport?.mode)
        assertEquals("100-200", outbound?.transport?.xPaddingBytes)
        assertEquals(1048576L, outbound?.transport?.scMaxEachPostBytes)
        assertEquals(30L, outbound?.transport?.scMinPostsIntervalMs)
        assertEquals(64L, outbound?.transport?.scMaxBufferedPosts)
        assertEquals(true, outbound?.transport?.noGRPCHeader)
        assertEquals(true, outbound?.transport?.noSSEHeader)
    }

    // ==================== VLESS ====================

    @Test
    fun testParseVLessWebSocketEarlyDataFromPathQuery() {
        val link =
            "vless://uuid@125.140.145.188:21272?type=ws&encryption=none&security=tls" +
                "&sni=lp3.0528.linkpc.net&fp=random&allowInsecure=1&host=lp3.0528.linkpc.net" +
                "&path=%2F%3Fed%3D2560#KR_1"

        val outbound = parser.parse(link)

        assertNotNull(outbound)
        assertEquals("vless", outbound?.type)
        assertEquals("ws", outbound?.transport?.type)
        assertEquals("/", outbound?.transport?.path)
        assertEquals(2560, outbound?.transport?.maxEarlyData)
        assertEquals("Sec-WebSocket-Protocol", outbound?.transport?.earlyDataHeaderName)
        assertEquals("random", outbound?.tls?.utls?.fingerprint)
        assertEquals(true, outbound?.tls?.insecure)
        assertEquals("lp3.0528.linkpc.net", outbound?.tls?.serverName)
    }

    @Test
    fun testParseVLessBasic() {
        val link = "vless://uuid-1234@vless.example.com:443?security=tls&sni=vless.example.com&type=ws&path=%2Fpath#VLESSNode"
        val outbound = parser.parse(link)

        assertNotNull(outbound)
        assertEquals("vless", outbound?.type)
        assertEquals("VLESSNode", outbound?.tag)
        assertEquals("vless.example.com", outbound?.server)
        assertEquals(443, outbound?.serverPort)
        assertEquals("uuid-1234", outbound?.uuid)
        assertNotNull(outbound?.tls)
        assertEquals(true, outbound?.tls?.enabled)
        assertEquals("vless.example.com", outbound?.tls?.serverName)
        assertNotNull(outbound?.transport)
        assertEquals("ws", outbound?.transport?.type)
        assertEquals("/path", outbound?.transport?.path)
    }

    @Test
    fun testParseVLessWithReality() {
        val link = "vless://uuid@reality.example.com:443?security=reality&sni=www.microsoft.com&pbk=public-key-123&sid=short-id&fp=chrome&type=tcp#RealityNode"
        val outbound = parser.parse(link)

        assertNotNull(outbound)
        assertEquals("vless", outbound?.type)
        assertEquals("RealityNode", outbound?.tag)
        assertNotNull(outbound?.tls)
        assertEquals(true, outbound?.tls?.enabled)
        assertNotNull(outbound?.tls?.reality)
        assertEquals(true, outbound?.tls?.reality?.enabled)
        assertEquals("public-key-123", outbound?.tls?.reality?.publicKey)
        assertEquals("short-id", outbound?.tls?.reality?.shortId)
        assertEquals("chrome", outbound?.tls?.utls?.fingerprint)
    }

    @Test
    fun testParseVLessWithFlow() {
        val link = "vless://uuid@xtls.example.com:443?security=tls&flow=xtls-rprx-vision&type=tcp#XTLSNode"
        val outbound = parser.parse(link)

        assertNotNull(outbound)
        assertEquals("xtls-rprx-vision", outbound?.flow)
    }

    @Test
    fun testParseVLessWithGrpc() {
        val link = "vless://uuid@grpc.example.com:443?security=tls&type=grpc&serviceName=my-service#gRPCNode"
        val outbound = parser.parse(link)

        assertNotNull(outbound)
        assertNotNull(outbound?.transport)
        assertEquals("grpc", outbound?.transport?.type)
        assertEquals("my-service", outbound?.transport?.serviceName)
    }

    @Test
    fun testParseVLessWithXhttpExtendedParams() {
        val link =
            "vless://uuid@xhttp.example.com:443?security=tls&type=xhttp&host=h1.example.com,h2.example.com" +
                "&path=%2Fxhttp&mode=auto&xPaddingBytes=100-200&scMaxEachPostBytes=1048576" +
                "&scMinPostsIntervalMs=30&scMaxBufferedPosts=64&noGRPCHeader=1&noSSEHeader=true#XHTTPNode"

        val outbound = parser.parse(link)

        assertNotNull(outbound)
        assertEquals("xhttp", outbound?.transport?.type)
        assertEquals(listOf("h1.example.com", "h2.example.com"), outbound?.transport?.host)
        assertEquals("/xhttp", outbound?.transport?.path)
        assertEquals("auto", outbound?.transport?.mode)
        assertEquals("100-200", outbound?.transport?.xPaddingBytes)
        assertEquals(1048576L, outbound?.transport?.scMaxEachPostBytes)
        assertEquals(30L, outbound?.transport?.scMinPostsIntervalMs)
        assertEquals(64L, outbound?.transport?.scMaxBufferedPosts)
        assertEquals(true, outbound?.transport?.noGRPCHeader)
        assertEquals(true, outbound?.transport?.noSSEHeader)
    }

    @Test
    fun testParseVLessWithCustomEncryptionAndXhttp() {
        val link =
            "vless://uuid@xhttp.example.com:443?security=reality&sni=apple.com&pbk=public-key-123" +
                "&sid=short-id-123&fp=chrome&flow=xtls-rprx-vision&type=xhttp" +
                "&path=node-xh&mode=auto&encryption=mlkem768x25519plus.native.0rtt.sample#EncryptedXHTTPNode"

        val outbound = parser.parse(link)

        assertNotNull(outbound)
        assertEquals("vless", outbound?.type)
        assertEquals("xhttp", outbound?.transport?.type)
        assertEquals("xtls-rprx-vision", outbound?.flow)
        assertEquals("mlkem768x25519plus.native.0rtt.sample", outbound?.encryption)
        assertEquals("apple.com", outbound?.tls?.serverName)
    }

    // ==================== Trojan ====================

    @Test
    fun testParseTrojanBasic() {
        val link = "trojan://password123@trojan.example.com:443?sni=trojan.example.com#TrojanNode"
        val outbound = parser.parse(link)

        assertNotNull(outbound)
        assertEquals("trojan", outbound?.type)
        assertEquals("TrojanNode", outbound?.tag)
        assertEquals("trojan.example.com", outbound?.server)
        assertEquals(443, outbound?.serverPort)
        assertEquals("password123", outbound?.password)
        assertNotNull(outbound?.tls)
        assertEquals(true, outbound?.tls?.enabled)
    }

    @Test
    fun testParseTrojanWithDefaultPort() {
        val link = "trojan://password@trojan.example.com#DefaultPort"
        val outbound = parser.parse(link)

        assertNotNull(outbound)
        assertEquals(443, outbound?.serverPort)
    }

    @Test
    fun testParseTrojanWithXhttpExtendedParams() {
        val link =
            "trojan://password@trojan.example.com:443?security=tls&type=xhttp&host=h1.example.com,h2.example.com" +
                "&path=%2Fxhttp&mode=auto&xPaddingBytes=100-200&scMaxEachPostBytes=1048576" +
                "&scMinPostsIntervalMs=30&scMaxBufferedPosts=64&noGRPCHeader=1&noSSEHeader=true" +
                "&sni=sni.example.com&fp=chrome&alpn=h2,http%2F1.1&allowInsecure=1#TrojanXHTTP"

        val outbound = parser.parse(link)

        assertNotNull(outbound)
        assertEquals("trojan", outbound?.type)
        assertEquals("TrojanXHTTP", outbound?.tag)
        assertEquals("trojan.example.com", outbound?.server)
        assertEquals(443, outbound?.serverPort)
        assertEquals("password", outbound?.password)
        assertEquals(true, outbound?.tls?.enabled)
        assertEquals("sni.example.com", outbound?.tls?.serverName)
        assertEquals(true, outbound?.tls?.insecure)
        assertEquals("chrome", outbound?.tls?.utls?.fingerprint)
        assertEquals(listOf("h2", "http/1.1"), outbound?.tls?.alpn)

        assertEquals("xhttp", outbound?.transport?.type)
        assertEquals("/xhttp", outbound?.transport?.path)
        assertEquals(listOf("h1.example.com", "h2.example.com"), outbound?.transport?.host)
        assertEquals("auto", outbound?.transport?.mode)
        assertEquals("100-200", outbound?.transport?.xPaddingBytes)
        assertEquals(1048576L, outbound?.transport?.scMaxEachPostBytes)
        assertEquals(30L, outbound?.transport?.scMinPostsIntervalMs)
        assertEquals(64L, outbound?.transport?.scMaxBufferedPosts)
        assertEquals(true, outbound?.transport?.noGRPCHeader)
        assertEquals(true, outbound?.transport?.noSSEHeader)
    }

    // ==================== Hysteria2 ====================

    @Test
    fun testParseHysteria2Basic() {
        val link = "hysteria2://password@hy2.example.com:443?sni=hy2.example.com#Hy2Node"
        val outbound = parser.parse(link)

        assertNotNull(outbound)
        assertEquals("hysteria2", outbound?.type)
        assertEquals("Hy2Node", outbound?.tag)
        assertEquals("hy2.example.com", outbound?.server)
        assertEquals(443, outbound?.serverPort)
        assertEquals("password", outbound?.password)
    }

    @Test
    fun testParseHysteria2WithBandwidth() {
        val link = "hysteria2://password@hy2.example.com:443?up=100&down=200#BandwidthNode"
        val outbound = parser.parse(link)

        assertNotNull(outbound)
        assertEquals(100, outbound?.upMbps)
        assertEquals(200, outbound?.downMbps)
    }

    @Test
    fun testParseHysteria2WithoutBandwidthKeepsNull() {
        val link = "hysteria2://password@hy2.example.com:443?sni=hy2.example.com#NoBandwidthNode"
        val outbound = parser.parse(link)

        assertNotNull(outbound)
        assertEquals(null, outbound?.upMbps)
        assertEquals(null, outbound?.downMbps)
    }

    @Test
    fun testParseHysteria2WithExportedBandwidthParamNames() {
        val link = "hysteria2://password@hy2.example.com:443?upmbps=80&downmbps=160#ExportedBandwidthNode"
        val outbound = parser.parse(link)

        assertNotNull(outbound)
        assertEquals(80, outbound?.upMbps)
        assertEquals(160, outbound?.downMbps)
    }

    @Test
    fun testParseHy2ShortScheme() {
        val link = "hy2://password@hy2.example.com:443#ShortScheme"
        val outbound = parser.parse(link)

        assertNotNull(outbound)
        assertEquals("hysteria2", outbound?.type)
        assertEquals("ShortScheme", outbound?.tag)
    }

    @Test
    fun testParseHysteria2WithObfs() {
        val link = "hysteria2://password@hy2.example.com:443?obfs=salamander&obfs-password=obfs-pass#ObfsNode"
        val outbound = parser.parse(link)

        assertNotNull(outbound)
        assertNotNull(outbound?.obfs)
        assertEquals("salamander", outbound?.obfs?.type)
        assertEquals("obfs-pass", outbound?.obfs?.password)
    }

    @Test
    fun testParseHysteria2WithTlsFlagsAndMport() {
        val link = "hysteria2://password@hy2.example.com:443" +
            "?sni=edge.example.com&insecure=1&alpn=h3,hysteria&mport=20000,20001#TlsFlagsNode"
        val outbound = parser.parse(link)

        assertNotNull(outbound)
        assertEquals("edge.example.com", outbound?.tls?.serverName)
        assertEquals(true, outbound?.tls?.insecure)
        assertEquals(listOf("h3", "hysteria"), outbound?.tls?.alpn)
        assertEquals(listOf("20000", "20001"), outbound?.serverPorts)
    }

    @Test
    fun testParseHysteria2IpWithoutSniKeepsServerNameNull() {
        val link = "hysteria2://password@34.150.59.170:38313#Hy2IpNode"
        val outbound = parser.parse(link)

        assertNotNull(outbound)
        assertEquals("34.150.59.170", outbound?.server)
        assertNull(outbound?.tls?.serverName)
    }

    // ==================== Naive ====================

    @Test
    fun testParseNaiveBasic() {
        val link = "naive://user:pass@naive.example.com:443" +
            "?network=h2&insecure_concurrency=2" +
            "&extra_headers=User-Agent%3A%20naive%0AX-Test%3A%20demo" +
            "&sni=naive.example.com#NaiveNode"
        val outbound = parser.parse(link)

        assertNotNull(outbound)
        assertEquals("naive", outbound?.type)
        assertEquals("NaiveNode", outbound?.tag)
        assertEquals("naive.example.com", outbound?.server)
        assertEquals(443, outbound?.serverPort)
        assertEquals("user", outbound?.username)
        assertEquals("pass", outbound?.password)
        assertEquals("h2", outbound?.network)
        assertEquals(2, outbound?.insecureConcurrency)
        assertEquals("naive", outbound?.extraHeaders?.get("User-Agent"))
        assertEquals("demo", outbound?.extraHeaders?.get("X-Test"))
        assertNull(outbound?.path)
        assertNull(outbound?.headers)
        assertNotNull(outbound?.tls)
        assertEquals(true, outbound?.tls?.enabled)
        assertEquals("naive.example.com", outbound?.tls?.serverName)
    }

    @Test
    fun testParseNaivePlusHttpsScheme() {
        val link = "naive+https://u:p@naive.example.com:443?extra_headers=User-Agent%3A%20naive#NaiveHttps"
        val outbound = parser.parse(link)

        assertNotNull(outbound)
        assertEquals("naive", outbound?.type)
        assertEquals("NaiveHttps", outbound?.tag)
        assertEquals("u", outbound?.username)
        assertEquals("p", outbound?.password)
        assertEquals("h2", outbound?.network)
        assertEquals("naive", outbound?.extraHeaders?.get("User-Agent"))
    }

    @Test
    fun testParseNaivePlusHttpsWithTrailingComma() {
        val link = "naive+https://u:p@naive.example.com:443?insecure_concurrency=4#NaiveHttps,"
        val outbound = parser.parse(link)

        assertNotNull(outbound)
        assertEquals("naive", outbound?.type)
        assertEquals("NaiveHttps", outbound?.tag)
        assertEquals("u", outbound?.username)
        assertEquals("p", outbound?.password)
        assertEquals(4, outbound?.insecureConcurrency)
    }

    @Test
    fun testParseNaiveIpWithoutSniKeepsServerNameNull() {
        val link = "naive://u:p@34.150.59.170:443?network=h2#NaiveIp"
        val outbound = parser.parse(link)

        assertNotNull(outbound)
        assertEquals("34.150.59.170", outbound?.server)
        assertNull(outbound?.tls?.serverName)
    }

    // ==================== TUIC ====================

    @Test
    fun testParseTuicBasic() {
        val link = "tuic://uuid:password@tuic.example.com:443?congestion_control=bbr&udp_relay_mode=native#TUICNode"
        val outbound = parser.parse(link)

        assertNotNull(outbound)
        assertEquals("tuic", outbound?.type)
        assertEquals("TUICNode", outbound?.tag)
        assertEquals("tuic.example.com", outbound?.server)
        assertEquals(443, outbound?.serverPort)
        assertEquals("uuid", outbound?.uuid)
        assertEquals("password", outbound?.password)
        assertEquals("bbr", outbound?.congestionControl)
        assertEquals("native", outbound?.udpRelayMode)
        assertEquals("tuic.example.com", outbound?.tls?.serverName)
        assertNull(outbound?.disableSni)
    }

    @Test
    fun testParseTuicWithDisableSniClearsServerName() {
        val link = "tuic://uuid:password@tuic.example.com:443?sni=edge.example.com&disable_sni=1#TUICDisableSni"
        val outbound = parser.parse(link)

        assertNotNull(outbound)
        assertEquals("tuic", outbound?.type)
        assertEquals(true, outbound?.disableSni)
        assertEquals(true, outbound?.tls?.enabled)
        assertNull(outbound?.tls?.serverName)
    }

    @Test
    fun testParseTuicWithZeroRtt() {
        val link = "tuic://uuid:password@tuic.example.com:443?reduce_rtt=1#ZeroRttNode"
        val outbound = parser.parse(link)

        assertNotNull(outbound)
        assertEquals(true, outbound?.zeroRttHandshake)
    }

    @Test
    fun testRealTuicLinkDisablesSniInRuntimeTlsOptions() {
        val link = "tuic://a06ded63-e9b9-41ac-b721-c1372fe3d700:YhUro1WIFk@cdn4.eu.org:27017" +
            "?security=tls&sni=cdn4.eu.org&alpn=h3&congestion_control=bbr#%F0%9F%87%B0%F0%9F%87%B7%20" +
            "%E9%A6%96%E5%B0%944-T"
        val outbound = parser.parse(link)?.copy(disableSni = true)

        val runtime = outbound?.let { OutboundFixer.buildForRuntimeWithDialConfigForTest(it) }
        val json = gson.toJson(runtime)

        assertNotNull(runtime)
        assertNull(runtime?.disableSni)
        assertEquals(true, runtime?.tls?.disableSni)
        assertFalse(json.contains("\"server_name\""))
    }

    @Test
    fun testRealHysteria2LinkDisablesSniInRuntimeTlsOptions() {
        val link = "hysteria2://YhUro1WIFk@cdn4.eu.org:52011?security=tls&sni=cdn4.eu.org&alpn=&fastopen=0" +
            "#%F0%9F%87%B0%F0%9F%87%B7%20%E9%A6%96%E5%B0%944-H"
        val outbound = parser.parse(link)?.copy(disableSni = true)

        val runtime = outbound?.let { OutboundFixer.buildForRuntimeWithDialConfigForTest(it) }
        val json = gson.toJson(runtime)

        assertNotNull(runtime)
        assertNull(runtime?.disableSni)
        assertEquals(true, runtime?.tls?.disableSni)
        assertFalse(json.contains("\"server_name\""))
    }

    // ==================== AnyTLS ====================

    @Test
    fun testParseAnyTLSBasic() {
        val link = "anytls://password@anytls.example.com:443?sni=anytls.example.com#AnyTLSNode"
        val outbound = parser.parse(link)

        assertNotNull(outbound)
        assertEquals("anytls", outbound?.type)
        assertEquals("AnyTLSNode", outbound?.tag)
        assertEquals("anytls.example.com", outbound?.server)
        assertEquals(443, outbound?.serverPort)
        assertEquals("password", outbound?.password)
        assertNotNull(outbound?.tls)
        assertEquals(true, outbound?.tls?.enabled)
    }

    @Test
    fun testParseAnyTLSWithSessionParams() {
        val link = "anytls://password@anytls.example.com:443?idle_session_check_interval=30s&idle_session_timeout=60s&min_idle_session=2#SessionNode"
        val outbound = parser.parse(link)

        assertNotNull(outbound)
        assertEquals("30s", outbound?.idleSessionCheckInterval)
        assertEquals("60s", outbound?.idleSessionTimeout)
        assertEquals(2, outbound?.minIdleSession)
    }

    // ==================== HTTP/HTTPS ====================

    @Test
    fun testParseHttpsProxy() {
        val link = "https://user:pass@proxy.example.com:8443#HTTPSProxy"
        val outbound = parser.parse(link)

        assertNotNull(outbound)
        assertEquals("http", outbound?.type)
        assertEquals("HTTPSProxy", outbound?.tag)
        assertEquals("proxy.example.com", outbound?.server)
        assertEquals(8443, outbound?.serverPort)
        assertEquals("user", outbound?.username)
        assertEquals("pass", outbound?.password)
        assertNotNull(outbound?.tls)
        assertEquals(true, outbound?.tls?.enabled)
    }

    @Test
    fun testParseHttpsProxyIpKeepsServerNameNull() {
        val link = "https://user:pass@1.2.3.4:8443#HTTPSProxyIp"
        val outbound = parser.parse(link)

        assertNotNull(outbound)
        assertEquals("1.2.3.4", outbound?.server)
        assertNull(outbound?.tls?.serverName)
    }

    @Test
    fun testParseHttpProxy() {
        val link = "http://user:pass@proxy.example.com:8080#HTTPProxy"
        val outbound = parser.parse(link)

        assertNotNull(outbound)
        assertEquals("http", outbound?.type)
        assertEquals(8080, outbound?.serverPort)
        assertNull(outbound?.tls)
    }

    @Test
    fun testParseHttpProxyWithoutAuth() {
        val link = "http://proxy.example.com:3128#NoAuthProxy"
        val outbound = parser.parse(link)

        assertNotNull(outbound)
        assertNull(outbound?.username)
        assertNull(outbound?.password)
    }

    // ==================== SOCKS5 ====================

    @Test
    fun testParseSocks5Basic() {
        val link = "socks5://user:pass@socks.example.com:1080#SOCKS5Node"
        val outbound = parser.parse(link)

        assertNotNull(outbound)
        assertEquals("socks", outbound?.type)
        assertEquals("SOCKS5Node", outbound?.tag)
        assertEquals("socks.example.com", outbound?.server)
        assertEquals(1080, outbound?.serverPort)
        assertEquals("user", outbound?.username)
        assertEquals("pass", outbound?.password)
    }

    @Test
    fun testParseSocksShortScheme() {
        val link = "socks://socks.example.com:1080#SocksShort"
        val outbound = parser.parse(link)

        assertNotNull(outbound)
        assertEquals("socks", outbound?.type)
        assertEquals(1080, outbound?.serverPort)
    }

    // ==================== Edge Cases ====================

    @Test
    fun testParseUnknownScheme() {
        val link = "unknown://something"
        val outbound = parser.parse(link)

        assertNull(outbound)
    }

    @Test
    fun testParseEmptyLink() {
        val outbound = parser.parse("")
        assertNull(outbound)
    }

    @Test
    fun testParseMalformedLink() {
        val outbound = parser.parse("not-a-valid-link")
        assertNull(outbound)
    }

    @Test
    fun testParseSpecialCharactersInPassword() {
        val link = "trojan://p%40ss%23word%21@trojan.example.com:443#SpecialChars"
        val outbound = parser.parse(link)

        assertNotNull(outbound)
        // URL 编码的特殊字符: @ = %40, # = %23, ! = %21
        assertEquals("p@ss#word!", outbound?.password)
    }

    @Test
    fun testParseSpacesInQueryParams() {
        // 测试 sanitizeUri 对空格的处理
        val link = "vless://uuid@server:443?security = tls & type = ws#SpacesNode"
        val outbound = parser.parse(link)

        assertNotNull(outbound)
        assertEquals("vless", outbound?.type)
    }
}
