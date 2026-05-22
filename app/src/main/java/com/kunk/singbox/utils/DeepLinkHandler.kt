package com.kunk.singbox.utils

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 */
object DeepLinkHandler {

    data class SubscriptionImportData(
        val name: String,
        val url: String,
        val autoUpdateInterval: Int
    )

    private val _pendingSubscriptionImport = MutableStateFlow<SubscriptionImportData?>(null)
    val pendingSubscriptionImport: StateFlow<SubscriptionImportData?> = _pendingSubscriptionImport.asStateFlow()

    // tbox:// single node import
    private val _pendingTboxImport = MutableStateFlow<String?>(null)
    val pendingTboxImport: StateFlow<String?> = _pendingTboxImport.asStateFlow()

    fun setPendingTboxImport(base64Data: String) {
        _pendingTboxImport.value = base64Data
    }

    fun clearPendingTboxImport() {
        _pendingTboxImport.value = null
    }

    /**
     * 璁剧疆寰呭鐞嗙殑璁㈤槄瀵煎叆鏁版嵁
     */
    fun setPendingSubscriptionImport(name: String, url: String, interval: Int) {
        _pendingSubscriptionImport.value = SubscriptionImportData(name, url, interval)
    }

    /**
     * 娓呴櫎寰呭鐞嗙殑璁㈤槄瀵煎叆鏁版嵁
     */
    fun clearPendingSubscriptionImport() {
        _pendingSubscriptionImport.value = null
    }
}
