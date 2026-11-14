package com.artlog.app.ui.directory

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.artlog.app.data.DirectoryCategory
import com.artlog.app.data.InspirationCategory
import com.artlog.app.data.WebsiteFavorite
import com.artlog.app.data.WebsiteFavoriteRepository
import com.artlog.app.data.isValidHttpUrl
import com.artlog.app.ui.ArtlogViewModelFactory
import kotlinx.coroutines.launch

/**
 * 视觉灵感导航页（Artlog 2.0 阶段 2 + 阶段 3 的旧收藏入口 +
 * 下一阶段迭代阶段 4 的网站与分类管理）。
 *
 * 产品形态：本地网站目录导航，不是作品推荐 Feed。首页列分类入口，
 * 分类详情页列网站卡片；点击卡片由系统浏览器打开原站首页。
 * 不显示第三方作品图片；无自动刷新、无限滚动、推荐分页、换一批；
 * 目录浏览全程无网络请求（静态配置本地读取）。
 *
 * 阶段 4（网站与分类管理）：
 * - 内置站：每站可编辑（名称 / URL / 所属枚举分类）、可删除
 *   （写 `website_overrides` 的 `isDeleted` 行，静态配置不动）、
 *   可恢复默认（删覆盖行）；删除内置站时同步删除其收藏行。
 *   简介不可改——`WebsiteOverride` 没有简介覆盖字段，v6 迁移已
 *   定型，本阶段不加列（见 ViewModel 注释）。
 * - 自定义站：已有增删改，扩展为支持分类重指定（含 `cat:<uuid>`
 *   自定义分类 token）；删除即删行（含其收藏行）。
 * - 分类：内置分类不可删除、不可改名（名取自枚举 label），只可
 *   隐藏与排序；自定义分类可增删改名排序。删除自定义分类时不删
 *   站，只解除归属；删除仍含网站的分类时，须先处理这些网站的
 *   归属（移到其他分类或仅解除归属），并有确认提示；不得静默
 *   删除分类及其所属网站。
 * - 展示层一律用 `resolveDirectory(overrides)` 合并后的目录；
 *   分类入口的顺序与隐藏取 `directory_categories` 表的生效视图。
 * - 危险操作（删除网站、删除分类）均有确认提示；收藏语义不动。
 *
 * 内部导航用一层返回栈（首页 → 分类 / 我的收藏 / 添加自定义 /
 * 编辑 / 管理分类 / 旧灵感收藏 → 旧收藏详情）；栈非空时本页先消费
 * 系统返回键，栈空时由 ArtlogApp 的 handleBack 关闭整个 overlay。
 * 旧灵感收藏是独立的只读历史入口（见 LegacyFavoritesScreen），
 * 与“我的收藏”（网站收藏）严格区分。
 */

/** 页内路由（返回栈元素）。 */
private sealed interface DirectoryRoute {
    data object Home : DirectoryRoute
    data class Category(val category: InspirationCategory) : DirectoryRoute
    /** 自定义分类详情：只列归属该分类的自定义站。 */
    data class CustomCategory(val id: String) : DirectoryRoute
    data object Favorites : DirectoryRoute
    data object AddCustom : DirectoryRoute
    data class EditCustom(val siteId: String) : DirectoryRoute
    data class EditOfficial(val siteId: String) : DirectoryRoute
    data object ManageCategories : DirectoryRoute
    data object LegacyList : DirectoryRoute
    data class LegacyDetail(val favoriteId: String) : DirectoryRoute
}

/**
 * 打开一个网站首页：先过安全门 [isValidHttpUrl]（仅 http/https），
 * 再交 `Intent.ACTION_VIEW` 给系统浏览器。失败返回 false，由调用
 * 方弹明确提示；不无限等待、不静默无反应、不自造加载态。
 */
