package com.kunk.singbox.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 注释已清理。
 *
 * 注释已清理。
 * 注释已清理。
 * 注释已清理。
 * 注释已清理。
 * 注释已清理。
 */
@Entity(tableName = "settings")
data class SettingsEntity(
    @PrimaryKey
    val id: Int = 1,

    /**
     * 注释已清理。
     */
    val version: Int = CURRENT_VERSION,

    /**
     * 閹兼潙绻愰崹顏堝礌閺嶎偅鐣?AppSettings JSON
     */
    val data: String,

    /**
     * 注释已清理。
     */
    val updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val CURRENT_VERSION = 3
    }
}
