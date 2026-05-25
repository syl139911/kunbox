package com.kunk.singbox.utils

import com.kunk.singbox.repository.BugLogRepository

/**
 * Centralized bug log helper to avoid repetitive try-catch blocks.
 * Supports structured VPN lifecycle error reporting with phase categorization,
 * current node context, and runtime config snapshots.
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
    const val PHASE_NETWORK = "NetworkFailed"
    const val PHASE_SELECTOR = "SelectorFailed"

    // ── Node Context (set by SingBoxService on start/switch) ───────
    @Volatile
    private var currentNodeName: String? = null

    @Volatile
    private var currentNodeProtocol: String? = null

    @Volatile
    private var currentNodeOutboundTag: String? = null

    @Volatile
    private var runtimeConfig: String? = null

    fun setNodeContext(
        name: String?,
        protocol: String? = null,
        outboundTag: String? = null,
        supportsUdp: Boolean? = null,
        stack: String? = null
    ) {
        try {
            currentNodeName = name
            currentNodeProtocol = protocol
            currentNodeOutboundTag = outboundTag
        } catch (_: Exception) {
        }
    }

    fun setRuntimeConfig(configContent: String?) {
        try {
            runtimeConfig = configContent
        } catch (_: Exception) {
        }
    }

    fun clearNodeContext() {
        currentNodeName = null
        currentNodeProtocol = null
        currentNodeOutboundTag = null
    }

    // ── Core Logging ───────────────────────────────────────────────

    fun log(title: String, detail: String, throwable: Throwable? = null) {
        try {
            BugLogRepository.getInstance().addBugLog(
                title = title,
                detail = detail,
                throwable = throwable
            )
        } catch (_: Exception) {
            // Never let bug logging crash the app
        }
    }

    /**
     * Report a VPN lifecycle error with phase categorization and current node context.
     */
    fun reportVpnError(
        phase: String,
        message: String,
        throwable: Throwable? = null,
        extraDetail: String? = null
    ) {
        try {
            val detail = buildString {
                append("Phase: $phase\n")
                append("Error: $message")
                currentNodeName?.let { append("\nNode: $it") }
                currentNodeProtocol?.let { append("\nProtocol: $it") }
                currentNodeOutboundTag?.let { append("\nOutbound: $it") }
                extraDetail?.let { append("\nDetail: $it") }
                runtimeConfig?.let {
                    append("\n\n--- Runtime Config (first 500 chars) ---\n")
                    append(it.take(500))
                }
            }
            BugLogRepository.getInstance().addBugLog(
                title = "VPN [$phase]",
                detail = detail,
                throwable = throwable
            )
        } catch (_: Exception) {
        }
    }

    // ── Convenience Wrappers (unchanged) ───────────────────────────

    fun logConfigError(detail: String, throwable: Throwable? = null) {
        log("Config Error", detail, throwable)
    }

    fun logHttpError(detail: String, throwable: Throwable? = null) {
        log("HTTP Error", detail, throwable)
    }

    fun logNodeError(detail: String, throwable: Throwable? = null) {
        log("Node Error", detail, throwable)
    }

    fun logConnectionError(detail: String, throwable: Throwable? = null) {
        log("Connection Error", detail, throwable)
    }

    fun logVpnError(detail: String, throwable: Throwable? = null) {
        log("VPN Error", detail, throwable)
    }
}
