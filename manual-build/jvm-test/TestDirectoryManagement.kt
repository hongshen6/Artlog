// JVM test for stage-4 directory management (next iteration) — NOT
// part of the app build (lives under manual-build/jvm-test, never
// shipped). Compiles the real data-layer sources + the pure
// ui/directory/DirectoryUiLogic.kt with kotlinc and runs against
// in-memory fake DAOs: no device, no Android runtime, no Room, no
// network. The ViewModel itself (AndroidX lifecycle) is not compiled
// here; what is tested is exactly the logic the ViewModel delegates
// to: resolved-directory display, custom-category listing, category
// reassignment, and the effective-category fallback.
//
// Covered:
//  1. effectiveCategories: an empty row list falls back to the 5
//     builtin seeds (so Home never shows a blank category list
//     while seeding); a non-empty list keeps its sortOrder; hidden
//     rows are dropped by visibleEffectiveCategories only.
//  2. buildCategoryItems with a resolved directory: a deleted
//     official site is absent; a renamed / re-URLed site shows the
//     override values; directoryCategoryCounts / directoryAllCount
//     count the resolved list (deleted sites do not count).
//  3. buildFavoriteItems with a resolved directory: an official
//     favorite shows the overridden name/URL.
//  4. buildCustomCategoryItems: a custom site carrying the cat:
//     token appears under its custom category and not under another
//     one; official sites never appear under a custom category
//     (resolveDirectory drops cat: tokens for official sites);
//     a deleted category id yields an empty list (sites are kept,
//     only the attribution is gone).
//  5. customCategorySiteCount counts the sites referencing a
//     custom category (delete-confirmation input).
//  6. reassignCategoryTokens: custom -> custom, custom -> builtin
//     enum, unassign (toId null); an unknown fromId leaves the raw
//     string untouched; an invalid toId degrades to unassign.
//  7. resolveFavoriteItem decodes cat: tokens into
//     DirectorySiteItem.customCategories for custom sites.
//  8. categoryDisplayName resolves builtin labels and custom names.
package com.artlog.app.data

import com.artlog.app.ui.directory.buildCategoryItems
import com.artlog.app.ui.directory.buildCustomCategoryItems
import com.artlog.app.ui.directory.buildFavoriteItems
import com.artlog.app.ui.directory.categoryDisplayName
import com.artlog.app.ui.directory.customCategorySiteCount
import com.artlog.app.ui.directory.directoryAllCount
import com.artlog.app.ui.directory.directoryCategoryCounts
import com.artlog.app.ui.directory.effectiveCategories
import com.artlog.app.ui.directory.reassignCategoryTokens
import com.artlog.app.ui.directory.resolveDirectory
import com.artlog.app.ui.directory.resolveFavoriteItem
import com.artlog.app.ui.directory.visibleEffectiveCategories
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

private class FakeWebsiteFavoriteDao : WebsiteFavoriteDao {
    private val rows = LinkedHashMap<String, WebsiteFavorite>()
    private val flow = MutableStateFlow<List<WebsiteFavorite>>(emptyList())

    private fun emit() {
        flow.value = rows.values.sortedByDescending { it.savedAt }
    }

    override suspend fun getFavorite(siteId: String): WebsiteFavorite? = rows[siteId]
    override fun observeFavorites(): Flow<List<WebsiteFavorite>> = flow
    override suspend fun insertFavorite(favorite: WebsiteFavorite) {
        rows[favorite.siteId] = favorite
        emit()
    }

    override suspend fun updateFavorite(favorite: WebsiteFavorite) {
        rows[favorite.siteId] = favorite
        emit()
    }

    override suspend fun deleteFavorite(siteId: String) {
        rows.remove(siteId)
        emit()
    }

    override suspend fun deleteAllFavorites() {
        rows.clear()
        emit()
    }
}

private class FakeWebsiteOverrideDao : WebsiteOverrideDao {
    private val rows = LinkedHashMap<String, WebsiteOverride>()
    private val flow = MutableStateFlow<List<WebsiteOverride>>(emptyList())

