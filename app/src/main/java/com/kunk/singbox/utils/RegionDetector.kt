package com.kunk.singbox.utils

import java.util.Collections

object RegionDetector {

    private const val MAX_CACHE_SIZE = 2000
    private const val UNKNOWN_REGION = "UNKNOWN"

    private data class RegionRule(
        val code: String,
        val keywords: List<String>,
        val wordBoundaryKeywords: List<String>
    )

    private val REGION_RULES = listOf(
        RegionRule("HK", listOf("hong kong", "hk"), listOf("hk")),
        RegionRule("TW", listOf("taiwan", "tw"), listOf("tw")),
        RegionRule("JP", listOf("japan", "tokyo", "jp"), listOf("jp")),
        RegionRule("SG", listOf("singapore", "sg"), listOf("sg")),
        RegionRule("US", listOf("united states", "america", "usa", "us"), listOf("us", "usa")),
        RegionRule("KR", listOf("korea", "kr"), listOf("kr")),
        RegionRule("UK", listOf("britain", "england", "uk", "gb"), listOf("uk", "gb")),
        RegionRule("DE", listOf("germany", "de"), listOf("de")),
        RegionRule("FR", listOf("france", "fr"), listOf("fr")),
        RegionRule("CA", listOf("canada", "ca"), listOf("ca")),
        RegionRule("AU", listOf("australia", "au"), listOf("au")),
        RegionRule("RU", listOf("russia", "ru"), listOf("ru")),
        RegionRule("IN", listOf("india", "in"), listOf("in")),
        RegionRule("BR", listOf("brazil", "br"), listOf("br")),
        RegionRule("NL", listOf("netherlands", "nl"), listOf("nl")),
        RegionRule("TR", listOf("turkey", "tr"), listOf("tr")),
        RegionRule("AR", listOf("argentina", "ar"), listOf("ar")),
        RegionRule("MY", listOf("malaysia", "my"), listOf("my")),
        RegionRule("TH", listOf("thailand", "th"), listOf("th")),
        RegionRule("VN", listOf("vietnam", "vn"), listOf("vn")),
        RegionRule("PH", listOf("philippines", "ph"), listOf("ph")),
        RegionRule("ID", listOf("indonesia", "id"), listOf("id"))
    )

    private val WORD_BOUNDARY_REGEX_MAP: Map<String, Regex> = REGION_RULES
        .flatMap { it.wordBoundaryKeywords }
        .associateWith { word -> Regex("(^|[^a-z])${Regex.escape(word)}([^a-z]|$)") }

    private val cache: MutableMap<String, String> = Collections.synchronizedMap(
        object : LinkedHashMap<String, String>(MAX_CACHE_SIZE, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
                return size > MAX_CACHE_SIZE
            }
        }
    )

    @Suppress("ReturnCount")
    fun detect(name: String): String {
        cache[name]?.let { return it }

        val lowerName = name.lowercase()

        for (rule in REGION_RULES) {
            if (rule.keywords.any { lowerName.contains(it) }) {
                cache[name] = rule.code
                return rule.code
            }

            if (rule.wordBoundaryKeywords.any { word ->
                    WORD_BOUNDARY_REGEX_MAP[word]?.containsMatchIn(lowerName) == true
                }) {
                cache[name] = rule.code
                return rule.code
            }
        }

        cache[name] = UNKNOWN_REGION
        return UNKNOWN_REGION
    }

    fun containsFlagEmoji(str: String): Boolean {
        var i = 0
        while (i < str.length - 1) {
            val cp = Character.codePointAt(str, i)
            if (cp in 0x1F1E6..0x1F1FF) {
                val nextIndex = i + Character.charCount(cp)
                if (nextIndex < str.length) {
                    val nextCp = Character.codePointAt(str, nextIndex)
                    if (nextCp in 0x1F1E6..0x1F1FF) return true
                }
            }
            i += Character.charCount(cp)
        }
        return false
    }

    fun clearCache() {
        cache.clear()
    }
}
