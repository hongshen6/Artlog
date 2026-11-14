// JVM test for directory categories + cat:<uuid> tokens (next
// iteration, database v6) — NOT part of the app build (lives under
// manual-build/jvm-test, never shipped). Compiles the real data-layer
// sources with kotlinc and runs the Repository against an in-memory
// fake DAO: no device, no Android runtime, no Room, no network. The
// DAO's SQL itself (CREATE TABLE / REPLACE / DELETE) is covered by
// verify_migration.py (v5 -> v6 data test); what is tested here is
// the Repository logic + the category-token encode/decode.
//
// Covered:
//  1. ensureSeeded: empty table gains exactly the 5 builtin rows in
//     order (ANIME 0, REALISTIC 1, PHOTOGRAPHY 2, ARCHITECTURE 3,
//     LANDSCAPE 4); a second call does not duplicate; a non-empty
//     table is left alone.
//  2. addCustomCategory: valid name returns a `cat:<uuid>` row sorted
//     last; empty name refuses.
//  3. renameCategory: custom rows rename; builtin rows and missing
//     rows refuse; empty name refuses.
//  4. setHidden: hides/shows builtin and custom rows.
//  5. deleteCustomCategory: custom rows delete; builtin rows refuse.
//  6. reorderCategories: order follows the given id list; unknown
//     ids are ignored.
//  7. encodeCategoryTokens / decodeCategoryTokens: enum names +
//     `cat:<uuid>` tokens round-trip; unknown tokens are dropped
//     (same tolerance as decodeCategories); ALL/OTHER never encode;
//     `cat:` with an empty suffix is dropped.
//  8. Existing encode/decodeCategories behavior is unchanged
//     (cat: tokens are dropped from the enum-only decode).
package com.artlog.app.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

private var passed = 0
private var failed = 0

private fun check(name: String, cond: Boolean) {
    if (cond) {
        passed++
        println("PASS: $name")
    } else {
        failed++
        println("FAIL: $name")
    }
}

private class FakeDirectoryCategoryDao : DirectoryCategoryDao {
    private val rows = LinkedHashMap<String, DirectoryCategory>()
    private val flow = MutableStateFlow<List<DirectoryCategory>>(emptyList())

    private fun emit() {
        flow.value = rows.values.sortedBy { it.sortOrder }
    }

    override suspend fun getCategory(id: String): DirectoryCategory? = rows[id]
    override fun observeCategories(): Flow<List<DirectoryCategory>> = flow
    override fun observeVisibleCategories(): Flow<List<DirectoryCategory>> =
        flow.map { list -> list.filter { c -> !c.isHidden } }

    override suspend fun countCategories(): Int = rows.size
    override suspend fun maxSortOrder(): Int? = rows.values.maxOfOrNull { it.sortOrder }
    override suspend fun insertCategory(category: DirectoryCategory) {
        rows[category.id] = category
        emit()
    }

    override suspend fun updateCategory(category: DirectoryCategory) {
        rows[category.id] = category
        emit()
    }

    override suspend fun deleteCategory(id: String) {
        rows.remove(id)
        emit()
    }

    override suspend fun deleteAllCategories() {
        rows.clear()
        emit()
    }
}

