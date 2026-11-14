package com.artlog.app.ui.directory

import com.artlog.app.data.DirectoryCategory
import com.artlog.app.data.DirectoryCategoryRepository
import com.artlog.app.data.InspirationCategory
import com.artlog.app.data.VisualDirectory
import com.artlog.app.data.VisualWebsite
import com.artlog.app.data.WebsiteFavorite
import com.artlog.app.data.WebsiteFavoriteRepository
import com.artlog.app.data.WebsiteOverride
import com.artlog.app.data.isValidHttpUrl

/**
 * 视觉灵感导航的纯展示逻辑（Artlog 2.0 阶段 2）。
 *
 * 本文件不依赖任何 Android 框架类（只有纯 Kotlin + 数据层类型），
 * 因此可在 JVM 上直接测试（见 manual-build/jvm-test/
 * TestDirectoryLogic.kt）。ViewModel 与 Composable 只做接线与展示，
 * 计数、域名提取、自定义输入校验、分类合并都在这里。
 *
 * 规则（与架构设计 §2 / 目录文件一致）：
 * - 五个分类入口的计数只计正式收录（A 级静态目录），B/C/D 级不计；
 *   风景与氛围正式收录 0 家，诚实显示 0。
 * - 同一网站在多个分类出现时为同一条记录（同一 slug ID）：简介、
 *   域名、收藏状态全部按 ID 解析，跨分类一致。
 * - 自定义网站不进静态目录；选了分类的出现在所选分类下，未选
 *   分类的只出现在“全部”视图与“我的收藏”中。
 * - 下一阶段迭代阶段 4：展示一律以 [resolveDirectory] 合并后的
 *   目录为准（被用户删除的内置站不出现、改名/改 URL/改分类后的
 *   值生效）；分类入口的顺序与隐藏取 `directory_categories`
 *   表的生效视图；自定义分类只收自定义站（官方站的覆盖分类串
 *   不含 `cat:` token，见 [buildCustomCategoryItems]）。
 */

/** 展示用的一条网站记录（官方站与自定义站统一后的形态）。 */
data class DirectorySiteItem(
    /** 官方站为 slug，自定义站为 `custom:<uuid>` 式 ID。 */
    val siteId: String,
    val name: String,
    /** Artlog 自写的一句话简介；自定义站无简介时为空字符串。 */
    val description: String,
    /** 网站类型；自定义站为“自定义网站”。 */
    val type: String,
    /** 打开用的首页 URL（官方站取静态目录，自定义站取行内 URL）。 */
    val homepageUrl: String,
    val isCustom: Boolean,
    val isFavorite: Boolean,
    /** 用户手写备注（只读展示用；编辑走 Repository.updateNote）。 */
    val note: String,
    val categories: Set<InspirationCategory>,
    /**
     * 自定义分类归属（`cat:<uuid>` token 集合；官方站恒为空——
     * [resolveDirectory] 只解码枚举分类，官方站的覆盖分类串里的
     * `cat:` token 会被丢弃，因此官方站不能归属自定义分类，
     * 见 [buildCustomCategoryItems] 的注释）。
     */
    val customCategories: Set<String> = emptySet(),
)

/** 五个正式分类（不含 ALL / OTHER）的正式收录数，顺序与展示一致。 */
fun directoryCategoryCounts(): Map<InspirationCategory, Int> =
    directoryCategoryCounts(VisualDirectory.sites)

/** 五个正式分类的正式收录数，按给定的已合并目录 [sites] 计。 */
fun directoryCategoryCounts(sites: List<VisualWebsite>): Map<InspirationCategory, Int> =
    InspirationCategory.visibleCategories
        .filter { it != InspirationCategory.ALL }
        .associateWith { category -> sites.count { category in it.categories } }

/**
 * 分类行的生效视图（纯函数）：数据库行按 sortOrder 升序；行表还
 * 是空的（首次读取、种子尚未写入）时回退到 5 个内置种子，避免
 * 首页分类入口短暂空白。种子本身不写库，写库只走
 * [DirectoryCategoryRepository.ensureSeeded]。
 */
