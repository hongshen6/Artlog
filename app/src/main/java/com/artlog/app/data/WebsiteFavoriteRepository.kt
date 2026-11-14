package com.artlog.app.data

import kotlinx.coroutines.flow.Flow
import java.util.UUID

/**
 * 网站收藏的 Repository（Artlog 2.0 导航改造，最小 v5）。
 *
 * 与旧作品收藏（[InspirationRepository] + `inspiration_favorites`）
 * 严格区分：不同表、不同 DAO、不同 Flow、不同备份计数。旧作品
 * 收藏绝不转成网站收藏；官方站收藏也绝不写入旧收藏表。
 *
 * 存储规则（见 [WebsiteFavorite]）：
 * - 官方站：只存 [WebsiteFavorite.siteId]（slug）+ [WebsiteFavorite.url]
 *   （首页 URL 冗余存入，读时仍以 [VisualDirectory] 静态配置为准）
 *   + [WebsiteFavorite.savedAt] + [WebsiteFavorite.note]；名称与分
 *   类读时从静态配置解析，目录更新自动生效。
 * - 自定义站：名称、URL、分类存行内（分类逗号拼接）；[WebsiteFavorite.siteId]
 *   为 `custom:<uuid>` 式 ID。
 *
 * URL 校验复用安全门已有的 [isValidHttpUrl]：仅 http/https，拒绝
 * javascript: / data: / file: / 空白 host / 含空白字符；接受 http；
 * 只做格式校验，不发任何网络请求、不探测可达性。
 *
 * 本类不依赖 Android 框架（除 Room DAO 接口外），可在 JVM 上直接
 * 用假 DAO 测试（见 manual-build/jvm-test/TestWebsiteFavorites.kt）。
 */
class WebsiteFavoriteRepository(private val dao: WebsiteFavoriteDao) {

    /** 全部网站收藏（按收藏时间倒序）。 */
    fun observeFavorites(): Flow<List<WebsiteFavorite>> = dao.observeFavorites()

    /** 按网站 ID 查询一行收藏；未收藏返回 null。 */
    suspend fun getFavorite(siteId: String): WebsiteFavorite? = dao.getFavorite(siteId)

    /** 该网站是否已收藏。 */
    suspend fun isFavorite(siteId: String): Boolean = dao.getFavorite(siteId) != null

    /**
     * 收藏一个官方目录网站（[siteId] 为 slug）。
     * 成功返回 true；[siteId] 不在目录中、或其首页 URL 未通过
     * [isValidHttpUrl] 时返回 false（不写库）。已收藏时幂等：
     * 不重复插行、不覆盖已有备注、不刷新收藏时间。
     */
    suspend fun favoriteOfficial(siteId: String): Boolean {
        val site = VisualDirectory.siteById(siteId) ?: return false
        if (!isValidHttpUrl(site.homepageUrl)) return false
        if (dao.getFavorite(site.id) != null) return true
        dao.insertFavorite(
            WebsiteFavorite(
                siteId = site.id,
                url = site.homepageUrl,
                name = null,
                isCustom = false,
                categories = null,
                note = "",
                savedAt = System.currentTimeMillis()
            )
        )
        return true
    }

    /**
     * 取消收藏 / 删除自定义网站：按 [siteId] 删行（官方站与自定义
     * 站共用同一删除路径；不存在的行视为已删除，不报错）。
     */
    suspend fun removeFavorite(siteId: String) {
        dao.deleteFavorite(siteId)
    }

    /**
     * 新增自定义网站并收藏。名称去空白后不能为空；URL 须通过
     * [isValidHttpUrl]。成功返回新建的行（含生成的 `custom:<uuid>`
     * 式 [WebsiteFavorite.siteId]）；校验失败返回 null（不写库）。
     */
    suspend fun addCustom(
        name: String,
        url: String,
        categories: Set<InspirationCategory>
    ): WebsiteFavorite? = addCustom(name, url, categories, emptySet())

    /**
     * 新增自定义网站并收藏（含自定义分类归属）。与
     * [addCustom] 同款校验；分类用
     * [encodeCategoryTokens] 编码（枚举名 + `cat:<uuid>` token）。
     */
    suspend fun addCustom(
        name: String,
        url: String,
        categories: Set<InspirationCategory>,
        customCategoryIds: Set<String>
    ): WebsiteFavorite? {
        val cleanName = name.trim()
        val cleanUrl = url.trim()
        if (cleanName.isEmpty() || !isValidHttpUrl(cleanUrl)) return null
        val favorite = WebsiteFavorite(
            siteId = "custom:" + UUID.randomUUID().toString(),
            url = cleanUrl,
            name = cleanName,
            isCustom = true,
            categories = encodeCategoryTokens(categories, customCategoryIds),
            note = "",
            savedAt = System.currentTimeMillis()
        )
        dao.insertFavorite(favorite)
        return favorite
    }

    /**
     * 修改一个自定义网站（名称 / URL / 分类）。只允许改自定义行：
     * 行不存在、或行是官方站时返回 false；名称或 URL 校验失败时
     * 返回 false；成功时只改这三个字段，备注与收藏时间保持不变。
     */
    suspend fun updateCustom(
        siteId: String,
        name: String,
        url: String,
        categories: Set<InspirationCategory>
    ): Boolean = updateCustom(siteId, name, url, categories, emptySet())