fun main() = runBlocking {
    val dao = FakeDirectoryCategoryDao()
    val repo = DirectoryCategoryRepository(dao)

    // ---- 1. ensureSeeded ----
    repo.ensureSeeded()
    val seeded = repo.observeCategories().first()
    check("ensureSeeded writes exactly 5 builtin rows",
        seeded.size == 5)
    check("seed ids/order match visibleCategories order",
        seeded.map { it.id } == listOf(
            "ANIME", "REALISTIC", "PHOTOGRAPHY", "ARCHITECTURE", "LANDSCAPE"
        ) && seeded.map { it.sortOrder } == listOf(0, 1, 2, 3, 4))
    check("seed rows are builtin, visible, with builtin labels",
        seeded.all { it.isBuiltin && !it.isHidden } &&
            seeded[0].name == "二次元插画" && seeded[2].name == "情绪摄影" &&
            seeded[4].name == "风景与氛围")
    repo.ensureSeeded()
    check("second ensureSeeded does not duplicate",
        repo.observeCategories().first().size == 5)
    check("visible categories are the 5 seeds",
        repo.observeVisibleCategories().first().size == 5)

    // ---- 2. addCustomCategory ----
    val custom = repo.addCustomCategory("我的分类")
    check("addCustomCategory returns a cat:<uuid> row sorted last",
        custom != null && custom.id.startsWith("cat:") &&
            custom.id.length > "cat:".length && !custom.isBuiltin &&
            !custom.isHidden && custom.sortOrder == 5 &&
            custom.name == "我的分类")
    check("addCustomCategory empty name refuses",
        repo.addCustomCategory("   ") == null &&
            repo.observeCategories().first().size == 6)

    // ---- 3. renameCategory ----
    val customId = custom!!.id
    check("renameCategory on a custom row succeeds",
        repo.renameCategory(customId, "改名后的分类") &&
            repo.getCategory(customId)?.name == "改名后的分类")
    check("renameCategory on a builtin row refuses",
        !repo.renameCategory("ANIME", "改名") &&
            repo.getCategory("ANIME")?.name == "二次元插画")
    check("renameCategory on a missing row refuses",
        !repo.renameCategory("cat:missing", "改名"))
    check("renameCategory with an empty name refuses and keeps the old name",
        !repo.renameCategory(customId, "   ") &&
            repo.getCategory(customId)?.name == "改名后的分类")

    // ---- 4. setHidden ----
    check("setHidden hides a builtin category",
        repo.setHidden("LANDSCAPE", true) &&
            repo.getCategory("LANDSCAPE")?.isHidden == true)
    check("hidden category leaves the visible list",
        repo.observeVisibleCategories().first().none { it.id == "LANDSCAPE" } &&
            repo.observeVisibleCategories().first().size == 5)
    check("setHidden(false) shows it again",
        repo.setHidden("LANDSCAPE", false) &&
            repo.observeVisibleCategories().first().size == 6)
    check("setHidden on a missing row refuses",
        !repo.setHidden("cat:missing", true))

    // ---- 5. deleteCustomCategory ----
    check("deleteCustomCategory on a builtin row refuses",
        !repo.deleteCustomCategory("ANIME") &&
            repo.getCategory("ANIME") != null)
    check("deleteCustomCategory on a custom row succeeds",
        repo.deleteCustomCategory(customId) &&
            repo.getCategory(customId) == null &&
            repo.observeCategories().first().size == 5)
    check("deleteCustomCategory on a missing row refuses",
        !repo.deleteCustomCategory("cat:missing"))

    // ---- 6. reorderCategories ----
    repo.reorderCategories(
        listOf("LANDSCAPE", "ANIME", "REALISTIC", "PHOTOGRAPHY", "ARCHITECTURE")
    )
    val reordered = repo.observeCategories().first()
    check("reorderCategories follows the given id order",
        reordered.map { it.id } == listOf(
            "LANDSCAPE", "ANIME", "REALISTIC", "PHOTOGRAPHY", "ARCHITECTURE"
        ) && reordered.map { it.sortOrder } == listOf(0, 1, 2, 3, 4))
    repo.reorderCategories(listOf("ANIME", "BOGUS-ID", "REALISTIC"))
    val partial = repo.observeCategories().first()
    check("reorderCategories ignores unknown ids (known ids still move)",
        partial.find { it.id == "ANIME" }?.sortOrder == 0 &&
            partial.find { it.id == "REALISTIC" }?.sortOrder == 1)

    // ---- 7. encode/decodeCategoryTokens ----
    val encoded = WebsiteFavoriteRepository.encodeCategoryTokens(
        setOf(InspirationCategory.REALISTIC, InspirationCategory.ANIME),
        setOf("cat:aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", "cat:11111111-2222-3333-4444-555555555555")
    )
    check("encodeCategoryTokens joins enums + cat: tokens sorted",
        encoded == "ANIME,REALISTIC,cat:11111111-2222-3333-4444-555555555555," +
            "cat:aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee")
    val (enums, customs) = WebsiteFavoriteRepository.decodeCategoryTokens(
        "ANIME,BOGUS,cat:11111111-2222-3333-4444-555555555555,REALISTIC,cat:,OTHER"
    )
    check("decodeCategoryTokens drops unknown tokens, keeps enums + cat: ids",
        enums == setOf(InspirationCategory.ANIME, InspirationCategory.REALISTIC) &&
            customs == setOf("cat:11111111-2222-3333-4444-555555555555"))
    check("decodeCategoryTokens null/blank gives empty pair",
        WebsiteFavoriteRepository.decodeCategoryTokens(null) ==
            (emptySet<InspirationCategory>() to emptySet<String>()) &&
            WebsiteFavoriteRepository.decodeCategoryTokens("") ==
            (emptySet<InspirationCategory>() to emptySet<String>()))
    check("encodeCategoryTokens drops ALL/OTHER and empty cat: suffix",
        WebsiteFavoriteRepository.encodeCategoryTokens(
            setOf(InspirationCategory.ALL, InspirationCategory.OTHER),
            setOf("cat:", "not-a-cat")
        ) == null)

    // ---- 8. Existing enum-only encode/decode unchanged ----
    check("encodeCategories still enum-only (no cat: tokens)",
        WebsiteFavoriteRepository.encodeCategories(
            setOf(InspirationCategory.REALISTIC, InspirationCategory.ANIME)
        ) == "ANIME,REALISTIC")
    check("decodeCategories drops cat: tokens (enum-only view)",
        WebsiteFavoriteRepository.decodeCategories(
            "ANIME,cat:11111111-2222-3333-4444-555555555555,REALISTIC"
        ) == setOf(InspirationCategory.ANIME, InspirationCategory.REALISTIC))

    println("TestCategories: $passed passed, $failed failed")
    if (failed > 0) kotlin.system.exitProcess(1)
}
