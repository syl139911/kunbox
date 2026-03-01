package com.kunk.singbox.service.manager

import android.util.Log
import com.kunk.singbox.core.SelectorManager as CoreSelectorManager
import io.nekohasekai.libbox.CommandClient
import kotlinx.coroutines.flow.StateFlow

/**
 * 注释已清理。
 * 注释已清理。
 * 注释已清理。
 *
 * 注释已清理。
 * 注释已清理。
 * 2. BoxWrapperManager (濠㈣泛娲ㄩ弫?
 * 注释已清理。
 */
class SelectorManager {
    companion object {
        private const val TAG = "SelectorManager"
        private const val PROXY_SELECTOR_TAG = "PROXY"
    }

    private var commandClient: CommandClient? = null

    /**
     * 注释已清理。
     */
    sealed class SwitchResult {
        data class Success(val nodeTag: String, val method: String) : SwitchResult()
        data class NeedRestart(val nodeTag: String, val reason: String) : SwitchResult()
        data class Failed(val error: String) : SwitchResult()
    }

    /**
     * 注释已清理。
     */
    fun init(commandClient: CommandClient?): Result<Unit> {
        return runCatching {
            this.commandClient = commandClient
            Log.i(TAG, "SelectorManager initialized, commandClient=${commandClient != null}")
        }
    }

    /**
     * 注释已清理。
     */
    fun recordSelector(outboundTags: List<String>, selectedTag: String?): Result<Unit> {
        return runCatching {
            CoreSelectorManager.recordSelectorSignature(outboundTags, selectedTag)
            Log.i(TAG, "Recorded ${outboundTags.size} outbounds, selected=$selectedTag")
        }
    }

    fun canHotSwitch(nodeTag: String): Boolean {
        return CoreSelectorManager.hasSelector() &&
            CoreSelectorManager.isNodeInCurrentSelector(nodeTag)
    }

    /**
     * 注释已清理。
     */
    fun switchNode(nodeTag: String): SwitchResult {
        if (!canHotSwitch(nodeTag)) {
            return SwitchResult.NeedRestart(nodeTag, "Node not in current selector")
        }

        // 注释已清理。
        commandClient?.let { client ->
            try {
                val success = CoreSelectorManager.selectOutbound(client, PROXY_SELECTOR_TAG, nodeTag)
                if (success) {
                    Log.i(TAG, "Hot switch via CommandClient: -> $nodeTag")
                    return SwitchResult.Success(nodeTag, "CommandClient")
                }
            } catch (e: Exception) {
                Log.w(TAG, "CommandClient switch failed: ${e.message}")
            }
            Unit
        }

        // 注释已清理。
        try {
            val success = CoreSelectorManager.selectOutboundViaWrapper(nodeTag)
            if (success) {
                Log.i(TAG, "Hot switch via BoxWrapper: -> $nodeTag")
                return SwitchResult.Success(nodeTag, "BoxWrapper")
            }
        } catch (e: Exception) {
            Log.w(TAG, "BoxWrapper switch failed: ${e.message}")
        }

        return SwitchResult.NeedRestart(nodeTag, "All hot switch methods failed")
    }

    /**
     * 注释已清理。
     */
    fun getSelectedOutbound(): String? = CoreSelectorManager.getSelectedOutbound()

    /**
     * 注释已清理。
     */
    fun getSelectedOutboundFlow(): StateFlow<String?> = CoreSelectorManager.selectedOutbound

    /**
     * 注释已清理。
     */
    fun getCurrentOutbounds(): List<String> = CoreSelectorManager.getCurrentOutboundTags()

    /**
     * 注释已清理。
     */
    fun hasSelector(): Boolean = CoreSelectorManager.hasSelector()

    /**
     * 注释已清理。
     */
    fun getCanHotSwitchFlow(): StateFlow<Boolean> = CoreSelectorManager.canHotSwitchFlow

    /**
     * 注释已清理。
     */
    fun clear(): Result<Unit> {
        return runCatching {
            CoreSelectorManager.clear()
            commandClient = null
            Log.i(TAG, "SelectorManager cleared")
        }
    }

    /**
     * 注释已清理。
     */
    fun updateCommandClient(client: CommandClient?) {
        this.commandClient = client
    }
}
