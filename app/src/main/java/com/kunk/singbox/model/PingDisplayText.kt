package com.kunk.singbox.model

object PingDisplayText {
    fun resolve(
        isConnected: Boolean,
        displayPing: Long?,
        timeoutText: String,
        unavailableText: String
    ): String {
        return when {
            !isConnected -> "-"
            displayPing != null && displayPing > 0 -> "$displayPing ms"
            displayPing == PingResultCode.FAILED_TIMEOUT -> timeoutText
            displayPing == PingResultCode.UNAVAILABLE -> unavailableText
            else -> "-"
        }
    }
}
