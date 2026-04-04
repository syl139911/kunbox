package com.kunk.singbox.ui.components

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kunk.singbox.ui.theme.PureWhite
import kotlinx.coroutines.flow.MutableSharedFlow

private data class AppNotificationEvent(
    val message: String,
    val duration: SnackbarDuration = SnackbarDuration.Short,
    val actionLabel: String? = null,
    val onAction: (() -> Unit)? = null
)

private data class AppNotificationVisuals(
    override val message: String,
    override val actionLabel: String?,
    override val duration: SnackbarDuration,
    val onAction: (() -> Unit)?
) : SnackbarVisuals {
    override val withDismissAction: Boolean = false
}

object AppNotificationManager {
    private val hostLock = Any()
    private val events = MutableSharedFlow<AppNotificationEvent>(extraBufferCapacity = 16)
    private var activeHostCount: Int = 0

    fun showMessage(
        context: Context,
        message: String,
        duration: SnackbarDuration = SnackbarDuration.Short,
        actionLabel: String? = null,
        onAction: (() -> Unit)? = null
    ) {
        if (message.isBlank()) return

        if (hasActiveHost() &&
            events.tryEmit(
                AppNotificationEvent(
                    message = message,
                    duration = duration,
                    actionLabel = actionLabel,
                    onAction = onAction
                )
            )
        ) {
            return
        }

        showStyledToast(context, message, duration)
    }

    fun showInAppMessage(
        message: String,
        duration: SnackbarDuration = SnackbarDuration.Short,
        actionLabel: String? = null,
        onAction: (() -> Unit)? = null
    ) {
        if (message.isBlank()) return
        events.tryEmit(
            AppNotificationEvent(
                message = message,
                duration = duration,
                actionLabel = actionLabel,
                onAction = onAction
            )
        )
    }

    fun registerHost() {
        synchronized(hostLock) {
            activeHostCount += 1
        }
    }

    fun unregisterHost() {
        synchronized(hostLock) {
            activeHostCount = (activeHostCount - 1).coerceAtLeast(0)
        }
    }

    private fun hasActiveHost(): Boolean = synchronized(hostLock) {
        activeHostCount > 0
    }

    suspend fun collectNotifications(
        onEvent: suspend (
            message: String,
            duration: SnackbarDuration,
            actionLabel: String?,
            onAction: (() -> Unit)?
        ) -> Unit
    ) {
        events.collect { event ->
            onEvent(event.message, event.duration, event.actionLabel, event.onAction)
        }
    }

    @Suppress("DEPRECATION")
    private fun showStyledToast(context: Context, message: String, duration: SnackbarDuration) {
        val appContext = context.applicationContext
        Handler(Looper.getMainLooper()).post {
            val paddingHorizontal = appContext.dpToPx(12)
            val paddingVertical = appContext.dpToPx(10)
            val textView = TextView(appContext).apply {
                text = message
                setTextColor(android.graphics.Color.BLACK)
                textSize = 13f
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                typeface = Typeface.DEFAULT
            }

            val container = LinearLayout(appContext).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(paddingHorizontal, paddingVertical, paddingHorizontal, paddingVertical)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = appContext.dpToPx(12).toFloat()
                    setColor(android.graphics.Color.WHITE)
                }
                elevation = appContext.dpToPx(6).toFloat()
                addView(
                    textView,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                )
            }

            Toast(appContext).apply {
                view = container
                setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, appContext.dpToPx(96))
                this.duration = if (duration == SnackbarDuration.Long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
            }.show()
        }
    }
}

@Composable
fun AppNotificationHost(bottomOffset: Dp) {
    val hostState = remember { SnackbarHostState() }

    DisposableEffect(Unit) {
        AppNotificationManager.registerHost()
        onDispose {
            AppNotificationManager.unregisterHost()
        }
    }

    LaunchedEffect(hostState) {
        AppNotificationManager.collectNotifications { message, duration, actionLabel, onAction ->
            hostState.currentSnackbarData?.dismiss()
            hostState.showSnackbar(
                AppNotificationVisuals(
                    message = message,
                    actionLabel = actionLabel,
                    duration = duration,
                    onAction = onAction
                )
            )
        }
    }

    SnackbarHost(
        hostState = hostState,
        modifier = Modifier.padding(bottom = bottomOffset),
        snackbar = { data ->
            AppNotificationCard(data.visuals.message, data, data.visuals as? AppNotificationVisuals)
        }
    )
}

@Composable
private fun AppNotificationCard(
    message: String,
    snackbarData: androidx.compose.material3.SnackbarData,
    visuals: AppNotificationVisuals?
) {
    Surface(
        modifier = Modifier
            .padding(horizontal = 12.dp)
            .heightIn(min = 52.dp)
            .shadow(6.dp, RoundedCornerShape(12.dp)),
        color = PureWhite,
        contentColor = Color.Black,
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Text(
                text = message,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Normal,
                color = Color.Black,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            val actionLabel = visuals?.actionLabel
            if (!actionLabel.isNullOrBlank()) {
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = actionLabel,
                    modifier = Modifier
                        .heightIn(min = 24.dp)
                        .clickable {
                            snackbarData.dismiss()
                            visuals.onAction?.invoke()
                        }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF00C853)
                )
            }
        }
    }
}

private fun Context.dpToPx(dp: Int): Int =
    (dp * resources.displayMetrics.density).toInt()
