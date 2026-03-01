package com.kunk.singbox.model

import androidx.annotation.Keep
import com.google.gson.annotations.SerializedName

/**
 */
@Keep
data class ExportData(
    @SerializedName("version") val version: Int = 1,
    @SerializedName("exportTime") val exportTime: Long,
    @SerializedName("appVersion") val appVersion: String,
    @SerializedName("settings") val settings: AppSettings, // 閹煎瓨姊婚弫·囨媼閸撗呮瀭
    @SerializedName("activeProfileId") val activeProfileId: String?,
    @SerializedName("activeNodeId") val activeNodeId: String?
)

/**
 */
@Keep
data class ProfileExportData(
    @SerializedName("config") val config: SingBoxConfig
)

/**
 */
@Keep
data class ImportOptions(
    val overwriteExisting: Boolean = true,
    val importSettings: Boolean = true, // ·哄嫷鍨伴幆浣衡·鐢靛帶閸欏棛鎷嬮崜褏鏋?
    val importProfiles: Boolean = true, // ·哄嫷鍨伴幆浣衡·鐢靛帶閸欏棝鏌婂鍥╂瀭
    val importRules: Boolean = true // ·哄嫷鍨伴幆浣衡·鐢靛帶閸欏棛鎲撮崟顐㈢仧
)

/**
 */
@Keep
sealed class ImportResult {
    /**
     */
    data class Success(
        val profilesImported: Int,
        val nodesImported: Int,
        val settingsImported: Boolean
    ) : ImportResult()

    /**
     */
    data class PartialSuccess(
        val profilesImported: Int,
        val profilesFailed: Int,
        val errors: List<String>
    ) : ImportResult()

    /**
     */
    data class Failed(val error: String) : ImportResult()
}

/**
 */
@Keep
data class ExportDataSummary(
    val version: Int,
    val exportTime: Long,
    val appVersion: String,
    val profileCount: Int,
    val totalNodeCount: Int,
    val hasSettings: Boolean,
    val hasCustomRules: Boolean,
    val hasRuleSets: Boolean,
    val hasAppRules: Boolean
)
