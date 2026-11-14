package com.artlog.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * 网站覆盖的数据访问（下一阶段迭代，数据库 v6 新表
 * `website_overrides`，见 [WebsiteOverride]）。最小 v6 起注册进
 * [ArtworkDatabase]（MIGRATION_5_6 建表），经
 * [WebsiteOverrideRepository] 与 [BackupManager] 接线。
 */
@Dao
interface WebsiteOverrideDao {

    /** 按网站 slug 查询一行覆盖；未覆盖返回 null。 */
    @Query("SELECT * FROM website_overrides WHERE siteId = :siteId")
    suspend fun getOverride(siteId: String): WebsiteOverride?

    /** 全部覆盖行，供读时合并与备份计数使用。 */
    @Query("SELECT * FROM website_overrides ORDER BY siteId ASC")
    fun observeOverrides(): Flow<List<WebsiteOverride>>

    /** 插入或覆盖一行（同一 siteId 再次覆盖时更新整行）。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOverride(override: WebsiteOverride)

    /** 更新一行覆盖。 */
    @Update
    suspend fun updateOverride(override: WebsiteOverride)

    /** 按网站 slug 删除一行覆盖（恢复默认）。 */
    @Query("DELETE FROM website_overrides WHERE siteId = :siteId")
    suspend fun deleteOverride(siteId: String)

    /** 删除全部覆盖行（仅备份恢复的整表交换用）。 */
    @Query("DELETE FROM website_overrides")
    suspend fun deleteAllOverrides()
}