fun effectiveCategories(categories: List<DirectoryCategory>): List<DirectoryCategory> =
    if (categories.isEmpty()) {
        DirectoryCategoryRepository.BUILTIN_SEEDS
    } else {
        categories.sortedBy { it.sortOrder }
    }

/** 生效视图中未隐藏的分类（首页入口与可选分类列表用）。 */
fun visibleEffectiveCategories(
    categories: List<DirectoryCategory>,
): List<DirectoryCategory> =
    effectiveCategories(categories).filter { !it.isHidden }

/** 内置分类枚举对应的分类行 ID（即枚举名）。 */
fun categoryIdForEnum(category: InspirationCategory): String = category.name

/** 按分类行 ID 取显示名：内置行名取自枚举 label，自定义行取行内名。 */
fun categoryDisplayName(
    categoryId: String,
    categories: List<DirectoryCategory>,
): String =
    effectiveCategories(categories).find { it.id == categoryId }?.name
        ?: runCatching { InspirationCategory.valueOf(categoryId) }.getOrNull()?.label
        ?: categoryId

/**
 * 读时合并（下一阶段迭代，数据库 v6）：静态配置为底、覆盖行为准。
 *
 * - [WebsiteOverride.isDeleted] 为真：该站不展示。
 * - 非空的 nameOverride / descriptionOverride / urlOverride /
 *   categoriesOverride 替换对应字段；名称与简介取去空白后非空的
 *   值，URL 覆盖须过 [isValidHttpUrl]（不通过时保留静态配置 URL），
 *   分类覆盖解码后非空时采用。
 * - 覆盖行指向不存在的 slug 时忽略，不崩溃。
 * - 不动 [VisualDirectory.siteById] / [VisualDirectory.sitesFor]；
 *   本函数是展示层合并的唯一入口（ViewModel 只负责把两路 Flow
 *   合并后交给它）。
 */
fun resolveDirectory(overrides: List<WebsiteOverride>): List<VisualWebsite> {
    val byId = overrides.associateBy { it.siteId }
    return VisualDirectory.sites.mapNotNull { site ->
        val ov = byId[site.id] ?: return@mapNotNull site
        if (ov.isDeleted) return@mapNotNull null
        site.copy(
            name = ov.nameOverride?.takeIf { it.isNotBlank() } ?: site.name,
            description = ov.descriptionOverride?.takeIf { it.isNotBlank() }
                ?: site.description,
            homepageUrl = ov.urlOverride
                ?.takeIf { isValidHttpUrl(it) } ?: site.homepageUrl,
            categories = ov.categoriesOverride
                ?.let { WebsiteFavoriteRepository.decodeCategories(it) }
                ?.takeIf { it.isNotEmpty() } ?: site.categories,
        )
    }
}

/** “全部”视图的网站总数：官方站 5 家 + 自定义站行数。 */
fun directoryAllCount(favorites: List<WebsiteFavorite>): Int =
    directoryAllCount(favorites, VisualDirectory.sites)

/** “全部”视图的网站总数：已合并目录站数 + 自定义站行数。 */
fun directoryAllCount(
    favorites: List<WebsiteFavorite>,
    resolvedSites: List<VisualWebsite>,
): Int =
    resolvedSites.size + favorites.count { it.isCustom }

/** 只取域名（去 scheme 与路径）供卡片展示；解析失败时原样返回。 */
fun domainOf(url: String): String {
    val trimmed = url.trim()
    val host = runCatching { java.net.URL(trimmed).host }.getOrNull()
        ?.takeIf { it.isNotEmpty() } ?: return trimmed
    return host
}

/** 自定义网站输入校验的失败原因；null 表示通过。 */
enum class CustomInputError {
    /** 名称为空（去空白后）。 */
    EMPTY_NAME,
    /** URL 未通过安全门 [isValidHttpUrl]（仅 http/https）。 */
    INVALID_URL,
}

/**
 * 校验自定义网站输入：名称必填、URL 必填且须通过 [isValidHttpUrl]。
 * 只做格式校验，不发任何网络请求、不访问所填网址。
 */
