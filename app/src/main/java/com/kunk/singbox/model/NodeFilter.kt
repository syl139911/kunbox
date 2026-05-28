package com.kunk.singbox.model

import com.google.gson.annotations.SerializedName

// 鏉╁洦鎶ゅΟ鈥崇础閺嬫矮濡?
enum class FilterMode {
    @SerializedName("NONE") NONE,
    @SerializedName("INCLUDE") INCLUDE,
    // 排除指定节点
}

data class NodeFilter(
    @SerializedName("filterMode") val filterMode: FilterMode = FilterMode.NONE,
    @SerializedName("includeKeywords") val includeKeywords: List<String> = emptyList(),
    @SerializedName("excludeKeywords") val excludeKeywords: List<String> = emptyList(),
    @Deprecated("Use includeKeywords/excludeKeywords instead")
    @SerializedName("keywords") val keywords: List<String> = emptyList()
) {

    val effectiveIncludeKeywords: List<String>
        get() = includeKeywords.ifEmpty {
            if (filterMode == FilterMode.INCLUDE) keywords else emptyList()
        }

    val effectiveExcludeKeywords: List<String>
        get() = excludeKeywords.ifEmpty {
            if (filterMode == FilterMode.EXCLUDE) keywords else emptyList()
        }
}
