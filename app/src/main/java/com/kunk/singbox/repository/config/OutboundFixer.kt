package com.kunk.singbox.repository.config

import com.kunk.singbox.core.LibboxCompat
import com.kunk.singbox.model.MultiplexConfig
import com.kunk.singbox.model.Outbound
import com.kunk.singbox.model.TlsConfig
import com.kunk.singbox.repository.SettingsRepository

/**
 */
object OutboundFixer {
    @Volatile private var cachedTcpKeepAliveEnabled: Boolean? = null
    @Volatile private var cachedTcpKeepAliveInterval: String? = null
    @Volatile private var cachedConnectTimeout: String? = null

    /**
     */
    private fun getTcpKeepAliveConfig(context: android.content.Context): Triple<Boolean, String?, String?> {
        cachedTcpKeepAliveEnabled?.let { enabled ->
            return Triple(enabled, cachedTcpKeepAliveInterval, cachedConnectTimeout)
        }

        synchronized(this) {
            cachedTcpKeepAliveEnabled?.let { enabled ->
                return Triple(enabled, cachedTcpKeepAliveInterval, cachedConnectTimeout)
            }

            val settings = SettingsRepository.getInstance(context).settings.value
            val enabled = settings.tcpKeepAliveEnabled
            val interval = if (enabled) "${settings.tcpKeepAliveInterval}s" else null
            val timeout = if (enabled) "${settings.connectTimeout}s" else null

            cachedTcpKeepAliveEnabled = enabled
            cachedTcpKeepAliveInterval = interval
            cachedConnectTimeout = timeout

            return Triple(enabled, interval, timeout)
        }
    }

    fun clearTcpKeepAliveCache() {
        synchronized(this) {
            cachedTcpKeepAliveEnabled = null
            cachedTcpKeepAliveInterval = null
            cachedConnectTimeout = null
        }
    }

    private val REGEX_INTERVAL_DIGITS = Regex("^\\d+$")
    private val REGEX_INTERVAL_DECIMAL = Regex("^\\d+\\.\\d+$")
    private val REGEX_INTERVAL_UNIT = Regex("^\\d+(\\.\\d+)?[smhSMH]$")
    private val REGEX_IPV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
    private val REGEX_IPV6 = Regex("^[0-9a-fA-F:]+$")
    private val REGEX_ED_PARAM_START = Regex("\\?ed=\\d+")
    private val REGEX_ED_PARAM_MID = Regex("&ed=\\d+")

