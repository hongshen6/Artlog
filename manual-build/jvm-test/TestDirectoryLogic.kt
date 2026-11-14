// JVM test for the stage-2/3 directory UI logic (Artlog 2.0) —
// NOT part of the app build (lives under manual-build/jvm-test, never
// shipped). Compiles the real data-layer sources + the pure
// ui/directory/DirectoryUiLogic.kt with kotlinc and runs against an
// in-memory fake DAO: no device, no Android runtime, no Room, no
// network. The ViewModel itself (AndroidX lifecycle) is not compiled
// here; what is tested is exactly the logic the ViewModel delegates
// to: category counts, same-slug consistency, domain extraction,
// custom-input validation, category merge (official + custom) and
// favorite-list resolution/order.
//
// Covered:
//  1. directoryCategoryCounts: ANIME 1 / REALISTIC 2 / PHOTOGRAPHY 2 /
//     ARCHITECTURE 1 / LANDSCAPE 0 (honest zero, no B-level filler).
//  2. Same slug, same record: chinachahua appears in ANIME and
//     REALISTIC with identical id/name/description/homepageUrl, and
//     its favorite state is consistent across both categories.
//  3. domainOf: scheme + path stripped, host only; unparsable input
//     returned as-is.
//  4. validateCustomInput: empty/blank name -> EMPTY_NAME;
//     javascript:/data:/file:/blank-host/whitespace URLs ->
//     INVALID_URL; http/https pass; no network involved.
//  5. buildCategoryItems: custom site appears under its chosen
//     category, not under others; uncategorized custom appears only
//     under ALL; official rows show isFavorite=false before favoriting.
//  6. resolveFavoriteItem: official rows resolve from VisualDirectory,
//     custom rows from the row itself; note preserved.
//  7. buildFavoriteItems: savedAt DESC order.
//  8. directoryAllCount: 5 official + custom count.
package com.artlog.app.data

import com.artlog.app.ui.directory.CustomInputError
import com.artlog.app.ui.directory.buildCategoryItems
import com.artlog.app.ui.directory.buildFavoriteItems
import com.artlog.app.ui.directory.directoryAllCount
import com.artlog.app.ui.directory.directoryCategoryCounts
import com.artlog.app.ui.directory.domainOf
import com.artlog.app.ui.directory.resolveFavoriteItem
import com.artlog.app.ui.directory.validateCustomInput
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
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