private fun openWebsite(context: Context, url: String): Boolean {
    val trimmed = url.trim()
    if (!isValidHttpUrl(trimmed)) return false
    return try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(trimmed)))
        true
    } catch (_: Exception) {
        false
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VisualInspirationScreen(
    onBackClick: () -> Unit,
    viewModelFactory: ArtlogViewModelFactory,
) {
    val viewModel: VisualInspirationViewModel = viewModel(factory = viewModelFactory)
    val legacyViewModel: LegacyFavoritesViewModel = viewModel(factory = viewModelFactory)
    val favorites by viewModel.favorites.collectAsState()
    val legacyFavorites by legacyViewModel.favorites.collectAsState()
    val overrides by viewModel.overrides.collectAsState()
    val categories by viewModel.categories.collectAsState()
    val resolvedSites by viewModel.resolvedSites.collectAsState()
    val context = LocalContext.current

    var stack by remember { mutableStateOf(listOf<DirectoryRoute>(DirectoryRoute.Home)) }
    val route = stack.last()
    fun navigate(next: DirectoryRoute) {
        stack = stack + next
    }
    fun pop() {
        if (stack.size > 1) stack = stack.dropLast(1) else onBackClick()
    }
    BackHandler(enabled = stack.size > 1) { pop() }

    var showOpenFailed by remember { mutableStateOf(false) }
    var noteTarget by remember { mutableStateOf<DirectorySiteItem?>(null) }
    /** 待确认删除的网站（内置站与自定义站共用确认框）。 */
    var deleteSiteTarget by remember { mutableStateOf<DirectorySiteItem?>(null) }
    /** 待确认恢复默认的内置站（恢复会清除该站的全部覆盖修改）。 */
    var restoreSiteTarget by remember { mutableStateOf<DirectorySiteItem?>(null) }
    fun openSite(url: String) {
        if (!openWebsite(context, url)) showOpenFailed = true
    }
    val overriddenIds = overrides.map { it.siteId }.toSet()
    val customCategories = categories.filter { !it.isBuiltin && !it.isHidden }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(
                    text = when (route) {
                        DirectoryRoute.Home -> "视觉灵感"
                        is DirectoryRoute.Category -> route.category.label
                        is DirectoryRoute.CustomCategory ->
                            categoryDisplayName(route.id, categories)
                        DirectoryRoute.Favorites -> "我的收藏"
                        DirectoryRoute.AddCustom -> "添加自定义网站"
                        is DirectoryRoute.EditCustom -> "编辑自定义网站"
                        is DirectoryRoute.EditOfficial -> "编辑内置网站"
                        DirectoryRoute.ManageCategories -> "管理分类"
                        DirectoryRoute.LegacyList -> "旧灵感收藏"
                        is DirectoryRoute.LegacyDetail -> "收藏详情"
                    }
                )
            },
            navigationIcon = {
                IconButton(onClick = { pop() }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回"
                    )
                }
            },
            // 2026-11-14 v26 视觉灵感顶栏修复：与设置页 v25 同款问题。
            // (1) 容器色改为透明，背景图透出（原来是不透明的
            // colorScheme.surface #191A1D，挡住了背景图）。
            // (2) 只保留水平方向 insets：ArtlogApp 的 Scaffold 内容区
            // 已经给了状态栏高度的 padding，TopAppBar 默认还会再加
            // 一份状态栏 inset（M3 1.3.0 实测），两份叠加使标题行
            // 下沉约一个状态栏高度、黑带增高；去掉重复的那份即可。
            // 单个 TopAppBar 服务全部 10 个路由（主页、分类、自定义
            // 分类、收藏、添加/编辑自定义网站、编辑内置网站、管理
            // 分类、旧灵感收藏列表与详情），一处修复即覆盖各子页。
            windowInsets = TopAppBarDefaults.windowInsets.only(
                WindowInsetsSides.Horizontal
            ),
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Transparent
            )
        )

        when (route) {
            DirectoryRoute.Home -> DirectoryHome(
                favorites = favorites,
                legacyCount = legacyFavorites.size,
                resolvedSites = resolvedSites,
                categories = categories,
                onCategory = { navigate(DirectoryRoute.Category(it)) },
                onCustomCategory = { navigate(DirectoryRoute.CustomCategory(it)) },
                onFavorites = { navigate(DirectoryRoute.Favorites) },
                onAddCustom = { navigate(DirectoryRoute.AddCustom) },
                onManageCategories = { navigate(DirectoryRoute.ManageCategories) },
                onLegacy = { navigate(DirectoryRoute.LegacyList) },
            )
            is DirectoryRoute.Category -> {
                val items = buildCategoryItems(route.category, favorites, resolvedSites)
                CategoryContent(
                    category = route.category,
                    items = items,
                    allCount = directoryAllCount(favorites, resolvedSites),
                    overriddenIds = overriddenIds,
                    onOpen = { openSite(it.homepageUrl) },
                    onToggleFavorite = { viewModel.toggleFavorite(it.siteId) },
                    onNote = { noteTarget = it },
                    onEditCustom = { navigate(DirectoryRoute.EditCustom(it.siteId)) },
                    onDeleteSite = { deleteSiteTarget = it },
                    onEditOfficial = { navigate(DirectoryRoute.EditOfficial(it.siteId)) },
                    onRestoreOfficial = { restoreSiteTarget = it },
                )
            }
            is DirectoryRoute.CustomCategory -> {
                val items = buildCustomCategoryItems(
                    route.id,
                    favorites,
                    categories.map { it.id }.toSet(),
                )
                CategoryContent(
                    category = null,
                    items = items,
                    allCount = items.size,
                    overriddenIds = overriddenIds,
                    onOpen = { openSite(it.homepageUrl) },
                    onToggleFavorite = { viewModel.toggleFavorite(it.siteId) },
                    onNote = { noteTarget = it },
                    onEditCustom = { navigate(DirectoryRoute.EditCustom(it.siteId)) },
                    onDeleteSite = { deleteSiteTarget = it },
                    onEditOfficial = { navigate(DirectoryRoute.EditOfficial(it.siteId)) },
                    onRestoreOfficial = { restoreSiteTarget = it },
                )
            }
            DirectoryRoute.Favorites -> FavoritesContent(
                items = buildFavoriteItems(favorites, resolvedSites),
                overriddenIds = overriddenIds,
                onOpen = { openSite(it.homepageUrl) },
                onToggleFavorite = { viewModel.toggleFavorite(it.siteId) },
                onNote = { noteTarget = it },
                onEditCustom = { navigate(DirectoryRoute.EditCustom(it.siteId)) },
                onDeleteSite = { deleteSiteTarget = it },
                onEditOfficial = { navigate(DirectoryRoute.EditOfficial(it.siteId)) },
                onRestoreOfficial = { restoreSiteTarget = it },
            )
            DirectoryRoute.AddCustom -> CustomWebsiteForm(
                initialName = "",
                initialUrl = "",
                initialCategories = emptySet(),
                initialCustomCategories = emptySet(),
                availableCustomCategories = customCategories,
                onSave = { name, url, categories, customCategoryIds ->
                    val error = validateCustomInput(name, url)
                    if (error != null) return@CustomWebsiteForm error
                    val ok = viewModel.addCustom(name, url, categories, customCategoryIds)
                    if (ok) pop()
                    if (ok) null else CustomInputError.INVALID_URL
                },
            )
            is DirectoryRoute.EditCustom -> {
                val row = favorites.find { it.siteId == route.siteId }
                if (row == null) {
                    // 行已不在（例如刚被删除）：直接退回，不展示空表单。
                    LaunchedEffect(Unit) { pop() }
                } else {
                    val (enums, customs) =
                        WebsiteFavoriteRepository.decodeCategoryTokens(row.categories)
                    CustomWebsiteForm(
                        initialName = row.name ?: "",
                        initialUrl = row.url,
                        initialCategories = enums,
                        initialCustomCategories = customs,
                        availableCustomCategories = customCategories,
                        onSave = { name, url, categories, customCategoryIds ->
                            val error = validateCustomInput(name, url)
                            if (error != null) return@CustomWebsiteForm error
                            val ok = viewModel.updateCustom(
                                row.siteId, name, url, categories, customCategoryIds
                            )
                            if (ok) pop()
                            if (ok) null else CustomInputError.INVALID_URL
                        },
                    )
                }
            }
            is DirectoryRoute.EditOfficial -> {
                val item = resolvedSites.find { it.id == route.siteId }
                    ?.let { site ->
                        DirectorySiteItem(
                            siteId = site.id,
                            name = site.name,
                            description = site.description,
                            type = site.type,
                            homepageUrl = site.homepageUrl,
                            isCustom = false,
                            isFavorite = favorites.any { it.siteId == site.id },
                            note = favorites.find { it.siteId == site.id }?.note ?: "",
                            categories = site.categories,
                        )
                    }
                if (item == null) {
                    // 站已不在合并目录中（例如刚被删除）：直接退回。
                    LaunchedEffect(Unit) { pop() }
                } else {
                    OfficialWebsiteForm(
                        item = item,
                        isOverridden = item.siteId in overriddenIds,
                        onSave = { name, description, url, categories ->
                            val error = validateCustomInput(name, url)
                            if (error != null) return@OfficialWebsiteForm error
                            val ok = viewModel.editOfficial(
                                item.siteId, name, description, url, categories
                            )
                            if (ok) pop()
                            if (ok) null else CustomInputError.INVALID_URL
                        },
                        onRestoreDefault = {
                            restoreSiteTarget = item
                        },
                    )
                }
            }
            DirectoryRoute.ManageCategories -> ManageCategoriesScreen(
                favorites = favorites,
                categories = categories,
                viewModel = viewModel,
            )
            DirectoryRoute.LegacyList -> LegacyFavoritesList(
                favorites = legacyFavorites,
                onFavoriteClick = { navigate(DirectoryRoute.LegacyDetail(it.id)) },
            )
            is DirectoryRoute.LegacyDetail -> {
                val favorite = legacyFavorites.find { it.id == route.favoriteId }
                if (favorite == null) {
                    LaunchedEffect(Unit) { pop() }
                } else {
                    LegacyFavoriteDetail(
                        favorite = favorite,
                        onOpenOriginal = { openSite(favorite.sourceUrl) },
                    )
                }
            }
        }
    }

    if (showOpenFailed) {
        AlertDialog(
            onDismissRequest = { showOpenFailed = false },
            confirmButton = {
                TextButton(onClick = { showOpenFailed = false }) { Text(text = "知道了") }
            },
            text = { Text(text = "无法打开，请检查网络后重试") },
        )
    }

    val target = noteTarget
    if (target != null) {
        NoteDialog(
            item = target,
            onDismiss = { noteTarget = null },
            onSave = { note ->
                viewModel.saveNote(target.siteId, note)
                noteTarget = null
            },
        )
    }

    val deleteTarget = deleteSiteTarget
    if (deleteTarget != null) {
        ConfirmDialog(
            title = "删除网站",
            text = if (deleteTarget.isCustom) {
                "确定删除「${deleteTarget.name}」吗？该网站及其收藏将一并删除，无法恢复。"
            } else {
                "确定删除「${deleteTarget.name}」吗？该网站将从目录中隐藏，" +
                    "收藏也会一并取消；以后可在编辑页通过“恢复默认”找回。"
            },
            confirmLabel = "删除",
            onDismiss = { deleteSiteTarget = null },
            onConfirm = {
                if (deleteTarget.isCustom) {
                    viewModel.deleteCustom(deleteTarget.siteId)
                } else {
                    viewModel.deleteOfficial(deleteTarget.siteId)
                }
                deleteSiteTarget = null
            },
        )
    }

    val restoreTarget = restoreSiteTarget
    if (restoreTarget != null) {
        ConfirmDialog(
            title = "恢复默认",
            text = "恢复默认将清除你对「${restoreTarget.name}」的名称、" +
                "网址与分类修改，回到官方目录的默认内容。确定恢复吗？",
            confirmLabel = "恢复默认",
            onDismiss = { restoreSiteTarget = null },
            onConfirm = {
                viewModel.restoreOfficial(restoreTarget.siteId)
                restoreSiteTarget = null
                // 正在编辑该站时，恢复后退回上一页（编辑表单已失效）。
                if (route is DirectoryRoute.EditOfficial) pop()
            },
        )
    }
}

