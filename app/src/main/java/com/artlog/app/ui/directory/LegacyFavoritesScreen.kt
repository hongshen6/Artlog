package com.artlog.app.ui.directory

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.artlog.app.data.ArtworkDates
import com.artlog.app.data.InspirationCategory
import com.artlog.app.data.InspirationContentType
import com.artlog.app.data.InspirationFavorite
import com.artlog.app.data.displayImageUrl
import com.artlog.app.data.effectiveContentType

/**
 * 旧灵感收藏只读页（Artlog 2.0 阶段 3）。
 *
 * 只复用旧收藏详情的只读展示结构（列表行 + 详情），不复用旧 Feed
 * 的任何网络请求与推荐逻辑。展示内容全部来自
 * `InspirationRepository.favorites` Flow（已过安全门降级）：
 * 标题、作者、来源链接与用户备注（只读 `note` 字段）。
 *
 * 本文件没有任何新增 / 取消收藏 / 编辑 / 删除入口，不调用
 * `toggleFavorite` / `updateNote` / `deleteFavorite` /
 * `insertFavorite`。图片展示沿用旧收藏详情的只读模式：
 * `effectiveContentType()` 分支 + `displayImageUrl()` 取图；图片
 * 文件已丢失（加载失败）时显示明确的缺失状态，不崩溃。
 */

/** 只读收藏列表：点一行进详情；空态说明这是历史记录。 */
@Composable
fun LegacyFavoritesList(
    favorites: List<InspirationFavorite>,
    onFavoriteClick: (InspirationFavorite) -> Unit,
) {
    if (favorites.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = 20.dp)
            ) {
                Text(
                    text = "还没有旧灵感收藏",
                    style = MaterialTheme.typography.bodyLarge,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "这里只保存旧版灵感推荐里收藏过的作品，不可新增。",
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    } else {
        LazyColumn(
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(favorites, key = { it.id }) { favorite ->
                // 与旧收藏列表同一规则：类型以安全门的裁决为准，不以
                // 库里存的字符串为准；LINK 收藏不显示任何图片。
                val favoriteType = favorite.effectiveContentType()
                var thumbFailed by remember(favorite.id) { mutableStateOf(false) }
                var thumbRetryKey by remember(favorite.id) { mutableIntStateOf(0) }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onFavoriteClick(favorite) },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        if (favoriteType == InspirationContentType.LINK) {
                            Icon(
                                imageVector = Icons.Filled.OpenInNew,
                                contentDescription = "外部作品链接",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(24.dp)
                            )
                        } else {
                            key(thumbRetryKey) {
                                AsyncImage(
                                    model = displayImageUrl(favorite),
                                    contentDescription = favorite.title,
                                    contentScale = ContentScale.FillWidth,
                                    modifier = Modifier.fillMaxWidth(),
                                    onSuccess = { thumbFailed = false },
                                    onError = { thumbFailed = true }
                                )
                            }
                            if (thumbFailed) {
                                IconButton(
                                    onClick = { thumbFailed = false; thumbRetryKey++ },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.BrokenImage,
                                        contentDescription = "图片无法加载，点按重试",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = favorite.title,
                            style = MaterialTheme.typography.bodyLarge,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = favorite.author ?: favorite.source,
                            style = MaterialTheme.typography.bodySmall,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "收藏于 ${ArtworkDates.formatDay(favorite.savedAt)}",
                            style = MaterialTheme.typography.bodySmall,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (favoriteType == InspirationContentType.LINK) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "仅链接 · 在浏览器中查看原文",
                                style = MaterialTheme.typography.bodySmall,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 只读收藏详情：图片（或缺失状态）+ 标题 + 作者 + 来源 + 只读备注 + 打开原站。 */
@Composable
fun LegacyFavoriteDetail(
    favorite: InspirationFavorite,
    onOpenOriginal: () -> Unit,
) {
    var loadFailed by remember(favorite.id) { mutableStateOf(false) }
    var retryKey by remember(favorite.id) { mutableIntStateOf(0) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
        Spacer(modifier = Modifier.height(8.dp))
        val favoriteType = favorite.effectiveContentType()
        if (favoriteType == InspirationContentType.LINK) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(vertical = 32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Filled.OpenInNew,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "仅链接 · 在浏览器中查看原文",
                        style = MaterialTheme.typography.bodySmall,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                key(retryKey) {
                    AsyncImage(
                        model = displayImageUrl(favorite),
                        contentDescription = favorite.title,
                        contentScale = ContentScale.FillWidth,
                        modifier = Modifier.fillMaxWidth(),
                        onSuccess = { loadFailed = false },
                        onError = { loadFailed = true }
                    )
                }
                if (loadFailed) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(vertical = 32.dp)
                    ) {
                        IconButton(onClick = { loadFailed = false; retryKey++ }) {
                            Icon(
                                imageVector = Icons.Filled.BrokenImage,
                                contentDescription = "图片无法加载，点按重试",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                        Text(
                            text = "图片无法加载，原图可能已失效",
                            style = MaterialTheme.typography.bodySmall,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = favorite.title,
            style = MaterialTheme.typography.titleLarge,
            fontSize = 18.sp
        )
        if (favorite.author != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = favorite.author!!,
                style = MaterialTheme.typography.bodyLarge,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = buildString {
                append(favorite.source)
                append(" · ")
                append(InspirationCategory.safeValueOf(favorite.category).label)
                favorite.license?.let { append(" · ").append(it) }
            },
            style = MaterialTheme.typography.bodySmall,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "收藏于 ${ArtworkDates.formatDay(favorite.savedAt)}",
            style = MaterialTheme.typography.bodySmall,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "我的感受",
            style = MaterialTheme.typography.titleMedium,
            fontSize = 16.sp
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = favorite.note.ifBlank { "没有备注" },
            style = MaterialTheme.typography.bodyLarge,
            fontSize = 14.sp,
            color = if (favorite.note.isBlank()) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            }
        )

        Spacer(modifier = Modifier.height(24.dp))
        OutlinedButton(onClick = onOpenOriginal) {
            Text(text = "打开原始页面")
        }
        Spacer(modifier = Modifier.height(24.dp))
    }
}