fun main() = runBlocking {
    // 1. Category counts (official directory only).
    val counts = directoryCategoryCounts()
    check("count ANIME == 1", counts[InspirationCategory.ANIME] == 1)
    check("count REALISTIC == 2", counts[InspirationCategory.REALISTIC] == 2)
    check("count PHOTOGRAPHY == 2", counts[InspirationCategory.PHOTOGRAPHY] == 2)
    check("count ARCHITECTURE == 1", counts[InspirationCategory.ARCHITECTURE] == 1)
    check("count LANDSCAPE == 0 (honest zero)", counts[InspirationCategory.LANDSCAPE] == 0)
    check("counts cover exactly the 5 visible categories", counts.size == 5)
    check("no B/C/D site leaks into counts (total == 6 incl. dup slug)",
        counts.values.sum() == 6)

    // 2. Same slug, same record across categories.
    val animeItems = buildCategoryItems(InspirationCategory.ANIME, emptyList())
    val realisticItems = buildCategoryItems(InspirationCategory.REALISTIC, emptyList())
    val inAnime = animeItems.find { it.siteId == "chinachahua" }
    val inRealistic = realisticItems.find { it.siteId == "chinachahua" }
    check("chinachahua in ANIME", inAnime != null)
    check("chinachahua in REALISTIC", inRealistic != null)
    check(
        "same record across categories (name/desc/url/type identical)",
        inAnime != null && inRealistic != null &&
            inAnime.name == inRealistic.name &&
            inAnime.description == inRealistic.description &&
            inAnime.homepageUrl == inRealistic.homepageUrl &&
            inAnime.type == inRealistic.type
    )
    check("chinachahua not favorite before favoriting",
        inAnime?.isFavorite == false && inRealistic?.isFavorite == false)

    // Favorite state consistent across categories (via the real Repository).
    val repo = WebsiteFavoriteRepository(FakeWebsiteFavoriteDao())
    check("favoriteOfficial(chinachahua) succeeds", repo.favoriteOfficial("chinachahua"))
    val favs = repo.observeFavorites()
    // Read the flow's current value through a fresh collect of one item.
    val current = favs.first()
    val animeAfter = buildCategoryItems(InspirationCategory.ANIME, current)
    val realisticAfter = buildCategoryItems(InspirationCategory.REALISTIC, current)
    check(
        "favorite state consistent across categories",
        animeAfter.find { it.siteId == "chinachahua" }?.isFavorite == true &&
            realisticAfter.find { it.siteId == "chinachahua" }?.isFavorite == true
    )
    repo.removeFavorite("chinachahua")
    val afterRemove = repo.observeFavorites().first()
    check(
        "unfavorite consistent across categories",
        buildCategoryItems(InspirationCategory.ANIME, afterRemove)
            .find { it.siteId == "chinachahua" }?.isFavorite == false
    )

    // 3. domainOf.
    check("domainOf strips scheme+path", domainOf("https://www.uisdc.com/") == "www.uisdc.com")
    check("domainOf keeps bare host", domainOf("https://chinachahua.net/") == "chinachahua.net")
    check("domainOf keeps http host", domainOf("http://www.ccud.org.cn/") == "www.ccud.org.cn")
    check("domainOf strips deep path",
        domainOf("https://www.archdaily.cn/projects/x") == "www.archdaily.cn")
    check("domainOf returns input when unparsable", domainOf("not a url") == "not a url")

    // 4. validateCustomInput.
    check("empty name -> EMPTY_NAME",
        validateCustomInput("", "https://example.com/") == CustomInputError.EMPTY_NAME)
    check("blank name -> EMPTY_NAME",
        validateCustomInput("   ", "https://example.com/") == CustomInputError.EMPTY_NAME)
    check("javascript: url -> INVALID_URL",
        validateCustomInput("x", "javascript:alert(1)") == CustomInputError.INVALID_URL)
    check("data: url -> INVALID_URL",
        validateCustomInput("x", "data:text/html,hi") == CustomInputError.INVALID_URL)
    check("file: url -> INVALID_URL",
        validateCustomInput("x", "file:///etc/passwd") == CustomInputError.INVALID_URL)
    check("blank host -> INVALID_URL",
        validateCustomInput("x", "https://") == CustomInputError.INVALID_URL)
    check("url with whitespace -> INVALID_URL",
        validateCustomInput("x", "https://exa mple.com/") == CustomInputError.INVALID_URL)
    check("https passes", validateCustomInput("x", "https://example.com/") == null)
    check("http passes (no forced https upgrade)",
        validateCustomInput("x", "http://example.com/") == null)

    // 5. buildCategoryItems with custom sites.
    val repo2 = WebsiteFavoriteRepository(FakeWebsiteFavoriteDao())
    val customRealistic = repo2.addCustom(
        "我的图站", "https://example.com/", setOf(InspirationCategory.REALISTIC)
    )
    val customUncategorized = repo2.addCustom("未分类站", "https://plain.example/", emptySet())
    check("addCustom realistic ok", customRealistic != null)
    check("addCustom uncategorized ok", customUncategorized != null)
    val allFavs = repo2.observeFavorites().first()
    val realisticWithCustom = buildCategoryItems(InspirationCategory.REALISTIC, allFavs)
    val animeWithCustom = buildCategoryItems(InspirationCategory.ANIME, allFavs)
    val allWithCustom = buildCategoryItems(InspirationCategory.ALL, allFavs)
    check(
        "custom appears under its chosen category",
        realisticWithCustom.any { it.siteId == customRealistic!!.siteId }
    )
    check(
        "custom does NOT appear under other categories",
        animeWithCustom.none { it.siteId == customRealistic!!.siteId }
    )
    check(
        "uncategorized custom NOT in a single category",
        realisticWithCustom.none { it.siteId == customUncategorized!!.siteId } &&
            animeWithCustom.none { it.siteId == customUncategorized!!.siteId }
    )
    check(
        "uncategorized custom appears under ALL",
        allWithCustom.any { it.siteId == customUncategorized!!.siteId }
    )
    check(
        "custom is favorite by construction (add == favorite)",
        realisticWithCustom.find { it.siteId == customRealistic!!.siteId }?.isFavorite == true
    )
    check(
        "custom card is marked custom with row name/url",
        realisticWithCustom.find { it.siteId == customRealistic!!.siteId }?.let {
            it.isCustom && it.name == "我的图站" && it.homepageUrl == "https://example.com/"
        } == true
    )

    // 6. resolveFavoriteItem.
    val officialFav = WebsiteFavorite(
        siteId = "uisdc", url = "https://www.uisdc.com/", name = null,
        isCustom = false, categories = null, note = "常看", savedAt = 1000L
    )
    val resolvedOfficial = resolveFavoriteItem(officialFav)
    check("official resolves name from directory", resolvedOfficial.name == "优设")
    check("official resolves url from directory",
        resolvedOfficial.homepageUrl == "https://www.uisdc.com/")
    check("official note preserved", resolvedOfficial.note == "常看")
    check("official categories from directory",
        resolvedOfficial.categories == setOf(InspirationCategory.REALISTIC))
    val resolvedCustom = resolveFavoriteItem(customRealistic!!)
    check("custom resolves name from row", resolvedCustom.name == "我的图站")
    check("custom type label", resolvedCustom.type == "自定义网站")

    // 7. buildFavoriteItems order (savedAt DESC).
    val rows = listOf(
        WebsiteFavorite("a", "https://a.example/", "A", true, null, "", 3000L),
        WebsiteFavorite("b", "https://b.example/", "B", true, null, "", 1000L),
        WebsiteFavorite("c", "https://c.example/", "C", true, null, "", 2000L),
    )
    val ordered = buildFavoriteItems(rows).map { it.siteId }
    check("favorites ordered savedAt DESC", ordered == listOf("a", "c", "b"))

    // 8. directoryAllCount.
    check("all count = 5 official + 2 custom",
        directoryAllCount(allFavs) == 7)
    check("all count = 5 with no customs", directoryAllCount(emptyList()) == 5)

    println("TestDirectoryLogic: $passed passed, $failed failed")
    if (failed > 0) {
        kotlin.system.exitProcess(1)
    }
}