fun validateCustomInput(name: String, url: String): CustomInputError? {
    if (name.trim().isEmpty()) return CustomInputError.EMPTY_NAME
    if (!isValidHttpUrl(url.trim())) return CustomInputError.INVALID_URL
    return null
}

/**
 * 把一行网站收藏解析为展示记录。解析规则与
 * [WebsiteFavoriteRepository.displayName] / [WebsiteFavoriteRepository.displayUrl] /
 * [WebsiteFavoriteRepository.displayCategories] 一致：官方站以静态
 * 配置为准（目录查不到时退回 slug / 行内 URL / 空集），自定义站以
 * 行内值为准。
 */
fun resolveFavoriteItem(favorite: WebsiteFavorite): DirectorySiteItem =
    resolveFavoriteItem(favorite, VisualDirectory.sites)

/**
 * 把一行网站收藏解析为展示记录（按已合并目录 [resolvedSites]）。
 * 官方站的名称 / URL / 分类取覆盖后的值（被删的站本不该还有收藏
 * 行——删除内置站时收藏行已同步删除；万一残留，退回静态配置，
 * 再退回 slug / 行内 URL / 空集，不崩溃）。
 * 自定义站以行内值为准，并解出 `cat:` token 归属。
 */
fun resolveFavoriteItem(
    favorite: WebsiteFavorite,
    resolvedSites: List<VisualWebsite>,
): DirectorySiteItem {
    if (favorite.isCustom) {
        val (enums, customs) =
            WebsiteFavoriteRepository.decodeCategoryTokens(favorite.categories)
        return DirectorySiteItem(
            siteId = favorite.siteId,
            name = favorite.name?.takeIf { it.isNotBlank() } ?: favorite.url,
            description = "",
            type = "自定义网站",
            homepageUrl = favorite.url,
            isCustom = true,
            isFavorite = true,
            note = favorite.note,
            categories = enums,
            customCategories = customs,
        )
    }
    val site = resolvedSites.find { it.id == favorite.siteId }
        ?: VisualDirectory.siteById(favorite.siteId)
    return DirectorySiteItem(
        siteId = favorite.siteId,
        name = site?.name ?: favorite.siteId,
        description = site?.description ?: "",
        type = site?.type ?: "",
        homepageUrl = site?.homepageUrl ?: favorite.url,
        isCustom = false,
        isFavorite = true,
        note = favorite.note,
        categories = site?.categories ?: emptySet(),
    )
}

/**
 * 某一分类（含 ALL）下的展示列表：官方站（按目录顺序）+ 该分类下
 * 的自定义站（按收藏时间倒序）。官方站的收藏状态按 ID 从
 * [favorites] 推导，同一 slug 在各分类下状态一致。
 *
 * 自定义站归属：选了该分类的出现；未选任何分类的只在 ALL 下出现。
 */
fun buildCategoryItems(
    category: InspirationCategory,
    favorites: List<WebsiteFavorite>,
): List<DirectorySiteItem> =
    buildCategoryItems(category, favorites, VisualDirectory.sites)

/**
 * 某一分类（含 ALL）下的展示列表：官方站（按已合并目录顺序，删
 * 除的站已不在 [resolvedSites] 中）+ 该分类下的自定义站（按收藏
 * 时间倒序）。官方站的收藏状态按 ID 从 [favorites] 推导，同一
 * slug 在各分类下状态一致。
 *
 * 自定义站归属：选了该分类的出现；未选任何分类的只在 ALL 下出现。
 */
fun buildCategoryItems(
    category: InspirationCategory,
    favorites: List<WebsiteFavorite>,
    resolvedSites: List<VisualWebsite>,
): List<DirectorySiteItem> {
    val favoriteIds = favorites.map { it.siteId }.toSet()
    val official = if (category == InspirationCategory.ALL) {
        resolvedSites
    } else {
        resolvedSites.filter { category in it.categories }
    }.map { site ->
        DirectorySiteItem(
            siteId = site.id,
            name = site.name,
            description = site.description,
            type = site.type,
            homepageUrl = site.homepageUrl,
            isCustom = false,
            isFavorite = site.id in favoriteIds,
            note = favorites.find { it.siteId == site.id }?.note ?: "",
            categories = site.categories,
        )
    }
    val custom = favorites
        .filter { it.isCustom }
        .filter { favorite ->
            val cats = WebsiteFavoriteRepository.decodeCategories(favorite.categories)
            category == InspirationCategory.ALL || category in cats
        }
        .sortedByDescending { it.savedAt }
        .map { resolveFavoriteItem(it, resolvedSites) }
    return official + custom
}

