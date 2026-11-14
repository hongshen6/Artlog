package com.artlog.app.ui.directory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.artlog.app.data.DirectoryCategory
import com.artlog.app.data.DirectoryCategoryRepository
import com.artlog.app.data.InspirationCategory
import com.artlog.app.data.VisualDirectory
import com.artlog.app.data.VisualWebsite
import com.artlog.app.data.WebsiteFavorite
import com.artlog.app.data.WebsiteFavoriteRepository
import com.artlog.app.data.WebsiteOverride
import com.artlog.app.data.WebsiteOverrideRepository
import com.artlog.app.data.isValidHttpUrl
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 视觉灵感导航的 ViewModel（Artlog 2.0 阶段 2 + 下一阶段迭代阶段 4）。
 *
 * 三路数据，各走各的 Repository，展示层只做合并：
 * - 网站收藏（`website_favorites`，经 [WebsiteFavoriteRepository]）：
 *   与旧作品收藏（`inspiration_favorites`）严格区分，绝不混表、绝不
 *   把旧作品收藏转成网站收藏；收藏语义不变。
 * - 内置站覆盖（`website_overrides`，经 [WebsiteOverrideRepository]）：
 *   改名 / 改 URL / 改分类 = 写覆盖行非空字段；删除内置站 =
 *   `isDeleted = true` 的覆盖行（不是删静态配置）；删除覆盖行即
 *   恢复默认。覆盖行以 slug 为键存在于数据库中，与 APK 内的静态
 *   配置分离，升级 / 重启后用户操作不丢失、不复活。
 * - 目录分类（`directory_categories`，经 [DirectoryCategoryRepository]）：
 *   内置分类不可删除、不可改名，只可隐藏与排序；自定义分类可增删
 *   改名排序。删除自定义分类时不删站，只解除归属（引用它的自定义
 *   站分类串在读时丢弃该 token；需要移动归属时走 [reassignCategory]）。
 *
 * 收藏语义（不变）：
 * - 官方站：星标切换 = [WebsiteFavoriteRepository.favoriteOfficial] /
 *   [WebsiteFavoriteRepository.removeFavorite]；同一 slug 在各分类下
 *   是同一行，状态全局一致。
 * - 自定义站：“添加即收藏”，行本身就是收藏行；取消收藏
 *   （[toggleFavorite] / [deleteCustom]）会删除该行，即删除自定义
 *   网站本身——删除自定义网站时同步删除其收藏行，两者是同一行。
 * - 备注只存用户手写文本（[saveNote]），不自动生成；不因收藏而
 *   访问对方网站，不下载 favicon / 预览图。
 * - 保存自定义网站时只做格式校验（[validateCustomInput]），不发
 *   任何网络请求、不访问所填网址；只有用户主动点“打开网站”时才
 *   由系统浏览器打开（UI 层处理）。
 *
 * 限制（数据层现状，见 DirectoryUiLogic.kt）：
 * - 简介不可改：[WebsiteOverride] 没有简介覆盖字段，v6 迁移已定
 *   型，本阶段不加列；编辑内置站只能改名称 / URL / 所属分类。
 * - 官方站不能归属自定义分类：`resolveDirectory` 只解码枚举分类。
 */
