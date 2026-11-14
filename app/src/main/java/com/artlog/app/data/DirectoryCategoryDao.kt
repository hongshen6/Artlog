package com.artlog.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * 目录分类的数据访问（下一阶段迭代，数据库 v6 新表
 * `directory_categories`，见 [DirectoryCategory]）。最小 v6 起注册
 * 进 [ArtworkDatabase]（MIGRATION_5_6 建表），经
 * [DirectoryCategoryRepository] 与 [BackupManager] 接线。
 */
@Dao
interface DirectoryCategoryDao {

    /** 按分类 ID 查询一行；不存在返回 null。 */
    @Query("SELECT * FROM directory_categories WHERE id = :id")
    suspend fun getCategory(id: String): DirectoryCategory?

    /** 全部分类（按 sortOrder 升序），供分类管理与备份计数使用。 */
    @Query("SELECT * FROM directory_categories ORDER BY sortOrder ASC")
    fun observeCategories(): Flow<List<DirectoryCategory>>

    /** 未隐藏的分类（按 sortOrder 升序），供目录入口展示使用。 */
    @Query("SELECT * FROM directory_categories WHERE isHidden = 0 ORDER BY sortOrder ASC")
    fun observeVisibleCategories(): Flow<List<DirectoryCategory>>

    /** 分类行数（种子判断与备份校验用）。 */
    @Query("SELECT COUNT(*) FROM directory_categories")
    suspend fun countCategories(): Int

    /** 当前最大 sortOrder；空表返回 null（种子/新增时定序用）。 */
    @Query("SELECT MAX(sortOrder) FROM directory_categories")
    suspend fun maxSortOrder(): Int?

    /** 插入或覆盖一行（种子 INSERT OR IGNORE 之外的写入走 REPLACE）。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCategory(category: DirectoryCategory)

    /** 更新一行分类。 */
    @Update
    suspend fun updateCategory(category: DirectoryCategory)

    /** 按分类 ID 删除一行（仅自定义分类可删，见 Repository）。 */
    @Query("DELETE FROM directory_categories WHERE id = :id")
    suspend fun deleteCategory(id: String)

    /** 删除全部分类行（仅备份恢复的整表交换用）。 */
    @Query("DELETE FROM directory_categories")
    suspend fun deleteAllCategories()
}
