package com.kunk.singbox.model

enum class NodeSortType {
    DEFAULT, LATENCY, NAME,
    @Deprecated("Legacy value for backward compatibility")
    REGION,
    CUSTOM
}
