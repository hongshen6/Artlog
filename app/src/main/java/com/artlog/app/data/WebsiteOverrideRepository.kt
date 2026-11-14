package com.artlog.app.data

/**
 * 网站覆盖的 Repository（下一阶段迭代，数据库 v6）。
 *
 * 读时合并的唯一真相来源：展示层先读 [VisualDirectory] 静态配置，
 * 再按 `siteId` 叠加覆盖行（见 `ui/directory/DirectoryUiLogic.kt`
 * 的 `resolveDirectory` 纯函数）。本类只负责覆盖行的读写，不做
 * 合并、不碰静态配置。
 *
 * - 覆盖行指向不存在的 slug 时，合并层忽略、不崩溃（见
 *   `resolveDirectory`）；本类不校验 slug 是否在目录中，写库时
 *   只校验 URL 覆盖的格式。
 * - 删除官方站（`isDeleted = true`）后，收藏行如存在应由调用方
 *   同步删除（取消收藏语义）；反向不成立（取消收藏不写覆盖行）。
 * - 删除覆盖行即恢复默认。
 *
 * 本类不依赖 Android 框架（除 Room DAO 接口外），可在 JVM 上直接
 * 用假 DAO 测试（见 manual-build/jvm-test/TestOverrides.kt）。
 */
class WebsiteOverrideRepository(private val dao: WebsiteOverrideDao) {

    /** 全部覆盖行（按 siteId 排序）。 */
    fun observeOverrides(): kotlinx.coroutines.flow.Flow<List<WebsiteOverride>> =
        dao.observeOverrides()

    /** 按网站 slug 查询一行覆盖；未覆盖返回 null。 */
    suspend fun getOverride(siteId: String): WebsiteOverride? = dao.getOverride(siteId)

    /** 该站是否被用户删除（隐藏）。无覆盖行时为 false。 */
    suspend fun isDeleted(siteId: String): Boolean =
        dao.getOverride(siteId)?.isDeleted == true

    /**
     * 写入一行覆盖（整行 REPLACE）。[urlOverride] 非空时须通过
     * [isValidHttpUrl]，否则返回 false（不写库）；其余字段不校验。
     */
    suspend fun upsertOverride(override: WebsiteOverride): Boolean {
        val url = override.urlOverride
        if (url != null && !isValidHttpUrl(url)) return false
        dao.insertOverride(override)
        return true
    }

    /**
     * 标记一个官方站为已删除 / 恢复删除。保留该站已有的其他覆盖
     * 字段（改名/改 URL 不丢失）；无覆盖行时新建一行（其余字段
     * 为空）。
     */
    suspend fun setDeleted(siteId: String, deleted: Boolean) {
        val existing = dao.getOverride(siteId)
        dao.insertOverride(
            (existing ?: WebsiteOverride(
                siteId = siteId,
                isDeleted = false,
                nameOverride = null,
                descriptionOverride = null,
                urlOverride = null,
                categoriesOverride = null,
                updatedAt = 0L
            )).copy(
                isDeleted = deleted,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    /** 删除一行覆盖（恢复该站的默认展示）。行不存在时不报错。 */
    suspend fun deleteOverride(siteId: String) {
        dao.deleteOverride(siteId)
    }
}
