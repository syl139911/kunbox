package com.kunk.singbox.core

import android.util.Log
import io.nekohasekai.libbox.CommandClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 注释已清理。
 *
 * 注释已清理。
 *
 * 注释已清理。
 * 注释已清理。
 * 注释已清理。
 * 注释已清理。
 *
 * 注释已清理。
 * 注释已清理。
 * 注释已清理。
 * 注释已清理。
 * 注释已清理。
 */
object SelectorManager {
    private const val TAG = "SelectorManager"

    @Volatile
    private var currentSelectorSignature: String? = null

    @Volatile
    private var currentOutboundTags: List<String> = emptyList()

    private val _selectedOutbound = MutableStateFlow<String?>(null)
    val selectedOutbound: StateFlow<String?> = _selectedOutbound.asStateFlow()

    // 注释已清理。
    private val _canHotSwitch = MutableStateFlow(false)
    val canHotSwitchFlow: StateFlow<Boolean> = _canHotSwitch.asStateFlow()

    /**
     * 注释已清理。
     *
     * 注释已清理。
     *
     * 注释已清理。
     * 注释已清理。
     */
    fun recordSelectorSignature(outboundTags: List<String>, selectedTag: String? = null) {
        currentOutboundTags = outboundTags.toList()
        currentSelectorSignature = computeSignature(outboundTags)
        _canHotSwitch.value = outboundTags.isNotEmpty()
        if (selectedTag != null) {
            _selectedOutbound.value = selectedTag
        }
        Log.d(TAG, "Recorded selector: ${outboundTags.size} outbounds, sig=$currentSelectorSignature, selected=$selectedTag")
    }

    /**
     * 注释已清理。
     *
     * 注释已清理。
     * 注释已清理。
     * 注释已清理。
     *
     * 注释已清理。
     * 注释已清理。
     */
    fun canHotSwitch(newOutboundTags: List<String>): Boolean {
        val currentSig = currentSelectorSignature ?: return false
        val newSig = computeSignature(newOutboundTags)
        val canSwitch = currentSig == newSig
        Log.d(TAG, "canHotSwitch: current=$currentSig, new=$newSig, result=$canSwitch")
        return canSwitch
    }

    fun isNodeInCurrentSelector(nodeTag: String): Boolean {
        return currentOutboundTags.contains(nodeTag)
    }

    /**
     * 注释已清理。
     *
     * 注释已清理。
     * 注释已清理。
     * 注释已清理。
     * @return true if successful
     */
    fun selectOutbound(client: CommandClient, selectorTag: String, outboundTag: String): Boolean {
        return try {
            client.selectOutbound(selectorTag, outboundTag)
            _selectedOutbound.value = outboundTag
            Log.i(TAG, "Hot switch via CommandClient: $selectorTag -> $outboundTag")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Hot switch via CommandClient failed: ${e.message}")
            false
        }
    }

    /**
     * 注释已清理。
     *
     * 注释已清理。
     * @return true if successful
     */
    fun selectOutboundViaWrapper(outboundTag: String): Boolean {
        val success = BoxWrapperManager.selectOutbound(outboundTag)
        if (success) {
            _selectedOutbound.value = outboundTag
            Log.i(TAG, "Hot switch via BoxWrapper: -> $outboundTag")
        }
        return success
    }

    /**
     * 注释已清理。
     */
    fun getSelectedOutbound(): String? = _selectedOutbound.value

    /**
     * 注释已清理。
     */
    fun getCurrentOutboundTags(): List<String> = currentOutboundTags

    /**
     * 注释已清理。
     */
    fun hasSelector(): Boolean = currentSelectorSignature != null && currentOutboundTags.isNotEmpty()

    /**
     * 注释已清理。
     */
    fun clear() {
        currentSelectorSignature = null
        currentOutboundTags = emptyList()
        _selectedOutbound.value = null
        _canHotSwitch.value = false
        Log.d(TAG, "Selector state cleared")
    }

    /**
     * 注释已清理。
     * 注释已清理。
     */
    private fun computeSignature(tags: List<String>): String {
        return tags.sorted().hashCode().toString()
    }
}
