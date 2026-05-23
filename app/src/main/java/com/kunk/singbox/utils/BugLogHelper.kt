package com.kunk.singbox.utils

import com.kunk.singbox.repository.BugLogRepository

/**
 * Centralized bug log helper to avoid repetitive try-catch blocks.
 * Usage: BugLogHelper.log("Title", "Detail", throwable)
 */
object BugLogHelper {

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
