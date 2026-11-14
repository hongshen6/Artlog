package com.artlog.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 目录分类（下一阶段迭代，数据库 v6 新表 `directory_categories`）。
 *
 * - 内置分类 5 个，[id] 为枚举名（ANIME / REALISTIC / PHOTOGRAPHY /
 *   ARCHITECTURE / LANDSCAPE），[isBuiltin] = true；种子行由
 *   MIGRATION_5_6 写入（升级）或由
 *   [DirectoryCategoryRepository.ensureSeeded] 补写（全新安装：
 *   Room 全新安装只建表、不跑迁移，种子须由 Repository 补）。
 * - 自定义分类 [id] 为 `cat:<uuid>`，[isBuiltin] = false。
 * - 内置分类不可删除、不可改名，只可隐藏与排序；自定义分类可增
 *   删改名排序。删除自定义分类时不删站，只解除归属（引用它的自
 *   定义站分类串在读时自动丢弃该 token）。
 * - [sortOrder] 数值越小越靠前；[isHidden] 的分类入口不展示，但
 *   站仍可在“全部”与“我的收藏”中见到。
 *
 * 最小 v6 起注册进 [ArtworkDatabase]（entities 清单 +
 * MIGRATION_5_6 建表），经 [DirectoryCategoryDao] 与
 * [DirectoryCategoryRepository] 接线；随库备份（manifest 计数字段
 * `directoryCategoryCount`）。
 */
@Entity(
    tableName = "directory_categories",
    indices = [Index("sortOrder")]
)
data class DirectoryCategory(
    /** 内置：枚举名；自定义：`cat:<uuid>`。 */
    @PrimaryKey val id: String,
    /** 显示名；内置初始值取 InspirationCategory.label。 */
    val name: String,
    /** 展示顺序，数值越小越靠前。 */
    val sortOrder: Int,
    /** true 内置 / false 自定义。 */
    val isBuiltin: Boolean,
    /** true = 入口隐藏（内置可隐藏、不可删除）。 */
    val isHidden: Boolean,
    /** 创建时间（毫秒时间戳；种子行用固定值，见 MIGRATION_5_6）。 */
    val createdAt: Long,
)
