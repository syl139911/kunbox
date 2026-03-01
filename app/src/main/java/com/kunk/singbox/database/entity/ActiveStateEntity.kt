package com.kunk.singbox.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 娲昏穬鐘舵€佸疄浣?
 *
 * [乱码注释已清理]
 * [乱码注释已清理]
 */
@Entity(tableName = "active_state")
data class ActiveStateEntity(
    @PrimaryKey
    val id: Int = 1,
    val activeProfileId: String?,
    val activeNodeId: String?
)
