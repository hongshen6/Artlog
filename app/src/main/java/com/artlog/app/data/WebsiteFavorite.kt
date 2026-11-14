package com.artlog.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 网站收藏（Artlog 2.0 导航改造，最小 v5 的新表 `website_favorites`）。
 *
 * 依据：《数据库 v4 复用评估》§3 与《视觉灵感网站导航架构设计》§3.2。
 * 与旧作品收藏表 `inspiration_favorites` 严格区分：不同表、不同
 * Flow、不同备份计数、不同 UI 入口。
 *
 * - 官方站收藏只存 [siteId]（slug）+ [savedAt] + [note]；名称、URL、
 *   分类读时从 [VisualDirectory] 静态配置解析，目录更新自动生效。
 * - 自定义网站与收藏共用本表（[isCustom] = true 的行）：名称与 URL
 *   存行内，不另建表；分类只在自定义行内用逗号拼接保存。
 * - 自定义站的 [siteId] 为 `custom:<uuid>` 式 ID；官方站为 slug。
 *
 * 最小 v5 起注册进 [ArtworkDatabase]（entities 清单 +
 * MIGRATION_4_5 建表），经 [WebsiteFavoriteDao] 与
 * [WebsiteFavoriteRepository] 接线；备份/恢复经 [BackupManager]
 * 按网站收藏单独计数、整表交换。
 */
@Entity(
    tableName = "website_favorites",
    indices = [Index("savedAt")]
)
data class WebsiteFavorite(
    /** 官方站存 slug（如 "chinachahua"）；自定义站存 `custom:<uuid>` 式 ID。 */
    @PrimaryKey val siteId: String,
    /** 自定义站存用户输入的 URL；官方站可存空或冗余存一份首页 URL，以静态配置为准。 */
    val url: String,
    /** 仅自定义站用（官方站读时从静态配置取名称）。 */
    val name: String?,
    /** false 官方 / true 自定义。 */
    val isCustom: Boolean,
    /** 仅自定义站用，逗号拼分类名；官方站分类以静态配置为准。 */
    val categories: String?,
    /** 个人备注，用户手写文本，不自动生成。 */
    val note: String = "",
    /** 收藏时间（毫秒时间戳）。 */
    val savedAt: Long,
)
