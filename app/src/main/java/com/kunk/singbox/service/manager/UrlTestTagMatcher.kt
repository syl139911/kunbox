package com.kunk.singbox.service.manager

object UrlTestTagMatcher {

    data class ResolveDetail(
        val delay: Int,
        val matchType: String,
        val matchedKey: String
    )

    private const val ZERO_WIDTH_NO_BREAK_SPACE = "\uFE0F"
    private const val ZERO_WIDTH_SPACE = "\u200B"
    private const val ZERO_WIDTH_NON_JOINER = "\u200C"
    private const val ZERO_WIDTH_JOINER = "\u200D"
    private const val NO_BREAK_SPACE = "\u00A0"

    fun resolveDelay(
        results: Map<String, Int>,
        queryTag: String,
        aliasTags: List<String> = emptyList()
    ): Pair<Int, String>? {
        val detail = resolveDelayDetail(results, queryTag, aliasTags) ?: return null
        return detail.delay to detail.matchType
    }

    fun resolveDelayDetail(
        results: Map<String, Int>,
        queryTag: String,
        aliasTags: List<String> = emptyList()
    ): ResolveDetail? {
        val direct = results[queryTag]
        if (direct != null && direct > 0) {
            return ResolveDetail(delay = direct, matchType = "direct", matchedKey = queryTag)
        }

        val targets = buildTargets(queryTag, aliasTags)
        val normalizedTargets = targets.asSequence()
            .map(::normalizeTag)
            .filter { it.isNotBlank() }
            .toSet()
        val normalized = results.entries.firstOrNull { (key, value) ->
            value > 0 && normalizedTargets.contains(normalizeTag(key))
        }

        val fingerprintTargets = targets.asSequence()
            .map(::fingerprintTag)
            .filter { it.isNotBlank() }
            .toSet()
        val fingerprint = results.entries.firstOrNull { (key, value) ->
            value > 0 && fingerprintTargets.contains(fingerprintTag(key))
        }

        return when {
            normalized != null -> ResolveDetail(
                delay = normalized.value,
                matchType = "normalized",
                matchedKey = normalized.key
            )

            fingerprint != null -> ResolveDetail(
                delay = fingerprint.value,
                matchType = "fingerprint",
                matchedKey = fingerprint.key
            )

            else -> null
        }
    }

    fun normalizeTag(tag: String): String {
        return tag.trim()
            .replace(ZERO_WIDTH_NO_BREAK_SPACE, "")
            .replace(ZERO_WIDTH_SPACE, "")
            .replace(ZERO_WIDTH_NON_JOINER, "")
            .replace(ZERO_WIDTH_JOINER, "")
            .replace(NO_BREAK_SPACE, " ")
            .replace(Regex("\\s+"), " ")
            .lowercase()
    }

    fun fingerprintTag(tag: String): String {
        return normalizeTag(tag)
            .filter { it.isLetterOrDigit() }
    }

    private fun buildTargets(queryTag: String, aliasTags: List<String>): List<String> {
        return buildList {
            add(queryTag)
            aliasTags.forEach { alias ->
                if (alias.isNotBlank()) {
                    add(alias)
                }
            }
        }.distinct()
    }
}
