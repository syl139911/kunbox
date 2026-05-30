package com.kunk.singbox.ui.components

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.material3.SnackbarDuration

object AppNotificationManager {
    @Suppress("UnusedParameter")
    fun showMessage(
        context: Context,
        message: String,
        duration: SnackbarDuration = SnackbarDuration.Short,
        actionLabel: String? = null,
        onAction: (() -> Unit)? = null
    ) {
        if (message.isBlank()) return

        Handler(Looper.getMainLooper()).post {
            Toast.makeText(
                context.applicationContext,
                message,
                if (duration == SnackbarDuration.Long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
            ).show()
        }
    }
}
