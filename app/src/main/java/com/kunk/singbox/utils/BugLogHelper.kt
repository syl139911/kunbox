package com.kunk.singbox.utils

import com.kunk.singbox.repository.BugLogRepository

/**
 * Centralized bug log helper.
 *
 * Supports structured VPN lifecycle error reporting with:
 * - Error phase categorization (BuildConfig, CreateTun, StartCore, etc.)
 * - Current node context (name, protocol, outbound tag)
 * - Runtime config snapshot on critical failures
 * - Full stack trace capture
 */
object BugLogHelper {

    // ── Error Phase Constants ──────────────────────────────────────
    const val PHASE_BUILD_CONFIG = "BuildConfigFailed"
    const val PHASE_CREATE_TUN = "CreateTunFailed"
    const val PHASE_START_CORE = "StartCoreFailed"
    const val PHASE_LIBBOX_INIT = "LibboxInitFailed"
    const val PHASE_SERVICE_LOOP = "ServiceLoopCrash"
    const val PHASE_HOT_RELOAD = "HotReloadFailed"
    const val PHASE_HOT_SWITCH = "HotSwitchFailed"
    const val PHASE_NETWORK = "NetworkError"
    const val PHASE_SELECTOR = "SelectorError"
    const val PHASE_COMMAND = "CommandError"
    const val PHASE_SHUTDOWN = "ShutdownError"
    const val PHASE_PROXY_START = "ProxyStartFailed"
    const val PHASE_CONFIG = "ConfigError"
    const val PHASE_HTTP = "HttpError"
    const val PHASE_NODE = "NodeError"
    const val PHASE_CONNECTION = "ConnectionError"
    const val PHASE_UNKNOWN = "UnknownError"

    // ── Current Node Context (set by service layer) ───────────────
    @Volatile
    var currentNodeName: String? = null

    @Volatile
    var currentNodeProtocol: String? = null

    @Volatile
    var currentNodeOutboundTag: String? = null

    @Volatile
    var currentNodeSupportsUdp: Boolean? = null

    @Volatile
    var currentNodeStack: String? = null   // "gvisor" / "system"

    fun setNodeContext(
        name: String? = null,
        protocol: String? = null,
        outboundTag: String? = null,
        supportsUdp: Boolean? = null,
        stack: String? = null
    ) {
        currentNodeName = name ?: currentNodeName
        currentNodeProtocol = protocol ?: currentNodeProtocol
        currentNodeOutboundTag = outboundTag ?: currentNodeOutboundTag
        currentNodeSupportsUdp = supportsUdp ?: currentNodeSupportsUdp
        currentNodeStack = stack ?: currentNodeStack
    }

    fun clearNodeContext() {
        currentNodeName = null
        currentNodeProtocol = null
        currentNodeOutboundTag = null
        currentNodeSupportsUdp = null
        currentNodeStack = null
    }

    // ── Runtime Config Snapshot (saved on critical failure) ───────
    @Volatile
    private var lastRuntimeConfig: String? = null

    fun setRuntimeConfig(configJson: String?) {
        lastRuntimeConfig = configJson
    }

    fun getLastRuntimeConfig(): String? = lastRuntimeConfig

    // ── Core Log Methods ──────────────────────────────────────────

    fun log(title: String, detail: String, throwable: Throwable? = null) {
        try {
            val enrichedDetail = buildEnrichedDetail(detail)
            BugLogRepository.getInstance().addBugLog(
                title = title,
                detail = enrichedDetail,
                throwable = throwable
            )
        } catch (_: Exception) {
            // Never let bug logging crash the app
        }
    }

    /**
     * Unified VPN lifecycle error reporter.
     * Logs to both LogRepository (running log) and BugLogRepository (bug page).
     */
    fun reportVpnError(
        phase: String,
        title: String,
        e: Throwable,
        extraDetail: String? = null
    ) {
        val detail = buildString {
            append("Phase: $phase")
            append("\nError: ${e.javaClass.simpleName}: ${e.message}")
            if (extraDetail != null) {
                append("\n$extraDetail")
            }
        }
        log("[$phase] $title", detail, e)
    }

    // ── Convenience Methods (backward compatible) ─────────────────

    fun logConfigError(detail: String, throwable: Throwable? = null) {
        log("[$PHASE_CONFIG] Config Error", detail, throwable)
    }

    fun logHttpError(detail: String, throwable: Throwable? = null) {
        log("[$PHASE_HTTP] HTTP Error", detail, throwable)
    }

    fun logNodeError(detail: String, throwable: Throwable? = null) {
        log("[$PHASE_NODE] Node Error", detail, throwable)
    }

    fun logConnectionError(detail: String, throwable: Throwable? = null) {
        log("[$PHASE_CONNECTION] Connection Error", detail, throwable)
    }

    fun logVpnError(detail: String, throwable: Throwable? = null) {
        log("[$PHASE_UNKNOWN] VPN Error", detail, throwable)
    }

    // ── Internal Helpers ──────────────────────────────────────────

    private fun buildEnrichedDetail(detail: String): String {
        return buildString {
            append(detail)

            val nodeName = currentNodeName
            val protocol = currentNodeProtocol
            val outbound = currentNodeOutboundTag
            val udp = currentNodeSupportsUdp
            val stack = currentNodeStack

            if (nodeName != null || protocol != null || outbound != null) {
                append("\n\n--- Node Context ---")
                if (nodeName != null) append("\nNode: $nodeName")
                if (protocol != null) append("\nProtocol: $protocol")
                if (outbound != null) append("\nOutboundTag: $outbound")
                if (udp != null) append("\nUDP: $udp")
                if (stack != null) append("\nStack: $stack")
            }
        }
    }
}
