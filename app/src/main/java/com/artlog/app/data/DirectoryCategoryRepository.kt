package com.artlog.app.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.util.UUID

/**
 * 目录分类的 Repository（下一阶段迭代，数据库 v6）。
 *
 * 种子规则：升级走 MIGRATION_5_6 的 5 条 `INSERT OR IGNORE`；全
 * 新安装时 Room 只建表不跑迁移，须由调用方在首次读取前调一次
 * [ensureSeeded]（表空时才补 5 行内置分类；表非空时什么也不做，
 * 用户删/藏过的状态不会被种子覆盖）。
 *
 * 内置分类的限制（安全默认）：不可删除、不可改名，只可隐藏与排
 * 序；自定义分类可增删改名排序。删除自定义分类时不删站，只解除
 * 归属。
 *
 * 本类不依赖 Android 框架（除 Room DAO 接口外），可在 JVM 上直接
 * 用假 DAO 测试（见 manual-build/jvm-test/TestCategories.kt）。
 */
class DirectoryCategoryRepository(private val dao: DirectoryCategoryDao) {

    companion object {
        /** 种子行创建时间（毫秒时间戳；与 MIGRATION_5_6 的种子 INSERT 一致）。 */
        const val SEED_CREATED_AT = 1700000000000L

        /** 5 个内置分类（id / 显示名 / sortOrder），顺序与现有 visibleCategories 一致。 */
        val BUILTIN_SEEDS: List<DirectoryCategory> = listOf(
            DirectoryCategory("ANIME", InspirationCategory.ANIME.label, 0, true, false, SEED_CREATED_AT),
            DirectoryCategory("REALISTIC", InspirationCategory.REALISTIC.label, 1, true, false, SEED_CREATED_AT),
            DirectoryCategory("PHOTOGRAPHY", InspirationCategory.PHOTOGRAPHY.label, 2, true, false, SEED_CREATED_AT),
            DirectoryCategory("ARCHITECTURE", InspirationCategory.ARCHITECTURE.label, 3, true, false, SEED_CREATED_AT),
            DirectoryCategory("LANDSCAPE", InspirationCategory.LANDSCAPE.label, 4, true, false, SEED_CREATED_AT),
        )
    }

    /** 全部分类（按 sortOrder 升序）。 */
    fun observeCategories(): Flow<List<DirectoryCategory>> = dao.observeCategories()

    /** 未隐藏的分类（按 sortOrder 升序）。 */
    fun observeVisibleCategories(): Flow<List<DirectoryCategory>> =
        dao.observeVisibleCategories()

    /** 按分类 ID 查询一行；不存在返回 null。 */
    suspend fun getCategory(id: String): DirectoryCategory? = dao.getCategory(id)

    /**
     * 表空时补 5 行内置分类种子；表非空时不做任何事。供全新安装
     * 首次读取前调用（升级路径由 MIGRATION_5_6 负责，不依赖此方法）。
     */
    suspend fun ensureSeeded() {
        if (dao.countCategories() == 0) {
            BUILTIN_SEEDS.forEach { dao.insertCategory(it) }
        }
    }

    /**
     * 新增自定义分类。名称去空白后不能为空；成功返回新建的行
     * （`cat:<uuid>`，排在最后）；校验失败返回 null（不写库）。
     */
    suspend fun addCustomCategory(name: String): DirectoryCategory? {
        val cleanName = name.trim()
        if (cleanName.isEmpty()) return null
        val nextOrder = (dao.maxSortOrder() ?: -1) + 1
        val category = DirectoryCategory(
            id = "cat:" + UUID.randomUUID().toString(),
            name = cleanName,
            sortOrder = nextOrder,
            isBuiltin = false,
            isHidden = false,
            createdAt = System.currentTimeMillis()
        )
        dao.insertCategory(category)
        return category
    }

    /**
     * 改名。只允许改自定义分类：行不存在、或行是内置分类时返回
     * false；名称为空时返回 false。
     */
    suspend fun renameCategory(id: String, name: String): Boolean {
        val existing = dao.getCategory(id) ?: return false
        if (existing.isBuiltin) return false
        val cleanName = name.trim()
        if (cleanName.isEmpty()) return false
        dao.updateCategory(existing.copy(name = cleanName))
        return true
    }

    /** 设置一个分类的隐藏状态（内置与自定义均可）。行不存在返回 false。 */
    suspend fun setHidden(id: String, hidden: Boolean): Boolean {
        val existing = dao.getCategory(id) ?: return false
        dao.updateCategory(existing.copy(isHidden = hidden))
        return true
    }

    /**
     * 删除一个自定义分类（只解除归属，不删站）。行不存在、或是内
     * 置分类时返回 false。
     */
    suspend fun deleteCustomCategory(id: String): Boolean {
        val existing = dao.getCategory(id) ?: return false
        if (existing.isBuiltin) return false
        dao.deleteCategory(id)
        return true
    }

    /**
     * 按给定 ID 顺序重排全部的分量：列表顺序即 sortOrder（0 起）。
     * 未列出的分类保持原 sortOrder 不变；未知 ID 忽略。
     */
    suspend fun reorderCategories(orderedIds: List<String>) {
        val current = dao.observeCategories().first()
        val byId = current.associateBy { it.id }
        var nextOrder = 0
        orderedIds.forEach { id ->
            val row = byId[id] ?: return@forEach
            if (row.sortOrder != nextOrder) {
                dao.updateCategory(row.copy(sortOrder = nextOrder))
            }
            nextOrder++
        }
    }
}
