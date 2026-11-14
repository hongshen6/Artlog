package com.artlog.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 用户对官方目录网站的覆盖状态（下一阶段迭代，数据库 v6 新表
 * `website_overrides`）。
 *
 * 与 `website_favorites`（收藏）严格区分：收藏表语义是“行存在 =
 * 已收藏”，取消收藏即删行，无法区分“从未收藏”与“用户删了该
 * 站”；本表语义是“行存在 = 用户覆盖过该站”，行保留在库里，发版
 * 更新静态目录时覆盖行原样保留，被删的站不会复活。
 *
 * - 官方站以 slug 为键（[siteId]）；自定义站不进本表（自定义站
 *   直接改 `website_favorites` 的行）。
 * - [isDeleted] = true 时该站在展示层隐藏；删除覆盖行即恢复默认。
 * - [nameOverride] / [descriptionOverride] / [urlOverride] /
 *   [categoriesOverride] 为空时用静态配置的值；非空时替换对应
 *   字段（URL 覆盖须过安全门 [isValidHttpUrl]，见 [resolveDirectory]）。
 *   简介覆盖为空白时同样回落到静态简介（与名称覆盖一致的
 *   `takeIf { it.isNotBlank() }` 语义）；与静态简介相同的值不写
 *   入覆盖行（见 ViewModel.editOfficial）。
 * - [categoriesOverride] 为逗号拼枚举名，复用
 *   [WebsiteFavoriteRepository.encodeCategories] /
 *   [WebsiteFavoriteRepository.decodeCategories]。
 *
 * 最小 v6 起注册进 [ArtworkDatabase]（entities 清单 +
 * MIGRATION_5_6 建表），经 [WebsiteOverrideDao] 与
 * [WebsiteOverrideRepository] 接线；随库备份（manifest 计数字段
 * `websiteOverrideCount`）。
 */
@Entity(
    tableName = "website_overrides",
    indices = [Index("updatedAt")]
)
data class WebsiteOverride(
    /** 官方站 slug（如 "chinachahua"）。 */
    @PrimaryKey val siteId: String,
    /** true = 用户删除（隐藏）该站，行保留，目录更新后不复活。 */
    val isDeleted: Boolean,
    /** 空 = 用静态配置名。 */
    val nameOverride: String?,
    /** 空 = 用静态配置简介；空白同样回落到静态简介。 */
    val descriptionOverride: String?,
    /** 空 = 用静态配置首页；非空须过 isValidHttpUrl。 */
    val urlOverride: String?,
    /** 空 = 用静态配置分类；非空为逗号拼枚举名。 */
    val categoriesOverride: String?,
    /** 最后修改时间（毫秒时间戳）。 */
    val updatedAt: Long,
)
