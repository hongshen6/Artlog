// JVM test for the minimal-v5 website favorites (Artlog 2.0 stage 1) —
// NOT part of the app build (lives under manual-build/jvm-test, never
// shipped). Compiles the real data-layer sources with kotlinc and runs
// the Repository against an in-memory fake DAO: no device, no Android
// runtime, no Room, no network. The DAO's SQL itself (CREATE TABLE /
// REPLACE / DELETE) is covered by verify_migration.py (v4 -> v5 data
// test) and by the Room staging open on device (README I-steps);
// what is tested here is the Repository logic + URL validation +
// category encode/decode + read-time directory resolution.
//
// Covered:
//  1. favoriteOfficial: known slug inserts (homepageUrl stored
//     redundantly, isCustom=false); unknown slug refuses, writes
//     nothing; re-favorite is idempotent and keeps the note.
//  2. addCustom: valid http/https accepted (custom:<uuid> id,
//     isCustom=true); javascript: / data: / file: / blank host /
//     whitespace / empty name refused, writes nothing.
//  3. updateCustom: custom rows editable (note + savedAt preserved);
//     official rows and missing rows refuse; bad URL refuses and
//     leaves the row unchanged.
//  4. updateNote / removeFavorite / deleteAllFavorites.
//  5. encode/decode categories round-trip; unknown tokens dropped,
//     ALL/OTHER never encoded.
//  6. displayName / displayUrl / displayCategories: official rows
//     resolve from VisualDirectory, custom rows from the row itself.
//  7. isValidHttpUrl (the gate function the Repository reuses):
//     http/https pass, javascript:/data:/file:/blank host/
//     whitespace/uppercase-scheme refuse.
//  8. observeFavorites order: savedAt DESC.
package com.artlog.app.data

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
    val dao = FakeWebsiteFavoriteDao()
    val repo = WebsiteFavoriteRepository(dao)

    // ---- 1. favoriteOfficial ----
    check("favoriteOfficial known slug succeeds",
        repo.favoriteOfficial("chinachahua"))
    val official = repo.getFavorite("chinachahua")
    check("official row stores slug + homepageUrl redundantly, isCustom=false",
        official != null && official.siteId == "chinachahua" &&
            official.url == "https://chinachahua.net/" &&
            !official.isCustom && official.name == null &&
            official.categories == null && official.note == "")
    check("isFavorite true after favoriteOfficial",
        repo.isFavorite("chinachahua"))
    check("favoriteOfficial unknown slug refuses",
        !repo.favoriteOfficial("no-such-site"))
    check("unknown slug writes nothing",
        repo.getFavorite("no-such-site") == null)
    check("favoriteOfficial re-favorite is idempotent (still 1 row)",
        repo.favoriteOfficial("chinachahua") &&
            repo.observeFavorites().first().size == 1)
    check("re-favorite keeps an existing note",
        repo.updateNote("chinachahua", "常看的插画站") &&
            repo.favoriteOfficial("chinachahua") &&
            repo.getFavorite("chinachahua")?.note == "常看的插画站")

    // ---- 2. addCustom ----
    val custom = repo.addCustom(
        "我的博客", "https://example.com/blog",
        setOf(InspirationCategory.ANIME, InspirationCategory.REALISTIC)
    )
    check("addCustom valid https returns a row",
        custom != null)
    check("custom row has custom:<uuid> id, isCustom=true, fields stored",
        custom != null && custom.siteId.startsWith("custom:") &&
            custom.siteId.length > "custom:".length &&
            custom.isCustom && custom.name == "我的博客" &&
            custom.url == "https://example.com/blog" &&
            custom.categories == "ANIME,REALISTIC" && custom.note == "")
    val httpCustom = repo.addCustom(
        "HTTP 站", "http://example.com/", setOf(InspirationCategory.PHOTOGRAPHY)
    )
    check("addCustom http URL is accepted (not https-only)",
        httpCustom != null)
    val badUrls = listOf(
        "javascript:alert(1)",
        "data:image/png;base64,aaaa",
        "file:///etc/passwd",
        "https://",
        "https://exa mple.com/blog",
        "ftp://example.com/blog",
        "example.com/blog",
        "   "
    )
    var badAccepted = 0
    for (url in badUrls) {
        if (repo.addCustom("坏站", url, emptySet()) != null) badAccepted++
    }
    check("addCustom refuses javascript:/data:/file:/blank host/whitespace/ftp/no-scheme/blank (${badUrls.size} cases)",
        badAccepted == 0)
    check("addCustom empty name refuses",
        repo.addCustom("   ", "https://example.com/", emptySet()) == null)
    check("refused adds write nothing (3 rows total)",
        repo.observeFavorites().first().size == 3)

    // ---- 3. updateCustom ----
    val customId = custom!!.siteId
    val savedAtBefore = custom.savedAt
    check("updateCustom on a custom row succeeds",
        repo.updateCustom(
            customId, "改名后的博客", "https://example.com/new",
            setOf(InspirationCategory.LANDSCAPE)
        ))
    val updated = repo.getFavorite(customId)
    check("updateCustom changes name/url/categories only (note + savedAt kept)",
        updated != null && updated.name == "改名后的博客" &&
            updated.url == "https://example.com/new" &&
            updated.categories == "LANDSCAPE" &&
            updated.note == "" && updated.savedAt == savedAtBefore)
    check("updateCustom on an official row refuses",
        !repo.updateCustom(
            "chinachahua", "改名", "https://example.com/",
            emptySet()
        ))
    check("updateCustom on a missing row refuses",
        !repo.updateCustom(
            "custom:missing", "改名", "https://example.com/", emptySet()
        ))
    check("updateCustom with a bad URL refuses and leaves the row unchanged",
        !repo.updateCustom(
            customId, "改名", "javascript:alert(1)", emptySet()
        ) && repo.getFavorite(customId)?.url == "https://example.com/new")

    // ---- 4. updateNote / removeFavorite / deleteAllFavorites ----
    check("updateNote on a custom row succeeds",
        repo.updateNote(customId, "备注") &&
            repo.getFavorite(customId)?.note == "备注")
    check("updateNote on a missing row refuses",
        !repo.updateNote("custom:missing", "备注"))
    repo.removeFavorite(httpCustom!!.siteId)
    check("removeFavorite deletes the row",
        repo.getFavorite(httpCustom.siteId) == null &&
            repo.observeFavorites().first().size == 2)
    repo.removeFavorite("custom:missing")
    check("removeFavorite on a missing row does not throw",
        repo.observeFavorites().first().size == 2)
    dao.deleteAllFavorites()
    check("deleteAllFavorites empties the table",
        repo.observeFavorites().first().isEmpty())

    // ---- 5. encode/decode categories ----
    check("encodeCategories sorts and joins enum names",
        WebsiteFavoriteRepository.encodeCategories(
            setOf(InspirationCategory.REALISTIC, InspirationCategory.ANIME)
        ) == "ANIME,REALISTIC")
    check("encodeCategories drops ALL/OTHER and returns null when empty",
        WebsiteFavoriteRepository.encodeCategories(
            setOf(InspirationCategory.ALL, InspirationCategory.OTHER)
        ) == null &&
            WebsiteFavoriteRepository.encodeCategories(emptySet()) == null)
    check("decodeCategories round-trips a stored value",
        WebsiteFavoriteRepository.decodeCategories("ANIME,REALISTIC") ==
            setOf(InspirationCategory.ANIME, InspirationCategory.REALISTIC))
    check("decodeCategories drops unknown tokens instead of mapping to OTHER",
        WebsiteFavoriteRepository.decodeCategories("ANIME,BOGUS,REALISTIC") ==
            setOf(InspirationCategory.ANIME, InspirationCategory.REALISTIC))
    check("decodeCategories null/blank/ALL-only gives empty set",
        WebsiteFavoriteRepository.decodeCategories(null).isEmpty() &&
            WebsiteFavoriteRepository.decodeCategories("").isEmpty() &&
            WebsiteFavoriteRepository.decodeCategories("ALL").isEmpty())

    // ---- 6. display resolution ----
    repo.favoriteOfficial("cphoto")
    val cphoto = repo.getFavorite("cphoto")!!
    check("official displayName comes from the directory",
        repo.displayName(cphoto) == "中国摄影在线")
    check("official displayUrl comes from the directory homepage",
        repo.displayUrl(cphoto) == "https://www.cphoto.net/")
    check("official displayCategories come from the directory (cphoto is PHOTOGRAPHY only)",
        repo.displayCategories(cphoto) == setOf(InspirationCategory.PHOTOGRAPHY))
    val custom2 = repo.addCustom(
        "摄影小站", "https://photo.example.com/",
        setOf(InspirationCategory.PHOTOGRAPHY)
    )!!
    check("custom displayName/displayUrl/displayCategories come from the row",
        repo.displayName(custom2) == "摄影小站" &&
            repo.displayUrl(custom2) == "https://photo.example.com/" &&
            repo.displayCategories(custom2) == setOf(InspirationCategory.PHOTOGRAPHY))
    val orphanOfficial = WebsiteFavorite(
        siteId = "gone-from-directory", url = "https://gone.example.com/",
        name = null, isCustom = false, categories = null,
        note = "", savedAt = 1700000000000
    )
    check("official row missing from the directory falls back to slug/row URL",
        repo.displayName(orphanOfficial) == "gone-from-directory" &&
            repo.displayUrl(orphanOfficial) == "https://gone.example.com/" &&
            repo.displayCategories(orphanOfficial).isEmpty())

    // ---- 7. isValidHttpUrl (the gate function the Repository reuses) ----
    check("isValidHttpUrl: http/https pass",
        isValidHttpUrl("http://example.com/") &&
            isValidHttpUrl("https://example.com/a?b=c"))
    check("isValidHttpUrl: javascript:/data:/file: refuse",
        !isValidHttpUrl("javascript:alert(1)") &&
            !isValidHttpUrl("data:image/png;base64,aaaa") &&
            !isValidHttpUrl("file:///etc/passwd"))
    check("isValidHttpUrl: blank host / whitespace / uppercase scheme refuse",
        !isValidHttpUrl("https://") &&
            !isValidHttpUrl("https://exa mple.com/") &&
            !isValidHttpUrl("HTTP://example.com/"))

    // ---- 8. observeFavorites order: savedAt DESC ----
    dao.deleteAllFavorites()
    dao.insertFavorite(
        WebsiteFavorite("chinachahua", "https://chinachahua.net/", null,
            false, null, "", 1700000001000)
    )
    dao.insertFavorite(
        WebsiteFavorite("custom:old", "https://old.example.com/", "旧站",
            true, null, "", 1700000000000)
    )
    dao.insertFavorite(
        WebsiteFavorite("custom:new", "https://new.example.com/", "新站",
            true, null, "", 1700000002000)
    )
    val ordered = repo.observeFavorites().first().map { it.siteId }
    check("observeFavorites returns savedAt DESC order",
        ordered == listOf("custom:new", "chinachahua", "custom:old"))

    println("JVM TEST WEBSITE FAVORITES: $passed passed, $failed failed")
    if (failed > 0) kotlin.system.exitProcess(1)
}
