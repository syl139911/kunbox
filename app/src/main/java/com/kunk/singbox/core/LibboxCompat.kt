package com.kunk.singbox.core

import android.util.Log
import io.nekohasekai.libbox.Libbox
import java.lang.reflect.Method

/**
 * [乱码注释已清理]
 *
 * [乱码注释已清理]
 */
object LibboxCompat {
    private const val TAG = "LibboxCompat"

    private var resetAllConnectionsMethod: Method? = null
    private var resetAllConnectionsChecked = false

    @Volatile
    var hasResetAllConnections: Boolean = false
        private set

    @Volatile
    var hasExtensionApi: Boolean = false
        private set

    init {
        detectAvailableApis()
    }

    private fun detectAvailableApis() {
        resetAllConnectionsMethod = try {
            Libbox::class.java.getMethod("resetAllConnections", Boolean::class.javaPrimitiveType).also {
                hasResetAllConnections = true
                Log.i(TAG, "Detected Libbox.resetAllConnections(boolean)")
            }
        } catch (e: NoSuchMethodException) {
            try {
                Libbox::class.java.getMethod("ResetAllConnections", Boolean::class.javaPrimitiveType).also {
                    hasResetAllConnections = true
                    Log.i(TAG, "Detected Libbox.ResetAllConnections(boolean)")
                }
            } catch (e2: NoSuchMethodException) {
                Log.d(TAG, "Libbox.resetAllConnections not available")
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error detecting resetAllConnections: ${e.message}")
            null
        }
        resetAllConnectionsChecked = true
        hasExtensionApi = try {
            Libbox::class.java.getMethod("getKunBoxVersion")
            Log.i(TAG, "Detected extension API")
            true
        } catch (e: Exception) {
            Log.d(TAG, "Extension API not available")
            false
        }
    }

    /**
     * [乱码注释已清理]
     * [乱码注释已清理]
     * [乱码注释已清理]
     */
    fun resetAllConnections(system: Boolean = true): Boolean {
        // 浼樺厛浣跨敤 BoxWrapperManager
        if (BoxWrapperManager.isAvailable()) {
            return BoxWrapperManager.resetAllConnections(system)
        }

        val method = resetAllConnectionsMethod ?: return false

        return try {
            method.invoke(null, system)
            Log.i(TAG, "Called Libbox.resetAllConnections($system)")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to call resetAllConnections: ${e.message}")
            false
        }
    }

    fun getVersion(): String {
        return try {
            Libbox.version()
        } catch (e: Exception) {
            "unknown"
        }
    }

    /**
     * 鑾峰彇鎵╁睍鐗堟湰
     */
    fun getExtensionVersion(): String {
        return try {
            Libbox.getKunBoxVersion()
        } catch (e: Exception) {
            "N/A"
        }
    }

    fun hasExtendedLibbox(): Boolean = hasResetAllConnections

    fun hasKunBoxExtension(): Boolean = hasExtensionApi

    fun printDiagnostics() {
        Log.i(TAG, "LibboxCompat: version=${getVersion()}, extensionVersion=${getExtensionVersion()}, hasResetAllConnections=$hasResetAllConnections, hasExtensionApi=$hasExtensionApi")
    }
}