    private fun emit() {
        flow.value = rows.values.sortedBy { it.siteId }
    }

    override suspend fun getOverride(siteId: String): WebsiteOverride? = rows[siteId]
    override fun observeOverrides(): Flow<List<WebsiteOverride>> = flow
    override suspend fun insertOverride(override: WebsiteOverride) {
        rows[override.siteId] = override
        emit()
    }

    override suspend fun updateOverride(override: WebsiteOverride) {
        rows[override.siteId] = override
        emit()
    }

    override suspend fun deleteOverride(siteId: String) {
        rows.remove(siteId)
        emit()
    }

    override suspend fun deleteAllOverrides() {
        rows.clear()
        emit()
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
    val favoriteRepo = WebsiteFavoriteRepository(FakeWebsiteFavoriteDao())
    val overrideRepo = WebsiteOverrideRepository(FakeWebsiteOverrideDao())
    val categoryRepo = DirectoryCategoryRepository(FakeDirectoryCategoryDao())

    // ---- 1. effectiveCategories ----
    val fallback = effectiveCategories(emptyList())
    check("empty category list falls back to the 5 builtin seeds",
        fallback.size == 5 && fallback.map { it.id } == listOf(
            "ANIME", "REALISTIC", "PHOTOGRAPHY", "ARCHITECTURE", "LANDSCAPE"
        ))
    check("fallback seeds are visible and sorted",
        fallback.map { it.sortOrder } == listOf(0, 1, 2, 3, 4) &&
            visibleEffectiveCategories(emptyList()).size == 5)
    categoryRepo.ensureSeeded()
    val seeded = categoryRepo.observeCategories().first()
    check("seeded list is used as-is (not replaced by fallback)",
        effectiveCategories(seeded).map { it.id } == seeded.map { it.id })
    categoryRepo.setHidden("LANDSCAPE", true)
    val afterHide = categoryRepo.observeCategories().first()
    check("hidden builtin row stays in effective list but leaves the visible list",
        effectiveCategories(afterHide).size == 5 &&
            visibleEffectiveCategories(afterHide).size == 4 &&
            visibleEffectiveCategories(afterHide).none { it.id == "LANDSCAPE" })
    categoryRepo.setHidden("LANDSCAPE", false)
    val custom = categoryRepo.addCustomCategory("我的分类")!!
    val customId = custom.id
    check("custom category sorts last (sortOrder 5)",
        effectiveCategories(categoryRepo.observeCategories().first()).last().id == customId)

    // ---- 2. buildCategoryItems with a resolved directory ----
    overrideRepo.upsertOverride(
        WebsiteOverride("uisdc", false, "优设改名", null, "https://www.uisdc.com/", null, 1000L)
    )
    overrideRepo.setDeleted("cphoto", true)
    val resolved = resolveDirectory(overrideRepo.observeOverrides().first())
    check("resolved directory drops the deleted site (4 left)",
        resolved.size == 4 && resolved.none { it.id == "cphoto" })
    val realisticItems = buildCategoryItems(InspirationCategory.REALISTIC, emptyList(), resolved)
    val renamed = realisticItems.find { it.siteId == "uisdc" }
    check("renamed official site shows the override name/url in its category",
        renamed != null && renamed.name == "优设改名" &&
            renamed.homepageUrl == "https://www.uisdc.com/")
    check("deleted site is absent from its category",
        buildCategoryItems(InspirationCategory.PHOTOGRAPHY, emptyList(), resolved)
            .none { it.siteId == "cphoto" })
    val counts = directoryCategoryCounts(resolved)
    check("category counts follow the resolved list (PHOTOGRAPHY 1 after delete)",
        counts[InspirationCategory.PHOTOGRAPHY] == 1 &&
            counts[InspirationCategory.REALISTIC] == 2 &&
            counts[InspirationCategory.LANDSCAPE] == 0)
    check("directoryAllCount counts resolved sites + custom rows",
        directoryAllCount(emptyList(), resolved) == 4)

    // ---- 3. buildFavoriteItems with a resolved directory ----
    favoriteRepo.favoriteOfficial("uisdc")
    val favItems = buildFavoriteItems(favoriteRepo.observeFavorites().first(), resolved)
    check("official favorite shows the overridden name/url",
        favItems.size == 1 && favItems[0].name == "优设改名" &&
            favItems[0].homepageUrl == "https://www.uisdc.com/")

    // ---- 4. buildCustomCategoryItems ----
    val customSite = favoriteRepo.addCustom(
        "我的图站", "https://example.com/", emptySet(), setOf(customId)
    )!!
    val secondCustom = categoryRepo.addCustomCategory("第二个分类")!!
    val favs = favoriteRepo.observeFavorites().first()
    val existingIds = categoryRepo.observeCategories().first().map { it.id }.toSet()
    val inCustom = buildCustomCategoryItems(customId, favs, existingIds)
    check("custom site appears under its custom category",
        inCustom.size == 1 && inCustom[0].siteId == customSite.siteId)
    check("custom site does not appear under another custom category",
        buildCustomCategoryItems(secondCustom.id, favs, existingIds).isEmpty())
    check("official sites never appear under a custom category",
        buildCustomCategoryItems(customId, favs, existingIds).none { !it.isCustom })
    check("deleted category id yields an empty list (sites kept, attribution gone)",
        buildCustomCategoryItems("cat:deleted-category", favs, existingIds).isEmpty())

    // ---- 5. customCategorySiteCount ----
    check("customCategorySiteCount counts referencing sites",
        customCategorySiteCount(favs, customId) == 1 &&
            customCategorySiteCount(favs, secondCustom.id) == 0)

    // ---- 6. reassignCategoryTokens ----
    val raw = favoriteRepo.getFavorite(customSite.siteId)?.categories
    check("stored categories string carries the cat: token",
        raw != null && customId in WebsiteFavoriteRepository
            .decodeCategoryTokens(raw).second)
    val movedToCustom = reassignCategoryTokens(raw, customId, secondCustom.id)
    check("reassign custom -> custom swaps the token",
        movedToCustom != null &&
            WebsiteFavoriteRepository.decodeCategoryTokens(movedToCustom).second ==
            setOf(secondCustom.id))
    val movedToBuiltin = reassignCategoryTokens(raw, customId, "ANIME")
    check("reassign custom -> builtin enum moves to the enum token",
        movedToBuiltin == "ANIME")
    val unassigned = reassignCategoryTokens(raw, customId, null)
    check("reassign to null unassigns (empty -> null)",
        unassigned == null)
    check("reassign with an unknown fromId leaves the raw string untouched",
        reassignCategoryTokens(raw, "cat:no-such-id", "ANIME") == raw)
    check("reassign with an invalid toId degrades to unassign",
        reassignCategoryTokens(raw, customId, "BOGUS") == null)
    val mixed = reassignCategoryTokens("ANIME,$customId", customId, "REALISTIC")
    check("reassign keeps the other enum token",
        mixed == "ANIME,REALISTIC")

    // ---- 7. resolveFavoriteItem decodes cat: tokens ----
    val resolvedCustom = resolveFavoriteItem(
        favoriteRepo.getFavorite(customSite.siteId)!!
    )
    check("custom favorite item carries its cat: tokens",
        resolvedCustom.customCategories == setOf(customId) &&
            resolvedCustom.categories.isEmpty())

    // ---- 8. categoryDisplayName ----
    val allRows = categoryRepo.observeCategories().first()
    check("categoryDisplayName resolves builtin labels and custom names",
        categoryDisplayName("ANIME", allRows) == "二次元插画" &&
            categoryDisplayName(customId, allRows) == "我的分类" &&
            categoryDisplayName("cat:unknown", allRows) == "cat:unknown")

    println("TestDirectoryManagement: $passed passed, $failed failed")
    if (failed > 0) kotlin.system.exitProcess(1)
}