    /**
     */
    fun fix(outbound: Outbound): Outbound {
        var result = outbound

        // Fix interval
        val interval = result.interval
        if (interval != null) {
            val fixedInterval = when {
                REGEX_INTERVAL_DIGITS.matches(interval) -> "${interval}s"
                REGEX_INTERVAL_DECIMAL.matches(interval) -> "${interval}s"
                REGEX_INTERVAL_UNIT.matches(interval) -> interval.lowercase()
                else -> interval
            }
            if (fixedInterval != interval) {
                result = result.copy(interval = fixedInterval)
            }
        }

        // Fix flow
        val cleanedFlow = result.flow?.takeIf { it.isNotBlank() }
        val normalizedFlow = cleanedFlow?.let { flowValue ->
            if (flowValue.contains("xtls-rprx-vision")) {
                "xtls-rprx-vision"
            } else {
                flowValue
            }
        }
        if (normalizedFlow != result.flow) {
            result = result.copy(flow = normalizedFlow)
        }

        // Fix VLESS + Vision + Reality + Mux compatibility
        if (result.type == "vless") {
            val tunedMux = tuneMuxForVisionReality(result)
            if (tunedMux != result.multiplex) {
                result = result.copy(multiplex = tunedMux)
            }
        }

        // Fix URLTest - Convert to selector to avoid sing-box core panic during InterfaceUpdated
        if (result.type == "urltest" || result.type == "url-test") {
            var newOutbounds = result.outbounds
            if (newOutbounds.isNullOrEmpty()) {
                newOutbounds = listOf("direct")
            }

            result = result.copy(
                type = "selector",
                outbounds = newOutbounds,
                default = newOutbounds.firstOrNull(),
                interruptExistConnections = false,
                url = null,
                interval = null,
                tolerance = null
            )
        }

        // Fix Selector empty outbounds
        if (result.type == "selector" && result.outbounds.isNullOrEmpty()) {
            result = result.copy(outbounds = listOf("direct"))
        }

        // Fix TLS SNI for WebSocket
        val tls = result.tls
        val transport = result.transport
        if (transport?.type == "ws" && tls?.enabled == true) {
            val wsHost = transport.headers?.get("Host")
                ?: transport.headers?.get("host")
                ?: transport.host?.firstOrNull()
            val sni = tls.serverName?.trim().orEmpty()
            val server = result.server?.trim().orEmpty()
            if (!wsHost.isNullOrBlank() && !isIpLiteral(wsHost)) {
                val needFix = sni.isBlank() || isIpLiteral(sni) || (server.isNotBlank() && sni.equals(server, ignoreCase = true))
                if (needFix && !wsHost.equals(sni, ignoreCase = true)) {
                    result = result.copy(tls = tls.copy(serverName = wsHost))
                }
            }
        }

        // Fix ALPN for WebSocket + TLS
        val tlsAfterSni = result.tls
        if (
            result.transport?.type == "ws" &&
            tlsAfterSni?.enabled == true &&
            (tlsAfterSni.alpn == null || tlsAfterSni.alpn.isEmpty())
        ) {
            result = result.copy(tls = tlsAfterSni.copy(alpn = listOf("http/1.1")))
        }

        if (transport?.type == "xhttp") {
            val rawPath = transport.path ?: "/"
            val normalizedPath = normalizeXhttpPath(rawPath)

            val xhttpHost = transport.host?.firstOrNull()
            val tlsForXhttp = result.tls?.takeIf { it.enabled == true }
            val xhttpSni = tlsForXhttp?.serverName?.trim().orEmpty()
            val server = result.server?.trim().orEmpty()
            val shouldFixXhttpSni = tlsForXhttp != null &&
                !xhttpHost.isNullOrBlank() &&
                !isIpLiteral(xhttpHost) &&
                (
                    xhttpSni.isBlank() ||
                        isIpLiteral(xhttpSni) ||
                        (server.isNotBlank() && xhttpSni.equals(server, ignoreCase = true))
                    )

            val currentAlpn = tlsForXhttp?.alpn
            val shouldFixXhttpAlpn = tlsForXhttp != null &&
                (currentAlpn == null || currentAlpn.isEmpty() || currentAlpn != listOf("h2"))

            if (normalizedPath != rawPath || shouldFixXhttpSni || shouldFixXhttpAlpn) {
                var updated = result.copy(
                    transport = transport.copy(path = normalizedPath)
                )

                var tlsUpdated = tlsForXhttp
                if (shouldFixXhttpSni && tlsUpdated != null) {
                    tlsUpdated = tlsUpdated.copy(serverName = xhttpHost)
                }
                if (shouldFixXhttpAlpn && tlsUpdated != null) {
                    tlsUpdated = tlsUpdated.copy(alpn = listOf("h2"))
                }

                if (tlsUpdated != result.tls) {
                    updated = updated.copy(tls = tlsUpdated)
                }

                result = updated
            }

            if (result.packetEncoding == "xudp") {
                result = result.copy(packetEncoding = "")
            }
        }

        // Fix User-Agent and path for WS
        if (transport != null && transport.type == "ws") {
            val headers = transport.headers?.toMutableMap() ?: mutableMapOf()
            var needUpdate = false

            if (!headers.containsKey("Host")) {
                val host = transport.host?.firstOrNull()
                    ?: result.tls?.serverName
                    ?: result.server
                if (!host.isNullOrBlank()) {
                    headers["Host"] = host
                    needUpdate = true
                }
            }

            if (!headers.containsKey("User-Agent")) {
                val fingerprint = result.tls?.utls?.fingerprint
                val userAgent = if (fingerprint?.contains("chrome") == true) {
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36"
                } else {
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:109.0) Gecko/20100101 Firefox/115.0"
                }
                headers["User-Agent"] = userAgent
                needUpdate = true
            }

            val rawPath = transport.path ?: "/"
            val cleanPath = rawPath
                .replace(REGEX_ED_PARAM_START, "")
                .replace(REGEX_ED_PARAM_MID, "")
                .trimEnd('?', '&')
                .ifEmpty { "/" }

            val pathChanged = cleanPath != rawPath

            if (needUpdate || pathChanged) {
                result = result.copy(transport = transport.copy(
                    headers = headers,
                    path = cleanPath
                ))
            }
        }

        if (result.type == "vless" && result.security != null) {
            result = result.copy(security = null)
        }

