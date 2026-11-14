package com.artlog.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * 网站收藏的数据访问（Artlog 2.0 导航改造，最小 v5 的新表
 * `website_favorites`，见 [WebsiteFavorite]）。最小 v5 起注册进
 * [ArtworkDatabase]（MIGRATION_4_5 建表），经
 * [WebsiteFavoriteRepository] 与 [BackupManager] 接线。同一网站在
 * 不同分类出现时收藏状态保持一致：收藏键是网站 ID（[WebsiteFavorite.siteId]），
 * 不是“分类 + 网站”。
 */
@Dao
interface WebsiteFavoriteDao {

    /** 按网站 ID 查询一行收藏；未收藏返回 null。 */
    @Query("SELECT * FROM website_favorites WHERE siteId = :siteId")
    suspend fun getFavorite(siteId: String): WebsiteFavorite?

    /** 全部网站收藏（按收藏时间倒序），供收藏列表与备份计数使用。 */
    @Query("SELECT * FROM website_favorites ORDER BY savedAt DESC")
    fun observeFavorites(): Flow<List<WebsiteFavorite>>

    /** 插入或覆盖一行收藏（同一 siteId 再次收藏时更新时间/备注）。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFavorite(favorite: WebsiteFavorite)

    /** 更新一行收藏（主要用于改备注）。 */
    @Update
    suspend fun updateFavorite(favorite: WebsiteFavorite)

    /** 按网站 ID 删除一行收藏（取消收藏 / 删除自定义网站时同步删行）。 */
    @Query("DELETE FROM website_favorites WHERE siteId = :siteId")
    suspend fun deleteFavorite(siteId: String)

    /** 删除全部网站收藏行（仅备份恢复的整表交换用）。 */
    @Query("DELETE FROM website_favorites")
    suspend fun deleteAllFavorites()
}
