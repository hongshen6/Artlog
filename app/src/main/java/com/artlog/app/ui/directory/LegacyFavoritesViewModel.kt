package com.artlog.app.ui.directory

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.artlog.app.data.InspirationFavorite
import com.artlog.app.data.InspirationRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * 旧灵感收藏（只读历史入口）的 ViewModel（Artlog 2.0 阶段 3）。
 *
 * 只读的三条红线（stage0-check §2.4），本类以结构保证：
 * 1. 只读 [InspirationRepository.favorites] Flow——Repository 层已做
 *    `displaySafe()` 安全门降级；绝不直读 `InspirationDao`。
 * 2. 本类没有任何写方法：不提供 [InspirationRepository.toggleFavorite] /
 *    `updateNote` / `deleteFavorite` / `insertFavorite` 的调用入口。
 *    备注只读 `note` 字段展示——`updateNote()` 会把门降级后的行写
 *    回库，只读页绝不能调它。
 * 3. 不提供新增、取消收藏、编辑、删除的任何入口；UI 层同样只做
 *    展示（见 LegacyFavoritesScreen）。
 */
class LegacyFavoritesViewModel(
    inspirationRepository: InspirationRepository,
) : ViewModel() {

    /** 旧作品收藏（经安全门降级，按收藏时间倒序），只读。 */
    val favorites: StateFlow<List<InspirationFavorite>> =
        inspirationRepository.favorites
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