class VisualInspirationViewModel(
    private val repository: WebsiteFavoriteRepository,
    private val overrideRepository: WebsiteOverrideRepository,
    private val categoryRepository: DirectoryCategoryRepository,
) : ViewModel() {

    init {
        // 全新安装时 Room 只建表不跑迁移，种子须由 Repository 补；
        // 表非空时 ensureSeeded 什么也不做，用户已有的隐藏/排序/
        // 删除状态不会被种子覆盖。
        viewModelScope.launch { categoryRepository.ensureSeeded() }
    }

    /** 全部网站收藏（按收藏时间倒序），UI 由此推导星标态与收藏列表。 */
    val favorites: StateFlow<List<WebsiteFavorite>> =
        repository.observeFavorites()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 全部网站覆盖行（按 siteId 排序）。 */
    val overrides: StateFlow<List<WebsiteOverride>> =
        overrideRepository.observeOverrides()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 全部分类行（按 sortOrder 升序）；空表时 UI 用种子回退展示。 */
    val categories: StateFlow<List<DirectoryCategory>> =
        categoryRepository.observeCategories()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** 已合并的官方目录：静态配置为底、覆盖行为准（见 resolveDirectory）。 */
    val resolvedSites: StateFlow<List<VisualWebsite>> =
        overrides.map { resolveDirectory(it) }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                VisualDirectory.sites,
            )

    /** 星标切换：已收藏则取消，未收藏则收藏官方站。 */
    fun toggleFavorite(siteId: String) {
        viewModelScope.launch {
            if (repository.isFavorite(siteId)) {
                repository.removeFavorite(siteId)
            } else {
                repository.favoriteOfficial(siteId)
            }
        }
    }

    /** 保存一行的个人备注（官方站与自定义站通用，只改备注）。 */
    fun saveNote(siteId: String, note: String) {
        viewModelScope.launch {
            repository.updateNote(siteId, note)
        }
    }

    /**
     * 新增自定义网站并收藏。调用方须先过 [validateCustomInput]；
     * 校验通过后写库，成功返回 true（失败只可能发生在校验已拦过
     * 的非法输入上，正常情况下不可达）。
     */
    suspend fun addCustom(
        name: String,
        url: String,
        categories: Set<InspirationCategory>,
    ): Boolean {
        return addCustom(name, url, categories, emptySet())
    }

    /** 新增自定义网站并收藏（含自定义分类归属，`cat:<uuid>` token）。 */
    suspend fun addCustom(
        name: String,
        url: String,
        categories: Set<InspirationCategory>,
        customCategoryIds: Set<String>,
    ): Boolean {
        return repository.addCustom(name, url, categories, customCategoryIds) != null
    }

    /**
     * 修改一个自定义网站（名称 / URL / 分类）。调用方须先过
     * [validateCustomInput]；成功返回 true。行不存在或非自定义行
     * 时返回 false（UI 只对自定义行提供编辑入口，正常情况下不可达）。
     */
    suspend fun updateCustom(
        siteId: String,
        name: String,
        url: String,
        categories: Set<InspirationCategory>,
    ): Boolean {
        return updateCustom(siteId, name, url, categories, emptySet())
    }

    /** 修改一个自定义网站（名称 / URL / 分类，含自定义分类归属）。 */
    suspend fun updateCustom(
        siteId: String,
        name: String,
        url: String,
        categories: Set<InspirationCategory>,
        customCategoryIds: Set<String>,
    ): Boolean {
        return repository.updateCustom(siteId, name, url, categories, customCategoryIds)
    }

    /** 删除自定义网站（即删除其收藏行；官方站不走此入口）。 */
    fun deleteCustom(siteId: String) {
        viewModelScope.launch {
            repository.removeFavorite(siteId)
        }
    }

    // ---- 内置站覆盖：编辑 / 删除 / 恢复默认 ----

    /**
     * 编辑一个内置站（名称 / 简介 / URL / 所属枚举分类）。
     *
     * 与静态配置相同的值不写入覆盖行（存 null = 用默认值），这样
     * “恢复默认”就是删行；空分类集合同样视为用默认值（覆盖串
     * 为空时 resolveDirectory 回退到静态分类）。简介为空白时视
     * 为用默认值（存 null，与 resolveDirectory 的回落语义一致）。
     * URL 须过安全门，否则返回 false（不写库）。行不存在、或
     * siteId 不在静态目录中时返回 false。
     */
    suspend fun editOfficial(
        siteId: String,
        name: String,
        description: String,
        url: String,
        categories: Set<InspirationCategory>,
    ): Boolean {
        val site = VisualDirectory.siteById(siteId) ?: return false
        val cleanName = name.trim()
        val cleanDescription = description.trim()
        val cleanUrl = url.trim()
        if (cleanName.isEmpty() || !isValidHttpUrl(cleanUrl)) return false
        val existing = overrideRepository.getOverride(siteId)
        val encoded = WebsiteFavoriteRepository.encodeCategories(categories)
        val staticEncoded = WebsiteFavoriteRepository.encodeCategories(site.categories)
        return overrideRepository.upsertOverride(
            WebsiteOverride(
                siteId = siteId,
                isDeleted = existing?.isDeleted == true,
                nameOverride = cleanName.takeIf { it != site.name },
                descriptionOverride = cleanDescription.takeIf { it != site.description },
                urlOverride = cleanUrl.takeIf { it != site.homepageUrl },
                categoriesOverride = encoded.takeIf { it != staticEncoded },
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    /**
     * 删除一个内置站：写 `isDeleted = true` 的覆盖行（静态配置
     * 不动），并同步删除其收藏行（取消收藏语义）。反向不成立：
     * 取消收藏不写覆盖行。
     */
    fun deleteOfficial(siteId: String) {
        viewModelScope.launch {
            overrideRepository.setDeleted(siteId, true)
            repository.removeFavorite(siteId)
        }
    }

    /** 恢复一个内置站的默认展示：删除覆盖行（改名/改 URL 一并还原）。 */
    fun restoreOfficial(siteId: String) {
        viewModelScope.launch {
            overrideRepository.deleteOverride(siteId)
        }
    }

    // ---- 分类管理 ----

    /** 新增自定义分类；名称为空时返回 null（不写库）。 */
    suspend fun addCategory(name: String): DirectoryCategory? =
        categoryRepository.addCustomCategory(name)

    /** 改名（仅自定义分类；内置分类返回 false）。 */
    suspend fun renameCategory(id: String, name: String): Boolean =
        categoryRepository.renameCategory(id, name)

    /** 设置一个分类的隐藏状态（内置与自定义均可）。 */
    suspend fun setCategoryHidden(id: String, hidden: Boolean): Boolean =
        categoryRepository.setHidden(id, hidden)

    /** 删除一个自定义分类（只解除归属，不删站；内置分类返回 false）。 */
    suspend fun deleteCategory(id: String): Boolean =
        categoryRepository.deleteCustomCategory(id)

    /**
     * 把归属从 [fromId] 的自定义站重指定到 [toId]。
     *
     * - [toId] 为 null：只解除归属（站保留，分类串里去掉该 token）。
     * - [toId] 为枚举名：移到内置分类；为 `cat:<uuid>`：移到另一
     *   自定义分类；非法值按解除归属处理。
     * 只改自定义站的行；官方站不受影响（官方站本就不能归属自定
     * 义分类）。返回实际改写的站数。
     */
    suspend fun reassignCategory(fromId: String, toId: String?): Int {
        var moved = 0
        repository.observeFavorites().first()
            .filter { it.isCustom }
            .forEach { favorite ->
                val (enums, customs) =
                    WebsiteFavoriteRepository.decodeCategoryTokens(favorite.categories)
                if (fromId !in enums.map { it.name } && fromId !in customs) return@forEach
                val rewritten = reassignCategoryTokens(favorite.categories, fromId, toId)
                val (newEnums, newCustoms) =
                    WebsiteFavoriteRepository.decodeCategoryTokens(rewritten)
                if (repository.updateCustom(
                        favorite.siteId,
                        favorite.name ?: favorite.url,
                        favorite.url,
                        newEnums,
                        newCustoms,
                    )
                ) {
                    moved++
                }
            }
        return moved
    }

    /** 上移 / 下移一个分类（[delta] 为 -1 上移、+1 下移）；到顶/底时不动。 */
    fun moveCategory(id: String, delta: Int) {
        viewModelScope.launch {
            val current = effectiveCategories(categories.value)
            val index = current.indexOfFirst { it.id == id }
            if (index < 0) return@launch
            val target = (index + delta).coerceIn(0, current.size - 1)
            if (target == index) return@launch
            val reordered = current.toMutableList().apply {
                val row = removeAt(index)
                add(target, row)
            }
            categoryRepository.reorderCategories(reordered.map { it.id })
        }
    }
}