/**
 * 某一自定义分类下的展示列表：只有自定义站能归属自定义分类。
 *
 * 限制（数据层现状，非 UI 决定）：官方站的分类覆盖串经
 * [resolveDirectory] 只解码枚举名，`cat:` token 会被丢弃，
 * 因此官方站无法归属自定义分类；本函数只列自定义站。分类行已
 * 被删除时（[existingCategoryIds] 中无该 ID）返回空列表——站不
 * 删，只是该分类下不再出现，即“删除分类只解除归属”。
 */
fun buildCustomCategoryItems(
    categoryId: String,
    favorites: List<WebsiteFavorite>,
    existingCategoryIds: Set<String>,
): List<DirectorySiteItem> {
    if (categoryId !in existingCategoryIds) return emptyList()
    return favorites
        .filter { it.isCustom }
        .filter { favorite ->
            val (_, customs) =
                WebsiteFavoriteRepository.decodeCategoryTokens(favorite.categories)
            categoryId in customs
        }
        .sortedByDescending { it.savedAt }
        .map { resolveFavoriteItem(it) }
}

/** 归属某自定义分类的自定义站数量（删除分类时的确认提示用）。 */
fun customCategorySiteCount(
    favorites: List<WebsiteFavorite>,
    categoryId: String,
): Int =
    favorites.count { favorite ->
        favorite.isCustom &&
            categoryId in WebsiteFavoriteRepository
                .decodeCategoryTokens(favorite.categories).second
    }

/**
 * 分类归属重指定（纯函数）：把分类串里的 [fromId] 换成 [toId]。
 *
 * - [toId] 为 null：只解除归属（删除分类时用，站不删）。
 * - [toId] 为枚举名（如 "ANIME"）：移到内置分类。
 * - [toId] 为 `cat:<uuid>`：移到另一自定义分类。
 * - [fromId] 不存在时原样返回；结果为空时返回 null（与
 *   `categories` 列“空存 null”的约定一致）。
 */
fun reassignCategoryTokens(
    raw: String?,
    fromId: String,
    toId: String?,
): String? {
    val (enums, customs) =
        WebsiteFavoriteRepository.decodeCategoryTokens(raw)
    if (fromId !in enums.map { it.name } && fromId !in customs) return raw
    val newEnums = enums.map { it.name }.toMutableSet()
    val newCustoms = customs.toMutableSet()
    newEnums.remove(fromId)
    newCustoms.remove(fromId)
    when {
        toId == null -> Unit
        toId.startsWith("cat:") -> newCustoms += toId
        else -> runCatching { InspirationCategory.valueOf(toId) }
            .getOrNull()
            ?.takeIf { it != InspirationCategory.ALL && it != InspirationCategory.OTHER }
            ?.let { newEnums += it.name }
    }
    return WebsiteFavoriteRepository.encodeCategoryTokens(
        newEnums.mapNotNull { runCatching { InspirationCategory.valueOf(it) }.getOrNull() }.toSet(),
        newCustoms,
    )
}

/** “我的收藏”列表：全部网站收藏行（官方 + 自定义），按收藏时间倒序。 */
fun buildFavoriteItems(favorites: List<WebsiteFavorite>): List<DirectorySiteItem> =
    buildFavoriteItems(favorites, VisualDirectory.sites)

/** “我的收藏”列表（按已合并目录解析官方站的覆盖值）。 */
fun buildFavoriteItems(
    favorites: List<WebsiteFavorite>,
    resolvedSites: List<VisualWebsite>,
): List<DirectorySiteItem> =
    favorites.sortedByDescending { it.savedAt }.map { resolveFavoriteItem(it, resolvedSites) }