        if (result.type == "hysteria" || result.type == "hysteria2") {
            val cleanedServerPorts = result.serverPorts
                ?.filter { it.isNotBlank() }
                ?.map { convertPortRangeFormat(it) }
                ?.takeIf { it.isNotEmpty() }
            val cleanedHopInterval = result.hopInterval?.takeIf { it.isNotBlank() }
            result = result.copy(
                serverPorts = cleanedServerPorts,
                hopInterval = cleanedHopInterval
            )
        }
        if (result.type == "vmess" && result.packetEncoding.isNullOrBlank()) {
            result = result.copy(packetEncoding = "xudp")
        }

        if (result.type == "naive") {
            result = fixNaive(result)
        }

        val currentTls = result.tls
        if (currentTls != null && currentTls.alpn?.isEmpty() == true) {
            result = result.copy(tls = currentTls.copy(alpn = null))
        }

        return result
    }

    /**
     */
    @Suppress("LongMethod")
    fun buildForRuntime(context: android.content.Context, outbound: Outbound): Outbound {
        val fixed = applyNaiveRuntimeCompatibility(fix(outbound))

        val (tcpKeepAliveEnabled, tcpKeepAliveInterval, connectTimeout) = getTcpKeepAliveConfig(context)

        return when (fixed.type) {
            "selector", "urltest", "url-test" -> Outbound(
                type = "selector",
                tag = fixed.tag,
                outbounds = fixed.outbounds,
                default = fixed.default,
                interruptExistConnections = fixed.interruptExistConnections
            )

            "direct", "block", "dns" -> Outbound(type = fixed.type, tag = fixed.tag)

            "vmess" -> Outbound(
                type = fixed.type,
                tag = fixed.tag,
                server = fixed.server,
                serverPort = fixed.serverPort,
                uuid = fixed.uuid,
                alterId = fixed.alterId,
                security = fixed.security,
                packetEncoding = fixed.packetEncoding,
                tls = fixed.tls,
                transport = fixed.transport,
                multiplex = fixed.multiplex,

                tcpKeepAlive = tcpKeepAliveInterval,
                tcpKeepAliveInterval = tcpKeepAliveInterval,
                connectTimeout = connectTimeout
            )

            "vless" -> Outbound(
                type = fixed.type,
                tag = fixed.tag,
                server = fixed.server,
                serverPort = fixed.serverPort,
                uuid = fixed.uuid,
                flow = fixed.flow,
                packetEncoding = fixed.packetEncoding,
                tls = fixed.tls,
                transport = fixed.transport,
                multiplex = fixed.multiplex,

                tcpKeepAlive = tcpKeepAliveInterval,
                tcpKeepAliveInterval = tcpKeepAliveInterval,
                connectTimeout = connectTimeout
            )

            "trojan" -> Outbound(
                type = fixed.type,
                tag = fixed.tag,
                server = fixed.server,
                serverPort = fixed.serverPort,
                password = fixed.password,
                tls = fixed.tls,
                transport = fixed.transport,
                multiplex = fixed.multiplex,

                tcpKeepAlive = tcpKeepAliveInterval,
                tcpKeepAliveInterval = tcpKeepAliveInterval,
                connectTimeout = connectTimeout
            )

            "shadowsocks" -> Outbound(
                type = fixed.type,
                tag = fixed.tag,
                server = fixed.server,
                serverPort = fixed.serverPort,
                method = fixed.method,
                password = fixed.password,
                plugin = fixed.plugin,
                pluginOpts = fixed.pluginOpts,
                udpOverTcp = fixed.udpOverTcp,
                multiplex = fixed.multiplex,
                detour = fixed.detour,
                network = fixed.network,

                tcpKeepAlive = tcpKeepAliveInterval,
                tcpKeepAliveInterval = tcpKeepAliveInterval,
                connectTimeout = connectTimeout
            )

            "hysteria", "hysteria2" -> Outbound(
                type = fixed.type,
                tag = fixed.tag,
                server = fixed.server,
                serverPort = fixed.serverPort,
                password = fixed.password,
                authStr = fixed.authStr,
                upMbps = fixed.upMbps,
                downMbps = fixed.downMbps,
                obfs = fixed.obfs,
                recvWindowConn = fixed.recvWindowConn,
                recvWindow = fixed.recvWindow,
                disableMtuDiscovery = fixed.disableMtuDiscovery,
                hopInterval = fixed.hopInterval,
                serverPorts = fixed.serverPorts,
                tls = fixed.tls,
                multiplex = fixed.multiplex,

                tcpKeepAlive = tcpKeepAliveInterval,
                tcpKeepAliveInterval = tcpKeepAliveInterval,
                connectTimeout = connectTimeout
            )

            "tuic" -> Outbound(
                type = fixed.type,
                tag = fixed.tag,
                server = fixed.server,
                serverPort = fixed.serverPort,
                uuid = fixed.uuid,
                password = fixed.password,
                congestionControl = fixed.congestionControl,
                udpRelayMode = fixed.udpRelayMode,
                zeroRttHandshake = fixed.zeroRttHandshake,
                heartbeat = fixed.heartbeat,
                disableSni = fixed.disableSni,
                mtu = fixed.mtu,
                tls = fixed.tls,
                multiplex = fixed.multiplex,

                tcpKeepAlive = tcpKeepAliveInterval,
                tcpKeepAliveInterval = tcpKeepAliveInterval,
                connectTimeout = connectTimeout
            )

            "naive" -> Outbound(
                type = fixed.type,
                tag = fixed.tag,
                server = fixed.server,
                serverPort = fixed.serverPort,
                username = fixed.username,
                password = fixed.password,
                insecureConcurrency = fixed.insecureConcurrency,
                extraHeaders = fixed.extraHeaders,
                quic = fixed.network?.equals("quic", ignoreCase = true),
                quicCongestionControl = fixed.congestionControl,
                tls = fixed.tls,
                udpOverTcp = fixed.udpOverTcp,
                domainResolver = resolveNaiveDomainResolver(fixed),

                tcpKeepAlive = if (tcpKeepAliveEnabled) tcpKeepAliveInterval else null,
                tcpKeepAliveInterval = if (tcpKeepAliveEnabled) tcpKeepAliveInterval else null,
                connectTimeout = connectTimeout
            )

            "anytls" -> Outbound(
                type = fixed.type,
                tag = fixed.tag,
                server = fixed.server,
                serverPort = fixed.serverPort,
                password = fixed.password,
                idleSessionCheckInterval = fixed.idleSessionCheckInterval,
                idleSessionTimeout = fixed.idleSessionTimeout,
                minIdleSession = fixed.minIdleSession,
                tls = fixed.tls,
                multiplex = fixed.multiplex,

                tcpKeepAlive = tcpKeepAliveInterval,
                tcpKeepAliveInterval = tcpKeepAliveInterval,
                connectTimeout = connectTimeout
            )

            "wireguard" -> Outbound(
                type = fixed.type,
                tag = fixed.tag,
                localAddress = fixed.localAddress,
                privateKey = fixed.privateKey,
                peerPublicKey = fixed.peerPublicKey,
                preSharedKey = fixed.preSharedKey,
                reserved = fixed.reserved,
                peers = fixed.peers,

                tcpKeepAlive = tcpKeepAliveInterval,
                tcpKeepAliveInterval = tcpKeepAliveInterval,
                connectTimeout = connectTimeout
            )

            "ssh" -> Outbound(
                type = fixed.type,
                tag = fixed.tag,
                server = fixed.server,
                serverPort = fixed.serverPort,
                user = fixed.user,
                password = fixed.password,
                privateKeyPath = fixed.privateKeyPath,
                privateKeyPassphrase = fixed.privateKeyPassphrase,
                hostKey = fixed.hostKey,
                hostKeyAlgorithms = fixed.hostKeyAlgorithms,
                clientVersion = fixed.clientVersion,

                tcpKeepAlive = tcpKeepAliveInterval,
                tcpKeepAliveInterval = tcpKeepAliveInterval,
                connectTimeout = connectTimeout
            )

            "shadowtls" -> Outbound(
                type = fixed.type,
                tag = fixed.tag,
                server = fixed.server,
                serverPort = fixed.serverPort,
                version = fixed.version,
                password = fixed.password,
                tls = fixed.tls,

                tcpKeepAlive = tcpKeepAliveInterval,
                tcpKeepAliveInterval = tcpKeepAliveInterval,
                connectTimeout = connectTimeout
            )

            else -> fixed
        }
    }

    @Suppress("CyclomaticComplexMethod")
    private fun fixNaive(outbound: Outbound): Outbound {
        val preferredNetwork = outbound.network?.trim()?.lowercase()
        val normalizedNetwork = when (preferredNetwork) {
            "", null -> "h2"
            "h2", "quic" -> preferredNetwork
            else -> "h2"
        }
        val useQuic = normalizedNetwork == "quic" || outbound.quic == true

        val normalizedHeaders = buildMap {
            outbound.extraHeaders
                ?.asSequence()
                ?.map { (key, value) -> key.trim() to value.trim() }
                ?.filter { (key, value) -> key.isNotEmpty() && value.isNotEmpty() }
                ?.forEach { (key, value) -> put(key, value) }

            val host = outbound.headers?.get("Host")?.trim()
            if (!host.isNullOrEmpty() && !containsKey("Host")) {
                put("Host", host)
            }
        }.ifEmpty { null }

        val host = normalizedHeaders?.get("Host")?.trim()
        val tls = outbound.tls ?: TlsConfig(enabled = true)
        val tlsEnabled = tls.enabled != false
        val shouldSetSni = tlsEnabled &&
            !host.isNullOrBlank() &&
            !isIpLiteral(host) &&
            (tls.serverName.isNullOrBlank() || isIpLiteral(tls.serverName ?: ""))
        val tlsUpdated = if (shouldSetSni) tls.copy(serverName = host, enabled = true) else tls

        return outbound.copy(
            network = if (useQuic) "quic" else "h2",
            path = null,
            headers = null,
            extraHeaders = normalizedHeaders,
            quic = useQuic,
            quicCongestionControl = outbound.quicCongestionControl ?: outbound.congestionControl,
            tls = tlsUpdated,
            domainResolver = resolveNaiveDomainResolver(outbound)
        )
    }

    private fun resolveNaiveDomainResolver(outbound: Outbound): com.kunk.singbox.model.DomainResolveConfig? {
        val existing = outbound.domainResolver
        if (existing?.server.isNullOrBlank().not()) return existing

        val serverHost = outbound.server?.trim().orEmpty()
        if (serverHost.isBlank() || isIpLiteral(serverHost)) {
            return existing
        }

        return com.kunk.singbox.model.DomainResolveConfig(server = "dns-bootstrap")
    }

    private fun applyNaiveRuntimeCompatibility(outbound: Outbound): Outbound {
        if (outbound.type != "naive") return outbound

        val hasQuic = outbound.quic == true || outbound.network?.lowercase() == "quic"
        val quicSupported = LibboxCompat.isNaiveQuicSupported()
        if (!hasQuic || quicSupported) return outbound

        val tls = (outbound.tls ?: TlsConfig(enabled = true)).copy(alpn = listOf("h2"))
        return outbound.copy(network = "h2", quic = false, tls = tls)
    }

    private fun tuneMuxForVisionReality(outbound: Outbound): MultiplexConfig? {
        val mux = outbound.multiplex ?: return null
        if (mux.enabled != true) return mux

        val hasVisionFlow = outbound.flow?.contains("xtls-rprx-vision", ignoreCase = true) == true
        val tls = outbound.tls
        val hasReality = tls?.enabled == true && tls.reality?.enabled == true
        if (!hasVisionFlow || !hasReality) return mux

        val transportType = outbound.transport?.type?.lowercase()
        val isXhttp = transportType == "xhttp" || transportType == "splithttp"
        if (isXhttp) return mux

        val normalizedProtocol = when (mux.protocol?.lowercase()) {
            "h2mux", "smux", "yamux" -> "h2mux"
            null, "" -> "h2mux"
            else -> "h2mux"
        }
        val maxConnections = mux.maxConnections?.coerceIn(1, 2) ?: 1
        val minStreams = mux.minStreams?.coerceAtLeast(1)
        val maxStreams = mux.maxStreams?.coerceIn(1, 8) ?: 4

        return mux.copy(
            protocol = normalizedProtocol,
            maxConnections = maxConnections,
            minStreams = minStreams,
            maxStreams = maxStreams,
            padding = false
        )
    }

    private fun isIpLiteral(value: String): Boolean {
        val v = value.trim()
        if (v.isEmpty()) return false
        if (REGEX_IPV4.matches(v)) {
            return v.split(".").all { it.toIntOrNull()?.let { n -> n in 0..255 } == true }
        }
        return v.contains(":") && REGEX_IPV6.matches(v)
    }

    private fun normalizeXhttpPath(path: String): String {
        val trimmed = path.trim().ifEmpty { "/" }
        val withLeadingSlash = if (trimmed.startsWith("/")) trimmed else "/$trimmed"

        return withLeadingSlash
    }

    /**
     */
    private fun convertPortRangeFormat(portSpec: String): String {
        return portSpec.split(",").joinToString(",") { part ->
            val trimmed = part.trim()
            if (trimmed.contains("-") && !trimmed.contains(":")) {
                trimmed.replace("-", ":")
            } else {
                trimmed
            }
        }
    }
}