    /**
     * 修改一个自定义网站（名称 / URL / 分类，含自定义分类归属）。
     * 只允许改自定义行；其余规则与 [updateCustom] 一致。
     */
    suspend fun updateCustom(
        siteId: String,
        name: String,
        url: String,
        categories: Set<InspirationCategory>,
        customCategoryIds: Set<String>
    ): Boolean {
        val existing = dao.getFavorite(siteId) ?: return false
        if (!existing.isCustom) return false
        val cleanName = name.trim()
        val cleanUrl = url.trim()
        if (cleanName.isEmpty() || !isValidHttpUrl(cleanUrl)) return false
        dao.updateFavorite(
            existing.copy(
                name = cleanName,
                url = cleanUrl,
                categories = encodeCategoryTokens(categories, customCategoryIds)
            )
        )
        return true
    }

    /**
     * 更新一行收藏的备注（官方站与自定义站通用）。行不存在返回
     * false；成功时只改备注，其他字段保持不变。
     */
    suspend fun updateNote(siteId: String, note: String): Boolean {
        val existing = dao.getFavorite(siteId) ?: return false
        dao.updateFavorite(existing.copy(note = note))
        return true
    }

    // ---- 读时解析：官方站以静态配置为准，自定义站以行内值为准 ----

    /** 展示用名称：官方站取目录名（目录查不到时退回 slug），自定义站取行内名（为空时退回 URL）。 */
    fun displayName(favorite: WebsiteFavorite): String {
        if (favorite.isCustom) return favorite.name?.takeIf { it.isNotBlank() } ?: favorite.url
        return VisualDirectory.siteById(favorite.siteId)?.name ?: favorite.siteId
    }

    /** 展示 / 打开用 URL：官方站取目录首页（目录查不到时退回行内 URL），自定义站取行内 URL。 */
    fun displayUrl(favorite: WebsiteFavorite): String {
        if (favorite.isCustom) return favorite.url
        return VisualDirectory.siteById(favorite.siteId)?.homepageUrl ?: favorite.url
    }

    /** 展示用分类：官方站取目录分类，自定义站解码行内逗号拼接值。 */
    fun displayCategories(favorite: WebsiteFavorite): Set<InspirationCategory> {
        if (favorite.isCustom) return decodeCategories(favorite.categories)
        return VisualDirectory.siteById(favorite.siteId)?.categories ?: emptySet()
    }

    companion object {
        /**
         * 分类编码：逗号拼接枚举名。ALL（全部分类）与 OTHER（未分
         * 类兜底）不是网站的归属分类，不编码；结果为空时存 null。
         */
        fun encodeCategories(categories: Set<InspirationCategory>): String? =
            categories
                .filter { it != InspirationCategory.ALL && it != InspirationCategory.OTHER }
                .sortedBy { it.name }
                .joinToString(",") { it.name }
                .takeIf { it.isNotEmpty() }

        /**
         * 分类解码：逗号拼接值还原为集合；无法识别的 token 直接丢
         * 弃（不映射为 OTHER，避免把脏数据当成有效分类）。
         */
        fun decodeCategories(raw: String?): Set<InspirationCategory> {
            if (raw.isNullOrBlank()) return emptySet()
            return raw.split(",")
                .mapNotNull { token ->
                    runCatching { InspirationCategory.valueOf(token.trim()) }.getOrNull()
                }
                .filter { it != InspirationCategory.ALL && it != InspirationCategory.OTHER }
                .toSet()
        }

        /**
         * 分类 token 编码（含自定义分类）：枚举名 + `cat:<uuid>` token
         * 逗号拼接。枚举部分与 [encodeCategories] 一致；自定义分类
         * ID 须以 `cat:` 开头且后缀非空，否则丢弃；结果为空时存 null。
         */
        fun encodeCategoryTokens(
            categories: Set<InspirationCategory>,
            customCategoryIds: Set<String> = emptySet()
        ): String? {
            val enumPart = categories
                .filter { it != InspirationCategory.ALL && it != InspirationCategory.OTHER }
                .sortedBy { it.name }
                .map { it.name }
            val customPart = customCategoryIds
                .filter { it.startsWith("cat:") && it.length > "cat:".length }
                .sorted()
            return (enumPart + customPart)
                .joinToString(",")
                .takeIf { it.isNotEmpty() }
        }

        /**
         * 分类 token 解码：逗号拼接值还原为（枚举分类，自定义分类
         * ID）一对。无法识别的 token 直接丢弃（沿用 [decodeCategories]
         * 的容错策略：不映射为 OTHER、不报错）；`cat:` token 只认
         * 后缀非空的。
         */
        fun decodeCategoryTokens(raw: String?): Pair<Set<InspirationCategory>, Set<String>> {
            if (raw.isNullOrBlank()) return emptySet<InspirationCategory>() to emptySet()
            val enums = mutableSetOf<InspirationCategory>()
            val customs = mutableSetOf<String>()
            raw.split(",").forEach { token ->
                val trimmed = token.trim()
                when {
                    trimmed.startsWith("cat:") && trimmed.length > "cat:".length ->
                        customs += trimmed
                    else -> runCatching { InspirationCategory.valueOf(trimmed) }
                        .getOrNull()
                        ?.takeIf { it != InspirationCategory.ALL && it != InspirationCategory.OTHER }
                        ?.let { enums += it }
                }
            }
            return enums to customs
        }
    }
}