/** 通用确认框：危险操作（删除网站、删除分类、恢复默认）均经此确认。 */
@Composable
private fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(text = confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(text = "取消") }
        },
        title = { Text(text = title) },
        text = { Text(text = text) },
    )
}

/** 首页：标题 + 副标题 + 目录说明 + 我的收藏 / 添加自定义 + 分类 + 旧灵感收藏。 */
@Composable
private fun DirectoryHome(
    favorites: List<WebsiteFavorite>,
    legacyCount: Int,
    resolvedSites: List<com.artlog.app.data.VisualWebsite>,
    categories: List<DirectoryCategory>,
    onCategory: (InspirationCategory) -> Unit,
    onCustomCategory: (String) -> Unit,
    onFavorites: () -> Unit,
    onAddCustom: () -> Unit,
    onManageCategories: () -> Unit,
    onLegacy: () -> Unit,
) {
    // 分类行表为空时（首次读取、种子尚未写入）回退到 5 个内置
    // 种子展示，避免首页分类入口短暂空白；种子写库只走 Repository。
    val visible = visibleEffectiveCategories(categories)
    val counts = directoryCategoryCounts(resolvedSites)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "视觉灵感",
            style = MaterialTheme.typography.titleLarge,
            fontSize = 22.sp
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "发现值得探索的视觉创作网站。",
            style = MaterialTheme.typography.bodyLarge,
            fontSize = 14.sp
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "仅列网站入口，点击后在浏览器打开；Artlog 不抓取站内作品，不展示第三方图片。",
            style = MaterialTheme.typography.bodySmall,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(24.dp))
        DirectoryEntryRow(
            title = "我的收藏",
            subtitle = "已收藏 ${favorites.size} 个网站",
            onClick = onFavorites,
        )
        Spacer(modifier = Modifier.height(12.dp))
        DirectoryEntryRow(
            title = "添加自定义网站",
            subtitle = "收藏你自己的常用网站，只存名称与链接",
            onClick = onAddCustom,
            leadingIcon = Icons.Filled.Add,
        )
        Spacer(modifier = Modifier.height(12.dp))
        DirectoryEntryRow(
            title = "管理分类",
            subtitle = "内置分类可隐藏与排序，自定义分类可增删改名",
            onClick = onManageCategories,
        )

        Spacer(modifier = Modifier.height(28.dp))
        Text(
            text = "分类",
            style = MaterialTheme.typography.titleMedium,
            fontSize = 16.sp
        )
        Spacer(modifier = Modifier.height(12.dp))
        DirectoryEntryRow(
            title = "全部",
            subtitle = "共 ${directoryAllCount(favorites, resolvedSites)} 家（含自定义网站）",
            onClick = { onCategory(InspirationCategory.ALL) },
        )
        Spacer(modifier = Modifier.height(12.dp))
        visible.forEach { row ->
            if (row.isBuiltin) {
                val category = runCatching {
                    InspirationCategory.valueOf(row.id)
                }.getOrNull() ?: return@forEach
                val count = counts[category] ?: 0
                DirectoryEntryRow(
                    title = row.name,
                    subtitle = if (count == 0) "正式收录 0 家 · 尚在确认中" else "正式收录 $count 家",
                    onClick = { onCategory(category) },
                )
            } else {
                val count = customCategorySiteCount(favorites, row.id)
                DirectoryEntryRow(
                    title = row.name,
                    subtitle = "自定义分类 · $count 家",
                    onClick = { onCustomCategory(row.id) },
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        Spacer(modifier = Modifier.height(28.dp))
        Text(
            text = "历史收藏",
            style = MaterialTheme.typography.titleMedium,
            fontSize = 16.sp
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "旧版灵感推荐里收藏的作品，只读保存，不可新增、编辑或删除，与上方的网站收藏严格区分。",
            style = MaterialTheme.typography.bodySmall,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(12.dp))
        DirectoryEntryRow(
            title = "旧灵感收藏",
            subtitle = if (legacyCount == 0) "暂无旧收藏" else "共 $legacyCount 条 · 只读",
            onClick = onLegacy,
            leadingIcon = Icons.Filled.Bookmark,
        )
        Spacer(modifier = Modifier.height(24.dp))
    }
}

/** 首页入口行：与 HomeScreen 灵感入口同一视觉（圆角边框 Row）。 */
@Composable
private fun DirectoryEntryRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    leadingIcon: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline,
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onClick)
            .padding(vertical = 16.dp, horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(12.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontSize = 14.sp
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * 分类详情页：分类名 + 正式收录数 + 网站卡片；风景与氛围为空态。
 * [category] 为 null 时是自定义分类详情（只列自定义站，无“正式
 * 收录”口径，空态文案相应不同）。
 */
@Composable
private fun CategoryContent(
    category: InspirationCategory?,
    items: List<DirectorySiteItem>,
    allCount: Int,
    overriddenIds: Set<String>,
    onOpen: (DirectorySiteItem) -> Unit,
    onToggleFavorite: (DirectorySiteItem) -> Unit,
    onNote: (DirectorySiteItem) -> Unit,
    onEditCustom: (DirectorySiteItem) -> Unit,
    onDeleteSite: (DirectorySiteItem) -> Unit,
    onEditOfficial: (DirectorySiteItem) -> Unit,
    onRestoreOfficial: (DirectorySiteItem) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = when (category) {
                InspirationCategory.ALL -> "共 $allCount 家（含自定义网站）"
                null -> "共 ${items.size} 家"
                else -> "正式收录 ${items.count { !it.isCustom }} 家"
            },
            style = MaterialTheme.typography.bodySmall,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
        )
        if (category == InspirationCategory.LANDSCAPE && items.none { !it.isCustom }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "正式目录尚在确认中",
                    style = MaterialTheme.typography.bodyLarge,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "已核查的候选网站正在等书面确认；正式目录只列已确认可收录的网站，不把待确认的网站列在这里。",
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (items.isEmpty() && category != InspirationCategory.LANDSCAPE) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = if (category == null) "这个分类下还没有网站" else "这里还没有网站",
                    style = MaterialTheme.typography.bodyLarge,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                if (category == null) {
                    Text(
                        text = "在添加或编辑自定义网站时，把它归到这个分类，就会显示在这里。",
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(items, key = { it.siteId }) { item ->
                SiteCard(
                    item = item,
                    isOverridden = item.siteId in overriddenIds,
                    onOpen = { onOpen(item) },
                    onToggleFavorite = { onToggleFavorite(item) },
                    onNote = { onNote(item) },
                    onEditCustom = { onEditCustom(item) },
                    onDeleteSite = { onDeleteSite(item) },
                    onEditOfficial = { onEditOfficial(item) },
                    onRestoreOfficial = { onRestoreOfficial(item) },
                )
            }
        }
    }
}

/** 我的收藏页：只列网站收藏，不混作品收藏。 */
@Composable
private fun FavoritesContent(
    items: List<DirectorySiteItem>,
    overriddenIds: Set<String>,
    onOpen: (DirectorySiteItem) -> Unit,
    onToggleFavorite: (DirectorySiteItem) -> Unit,
    onNote: (DirectorySiteItem) -> Unit,
    onEditCustom: (DirectorySiteItem) -> Unit,
    onDeleteSite: (DirectorySiteItem) -> Unit,
    onEditOfficial: (DirectorySiteItem) -> Unit,
    onRestoreOfficial: (DirectorySiteItem) -> Unit,
) {
    if (items.isEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "还没有收藏网站",
                style = MaterialTheme.typography.bodyLarge,
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "在分类里点亮星标，网站就会出现在这里；这里只列网站收藏，不混入作品收藏。",
                style = MaterialTheme.typography.bodySmall,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    } else {
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(items, key = { it.siteId }) { item ->
                SiteCard(
                    item = item,
                    isOverridden = item.siteId in overriddenIds,
                    onOpen = { onOpen(item) },
                    onToggleFavorite = { onToggleFavorite(item) },
                    onNote = { onNote(item) },
                    onEditCustom = { onEditCustom(item) },
                    onDeleteSite = { onDeleteSite(item) },
                    onEditOfficial = { onEditOfficial(item) },
                    onRestoreOfficial = { onRestoreOfficial(item) },
                )
            }
        }
    }
}

/** 网站卡片：名称、简介、类型、官方域名（只显示域名）、收藏星标、打开网站。 */
@Composable
private fun SiteCard(
    item: DirectorySiteItem,
    isOverridden: Boolean,
    onOpen: () -> Unit,
    onToggleFavorite: () -> Unit,
    onNote: () -> Unit,
    onEditCustom: () -> Unit,
    onDeleteSite: () -> Unit,
    onEditOfficial: () -> Unit,
    onRestoreOfficial: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline,
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onOpen)
            .padding(vertical = 12.dp, horizontal = 16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = buildString {
                        append(item.type)
                        if (item.isCustom) append(" · 自定义")
                        if (isOverridden) append(" · 已修改")
                        append(" · ")
                        append(domainOf(item.homepageUrl))
                    },
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onToggleFavorite) {
                Icon(
                    imageVector = if (item.isFavorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                    contentDescription = if (item.isFavorite) "取消收藏" else "收藏",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (item.description.isNotBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = item.description,
                style = MaterialTheme.typography.bodySmall,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (item.note.isNotBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "备注：${item.note}",
                style = MaterialTheme.typography.bodySmall,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(onClick = onOpen) {
                Text(text = "打开网站")
            }
            if (item.isFavorite) {
                TextButton(onClick = onNote) {
                    Text(text = "备注")
                }
            }
            if (item.isCustom) {
                IconButton(onClick = onEditCustom) {
                    Icon(
                        imageVector = Icons.Filled.Edit,
                        contentDescription = "编辑自定义网站",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onDeleteSite) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = "删除自定义网站",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                IconButton(onClick = onEditOfficial) {
                    Icon(
                        imageVector = Icons.Filled.Edit,
                        contentDescription = "编辑内置网站",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onDeleteSite) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = "删除内置网站",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (isOverridden) {
                    TextButton(onClick = onRestoreOfficial) {
                        Text(text = "恢复默认")
                    }
                }
            }
        }
    }
}

/** 备注弹窗：只存用户手写文本；留空保存即清空备注。 */
@Composable
private fun NoteDialog(
    item: DirectorySiteItem,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var draft by remember(item.siteId) { mutableStateOf(item.note) }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onSave(draft) }) { Text(text = "保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(text = "取消") }
        },
        title = { Text(text = "备注 · ${item.name}") },
        text = {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = { Text(text = "写下你收藏它的原因……") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 3
            )
        },
    )
}

/**
 * 内置网站编辑表单：名称 / 简介 / URL / 所属枚举分类。简介可编
 * 辑（Wave 3 补齐：覆盖表新增 `descriptionOverride` 列；与静态
 * 简介相同的值不写入覆盖行，全不改时保存即恢复默认语义之外无
 * 任何写入）。[onSave] 返回 null 表示成功（调用方关闭本页），
 * 非 null 表示本次应显示的错误。
 */
@Composable
private fun OfficialWebsiteForm(
    item: DirectorySiteItem,
    isOverridden: Boolean,
    onSave: suspend (String, String, String, Set<InspirationCategory>) -> CustomInputError?,
    onRestoreDefault: () -> Unit,
) {
    var name by remember(item.siteId) { mutableStateOf(item.name) }
    var description by remember(item.siteId) { mutableStateOf(item.description) }
    var url by remember(item.siteId) { mutableStateOf(item.homepageUrl) }
    var selected by remember(item.siteId) { mutableStateOf(item.categories) }
    var error by remember { mutableStateOf<CustomInputError?>(null) }
    val scope = rememberCoroutineScope()
    val categories = InspirationCategory.visibleCategories
        .filter { it != InspirationCategory.ALL }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = name,
            onValueChange = { name = it; error = null },
            label = { Text(text = "网站名称（必填）") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            isError = error == CustomInputError.EMPTY_NAME,
        )
        if (error == CustomInputError.EMPTY_NAME) {
            Text(
                text = "请填写网站名称",
                style = MaterialTheme.typography.bodySmall,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        OutlinedTextField(
            value = url,
            onValueChange = { url = it; error = null },
            label = { Text(text = "网址（必填，仅 http / https）") },
            placeholder = { Text(text = "https://example.com/") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            isError = error == CustomInputError.INVALID_URL,
        )
        if (error == CustomInputError.INVALID_URL) {
            Text(
                text = "网址不合法，仅支持以 http:// 或 https:// 开头的网址",
                style = MaterialTheme.typography.bodySmall,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        OutlinedTextField(
            value = description,
            onValueChange = { description = it; error = null },
            label = { Text(text = "简介（可选，留空则用官方目录简介）") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "所属分类（可选，不选则用官方目录默认分类）",
            style = MaterialTheme.typography.bodySmall,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            categories.forEach { category ->
                FilterChip(
                    selected = category in selected,
                    onClick = {
                        selected = if (category in selected) {
                            selected - category
                        } else {
                            selected + category
                        }
                    },
                    label = { Text(text = category.label) },
                )
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
        Button(
            onClick = {
                scope.launch {
                    error = onSave(name, description, url, selected)
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(text = "保存")
        }
        if (isOverridden) {
            Spacer(modifier = Modifier.height(12.dp))
            TextButton(
                onClick = onRestoreDefault,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(text = "恢复默认（清除全部修改）")
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "修改只保存在本机，不改官方目录；升级后修改仍在，删除覆盖即恢复默认。",
            style = MaterialTheme.typography.bodySmall,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(24.dp))
    }
}

/**
 * 自定义网站表单（新增 / 编辑共用）。
 * 输入：名称（必填）+ URL（必填）+ 所属分类（可选多选；不选则只
 * 出现在“全部”视图与“我的收藏”中）+ 自定义分类（可选多选）。
 * 校验失败时在输入框下明确报错、不保存；保存时不发任何网络请求、
 * 不访问所填网址。[onSave] 返回 null 表示成功（调用方关闭本页），
 * 非 null 表示本次应显示的错误。
 */
@Composable
private fun CustomWebsiteForm(
    initialName: String,
    initialUrl: String,
    initialCategories: Set<InspirationCategory>,
    initialCustomCategories: Set<String>,
    availableCustomCategories: List<DirectoryCategory>,
    onSave: suspend (
        String, String, Set<InspirationCategory>, Set<String>
    ) -> CustomInputError?,
) {
    var name by remember { mutableStateOf(initialName) }
    var url by remember { mutableStateOf(initialUrl) }
    var selected by remember { mutableStateOf(initialCategories) }
    var selectedCustom by remember { mutableStateOf(initialCustomCategories) }
    var error by remember { mutableStateOf<CustomInputError?>(null) }
    val scope = rememberCoroutineScope()
    val categories = InspirationCategory.visibleCategories
        .filter { it != InspirationCategory.ALL }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = name,
            onValueChange = { name = it; error = null },
            label = { Text(text = "网站名称（必填）") },
            placeholder = { Text(text = "例如：我的常用图站") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            isError = error == CustomInputError.EMPTY_NAME,
        )
        if (error == CustomInputError.EMPTY_NAME) {
            Text(
                text = "请填写网站名称",
                style = MaterialTheme.typography.bodySmall,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        OutlinedTextField(
            value = url,
            onValueChange = { url = it; error = null },
            label = { Text(text = "网址（必填，仅 http / https）") },
            placeholder = { Text(text = "https://example.com/") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            isError = error == CustomInputError.INVALID_URL,
        )
        if (error == CustomInputError.INVALID_URL) {
            Text(
                text = "网址不合法，仅支持以 http:// 或 https:// 开头的网址",
                style = MaterialTheme.typography.bodySmall,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "所属分类（可选，不选则只出现在“全部”与“我的收藏”中）",
            style = MaterialTheme.typography.bodySmall,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            categories.forEach { category ->
                FilterChip(
                    selected = category in selected,
                    onClick = {
                        selected = if (category in selected) {
                            selected - category
                        } else {
                            selected + category
                        }
                    },
                    label = { Text(text = category.label) },
                )
            }
        }
        if (availableCustomCategories.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "自定义分类（可选）",
                style = MaterialTheme.typography.bodySmall,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                availableCustomCategories.forEach { custom ->
                    FilterChip(
                        selected = custom.id in selectedCustom,
                        onClick = {
                            selectedCustom = if (custom.id in selectedCustom) {
                                selectedCustom - custom.id
                            } else {
                                selectedCustom + custom.id
                            }
                        },
                        label = { Text(text = custom.name) },
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
        Button(
            onClick = {
                scope.launch {
                    error = onSave(name, url, selected, selectedCustom)
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(text = "保存")
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "保存时不访问该网址、不抓取内容、不生成预览；只有你主动点“打开网站”时，才由系统浏览器打开。",
            style = MaterialTheme.typography.bodySmall,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(24.dp))
    }
}

/**
 * 分类管理页：内置分类不可删除、不可改名（名取自枚举 label），
 * 只可隐藏与排序；自定义分类可增删改名排序。删除自定义分类时
 * 不删站，只解除归属；删除仍含网站的分类时，须先处理这些网站
 * 的归属（移到其他分类或仅解除归属），并有确认提示。
 */
@Composable
private fun ManageCategoriesScreen(
    favorites: List<WebsiteFavorite>,
    categories: List<DirectoryCategory>,
    viewModel: VisualInspirationViewModel,
) {
    val scope = rememberCoroutineScope()
    val rows = effectiveCategories(categories)
    var newName by remember { mutableStateOf("") }
    var addError by remember { mutableStateOf(false) }
    var actionError by remember { mutableStateOf<String?>(null) }
    var renameTarget by remember { mutableStateOf<DirectoryCategory?>(null) }
    var renameDraft by remember { mutableStateOf("") }
    var deleteTarget by remember { mutableStateOf<DirectoryCategory?>(null) }
    /** 删除分类时网站的去向：null = 仅解除归属；否则为目标分类 ID。 */
    var moveTargetId by remember(deleteTarget) { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
    ) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "内置分类不可删除、不可改名，只可隐藏与排序；" +
                "自定义分类可增删改名排序。删除分类不删除网站，只解除归属。",
            style = MaterialTheme.typography.bodySmall,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(16.dp))
        OutlinedTextField(
            value = newName,
            onValueChange = { newName = it; addError = false },
            label = { Text(text = "新分类名称") },
            placeholder = { Text(text = "例如：我的灵感") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            isError = addError,
        )
        if (addError) {
            Text(
                text = "请填写分类名称",
                style = MaterialTheme.typography.bodySmall,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
        Button(
            onClick = {
                scope.launch {
                    if (newName.trim().isEmpty()) {
                        addError = true
                        return@launch
                    }
                    val created = viewModel.addCategory(newName)
                    if (created == null) {
                        addError = true
                    } else {
                        newName = ""
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(text = "添加自定义分类")
        }
        if (actionError != null) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = actionError ?: "",
                style = MaterialTheme.typography.bodySmall,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "全部分类（按显示顺序）",
            style = MaterialTheme.typography.titleMedium,
            fontSize = 16.sp
        )
        Spacer(modifier = Modifier.height(12.dp))
        if (rows.isEmpty()) {
            Text(
                text = "还没有分类，正在载入内置分类……",
                style = MaterialTheme.typography.bodySmall,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        rows.forEachIndexed { index, row ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outline,
                        shape = RoundedCornerShape(12.dp)
                    )
                    .padding(vertical = 12.dp, horizontal = 16.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = row.name,
                            style = MaterialTheme.typography.bodyLarge,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = buildString {
                                append(if (row.isBuiltin) "内置分类" else "自定义分类")
                                if (row.isHidden) append(" · 已隐藏")
                                if (!row.isBuiltin) {
                                    append(" · ")
                                    append(customCategorySiteCount(favorites, row.id))
                                    append(" 家")
                                }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = { viewModel.moveCategory(row.id, -1) },
                        enabled = index > 0,
                    ) {
                        Text(text = "上移")
                    }
                    TextButton(
                        onClick = { viewModel.moveCategory(row.id, 1) },
                        enabled = index < rows.size - 1,
                    ) {
                        Text(text = "下移")
                    }
                    TextButton(
                        onClick = {
                            scope.launch {
                                val ok = viewModel.setCategoryHidden(row.id, !row.isHidden)
                                if (!ok) actionError = "操作失败，请重试"
                            }
                        },
                    ) {
                        Text(text = if (row.isHidden) "显示" else "隐藏")
                    }
                    if (!row.isBuiltin) {
                        TextButton(
                            onClick = {
                                renameTarget = row
                                renameDraft = row.name
                            },
                        ) {
                            Text(text = "改名")
                        }
                        TextButton(
                            onClick = { deleteTarget = row },
                        ) {
                            Text(text = "删除")
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }
        Spacer(modifier = Modifier.height(24.dp))
    }

    val renameRow = renameTarget
    if (renameRow != null) {
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            val ok = viewModel.renameCategory(renameRow.id, renameDraft)
                            if (ok) {
                                renameTarget = null
                            } else {
                                actionError = "改名失败：名称不能为空"
                            }
                        }
                    }
                ) { Text(text = "保存") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text(text = "取消") }
            },
            title = { Text(text = "改名 · ${renameRow.name}") },
            text = {
                OutlinedTextField(
                    value = renameDraft,
                    onValueChange = { renameDraft = it },
                    label = { Text(text = "分类名称") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            },
        )
    }

    val deleteRow = deleteTarget
    if (deleteRow != null) {
        val affected = customCategorySiteCount(favorites, deleteRow.id)
        val moveOptions = rows.filter { it.id != deleteRow.id && !it.isHidden }
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            val targetId = moveTargetId
                            if (targetId != null) {
                                viewModel.reassignCategory(deleteRow.id, targetId)
                            }
                            val ok = viewModel.deleteCategory(deleteRow.id)
                            if (ok) {
                                deleteTarget = null
                            } else {
                                actionError = "删除失败，请重试"
                            }
                        }
                    }
                ) { Text(text = "删除分类") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text(text = "取消") }
            },
            title = { Text(text = "删除分类 · ${deleteRow.name}") },
            text = {
                Column {
                    Text(
                        text = if (affected == 0) {
                            "该分类下没有网站。删除后分类消失，网站不受影响。"
                        } else {
                            "该分类下还有 $affected 个网站。删除分类不会删除网站，" +
                                "请先决定这些网站的归属。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 12.sp,
                    )
                    if (affected > 0) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "这些网站移到：",
                            style = MaterialTheme.typography.bodySmall,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = moveTargetId == null,
                                onClick = { moveTargetId = null },
                                label = { Text(text = "仅解除归属（不移动）") },
                            )
                            moveOptions.forEach { option ->
                                FilterChip(
                                    selected = moveTargetId == option.id,
                                    onClick = { moveTargetId = option.id },
                                    label = { Text(text = option.name) },
                                )
                            }
                        }
                    }
                }
            },
        )
    }
}
