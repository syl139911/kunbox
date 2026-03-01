package com.kunk.singbox.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.kunk.singbox.database.entity.SettingsEntity
import kotlinx.coroutines.flow.Flow

/**
 * [涔辩爜娉ㄩ噴宸叉竻鐞哴
 *
 * [涔辩爜娉ㄩ噴宸叉竻鐞哴
 * [涔辩爜娉ㄩ噴宸叉竻鐞哴
 * [涔辩爜娉ㄩ噴宸叉竻鐞哴
 */
@Dao
interface SettingsDao {

    /**
     * [涔辩爜娉ㄩ噴宸叉竻鐞哴
     */
    @Query("SELECT * FROM settings WHERE id = 1")
    fun observeSettings(): Flow<SettingsEntity?>

    /**
     * 閼惧嘲褰囪ぐ鎾冲鐠佸墽鐤?(閹稿倽鎹ｉ崙鑺ユ殶)
     */
    @Query("SELECT * FROM settings WHERE id = 1")
    suspend fun getSettings(): SettingsEntity?

    /**
     */
    @Query("SELECT * FROM settings WHERE id = 1")
    fun getSettingsSync(): SettingsEntity?

    /**
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveSettings(settings: SettingsEntity)

    /**
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun saveSettingsSync(settings: SettingsEntity)

    /**
     */
    @Query("DELETE FROM settings")
    suspend fun deleteSettings()

    /**
     * [涔辩爜娉ㄩ噴宸叉竻鐞哴
     */
    @Query("SELECT EXISTS(SELECT 1 FROM settings WHERE id = 1)")
    suspend fun hasSettings(): Boolean

    /**
     * [涔辩爜娉ㄩ噴宸叉竻鐞哴
     */
    @Query("SELECT EXISTS(SELECT 1 FROM settings WHERE id = 1)")
    fun hasSettingsSync(): Boolean
}
