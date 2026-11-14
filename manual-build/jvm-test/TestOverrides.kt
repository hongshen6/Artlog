// JVM test for website overrides + resolveDirectory (next iteration,
// database v6) — NOT part of the app build (lives under
// manual-build/jvm-test, never shipped). Compiles the real data-layer
// sources + the pure ui/directory/DirectoryUiLogic.kt with kotlinc and
// runs the Repository against an in-memory fake DAO: no device, no
// Android runtime, no Room, no network. The DAO's SQL itself (CREATE
// TABLE / REPLACE / DELETE) is covered by verify_migration.py (v5 -> v6
// data test) and by the Room staging open on device; what is tested
// here is the Repository logic + the read-time merge.
//
// Covered:
//  1. upsertOverride: valid rows insert; a row with an invalid
//     urlOverride refuses and writes nothing; REPLACE on the same
//     siteId updates the row.
//  2. setDeleted: marks a site deleted, keeps its other override
//     fields; un-deleting restores; a site with no row is not deleted.
//  3. deleteOverride: removes the row (restore default); missing rows
//     do not throw.
//  4. resolveDirectory: no overrides -> the 5 static sites unchanged;
//     isDeleted=true hides the site; name/description/url/categories
//     overrides replace the matching fields; a blank nameOverride /
//     blank descriptionOverride / invalid urlOverride /
//     empty-after-decode categoriesOverride fall back to the static
//     values; an override pointing at an unknown slug is ignored,
//     never crashes.
//  5. DIRECTORY_VERSION is 1 (human-readable change record).
package com.artlog.app.data

import com.artlog.app.ui.directory.resolveDirectory
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

fun main() = runBlocking {
    val dao = FakeWebsiteOverrideDao()
    val repo = WebsiteOverrideRepository(dao)

    // ---- 1. upsertOverride ----
    check("upsertOverride valid row succeeds",
        repo.upsertOverride(
            WebsiteOverride("uisdc", false, "优设改名", null, null, null, 1000L)
        ))
    val row = repo.getOverride("uisdc")
    check("override row stored verbatim",
        row != null && row.siteId == "uisdc" && !row.isDeleted &&
            row.nameOverride == "优设改名" && row.descriptionOverride == null &&
            row.urlOverride == null &&
            row.categoriesOverride == null && row.updatedAt == 1000L)
    check("upsertOverride with a descriptionOverride stores it verbatim",
        repo.upsertOverride(
            WebsiteOverride("archdaily-cn", false, null, "改过的简介", null, null, 1002L)
        ) && repo.getOverride("archdaily-cn")?.descriptionOverride == "改过的简介")
    check("upsertOverride with an invalid urlOverride refuses",
        !repo.upsertOverride(
            WebsiteOverride("cphoto", false, null, null, "javascript:alert(1)", null, 1001L)
        ))
    check("refused upsert writes nothing",
        repo.getOverride("cphoto") == null)
    check("upsertOverride REPLACE on the same siteId updates the row",
        repo.upsertOverride(
            WebsiteOverride("uisdc", true, "优设改名", null, null, null, 2000L)
        ) && repo.getOverride("uisdc")?.isDeleted == true &&
            repo.getOverride("uisdc")?.updatedAt == 2000L &&
            repo.observeOverrides().first().size == 2)

    // ---- 2. setDeleted ----
    check("isDeleted true after upsert with isDeleted=true",
        repo.isDeleted("uisdc"))
    repo.setDeleted("uisdc", false)
    check("setDeleted(false) restores and keeps other fields",
        !repo.isDeleted("uisdc") &&
            repo.getOverride("uisdc")?.nameOverride == "优设改名")
    check("setDeleted keeps the descriptionOverride of the same row",
        repo.getOverride("archdaily-cn")?.descriptionOverride == "改过的简介" &&
            run {
                repo.setDeleted("archdaily-cn", true)
                repo.getOverride("archdaily-cn")?.descriptionOverride == "改过的简介"
            })
    repo.setDeleted("archdaily-cn", false)
    repo.setDeleted("pop-photo", true)
    check("setDeleted on a site with no row creates a deleted row",
        repo.isDeleted("pop-photo") &&
            repo.getOverride("pop-photo")?.nameOverride == null)
    check("isDeleted false for a site with no row",
        !repo.isDeleted("chinachahua"))

    // ---- 3. deleteOverride ----
    repo.deleteOverride("pop-photo")
    check("deleteOverride removes the row (restore default)",
        repo.getOverride("pop-photo") == null && !repo.isDeleted("pop-photo"))
    repo.deleteOverride("no-such-site")
    check("deleteOverride on a missing row does not throw",
        repo.observeOverrides().first().size == 2)

    // ---- 4. resolveDirectory ----
    val plain = resolveDirectory(emptyList())
    check("no overrides -> 5 static sites unchanged",
        plain.size == 5 && plain.map { it.id } ==
            listOf("chinachahua", "uisdc", "cphoto", "pop-photo", "archdaily-cn"))
    check("no overrides -> static names/urls/categories kept",
        plain.find { it.id == "chinachahua" }?.categories ==
            setOf(InspirationCategory.ANIME, InspirationCategory.REALISTIC))

    val merged = resolveDirectory(
        listOf(
            WebsiteOverride("uisdc", false, "优设改名", "改过的简介", "https://www.uisdc.com/", "PHOTOGRAPHY", 3000L),
            WebsiteOverride("cphoto", true, null, null, null, null, 3001L),
            WebsiteOverride("ghost-site", true, "幽灵站", null, null, null, 3002L),
            WebsiteOverride("pop-photo", false, "   ", "   ", "javascript:alert(1)", "BOGUS", 3003L),
        )
    )
    check("deleted site hidden, unknown slug ignored (4 sites left)",
        merged.size == 4 && merged.none { it.id == "cphoto" } &&
            merged.none { it.id == "ghost-site" })
    val uisdc = merged.find { it.id == "uisdc" }
    check("name/description/url/categories overrides replace the static fields",
        uisdc != null && uisdc.name == "优设改名" &&
            uisdc.description == "改过的简介" &&
            uisdc.homepageUrl == "https://www.uisdc.com/" &&
            uisdc.categories == setOf(InspirationCategory.PHOTOGRAPHY))
    val popPhoto = merged.find { it.id == "pop-photo" }
    check("blank name / blank description / invalid url / undecodable categories fall back to static values",
        popPhoto != null && popPhoto.name == "大众摄影" &&
            popPhoto.description == "《大众摄影》杂志官网，发布影赛征稿、作品、展讯与俱乐部活动。" &&
            popPhoto.homepageUrl == "https://www.pop-photo.com.cn/" &&
            popPhoto.categories == setOf(InspirationCategory.PHOTOGRAPHY))
    check("untouched sites keep static values",
        merged.find { it.id == "chinachahua" }?.name == "中国插画艺术网" &&
            merged.find { it.id == "archdaily-cn" }?.homepageUrl == "https://www.archdaily.cn/")

    // ---- 5. DIRECTORY_VERSION ----
    check("DIRECTORY_VERSION is 1", VisualDirectory.DIRECTORY_VERSION == 1)

    println("TestOverrides: $passed passed, $failed failed")
    if (failed > 0) kotlin.system.exitProcess(1)
}
