// JVM test for the 5.1.2 safety round (image-display gate + image
// loading chain hardening) — NOT part of the app build (lives under
// manual-build/jvm-test, never shipped). Compiles the real data-layer
// sources with kotlinc and runs the pure functions on the JVM: no
// device, no Android runtime, no network. Anything that touches
// Room, the Android Log, Coil's real loader or the network is NOT
// exercised here; those paths are covered by the build dex gate +
// verify_migration.py + the device checklist (README I1–I8).
//
// Mapping to the 12 required test items (user brief §四):
//  1. legal IMAGE displays — §1 gate passes (Commons / Openverse /
//     Met style works).
//  2. UNKNOWN-status IMAGE must not display — §2.
//  3. IMAGE missing license info must not display — §3.
//  4. IMAGE with empty/illegal image URL must not display — §4.
//  5. LINK keeps no image even with a stale imageUrl — §5.
//  6. favorites re-gated on read — §6: displaySafe() on
//     InspirationFavorite (the function the favorites Flow maps
//     through). The Flow mapping itself is Room-backed and is
//     verified by code inspection (Repository L306) + device I6.
//  7. favorite <-> work conversion re-gated on read — §7 (stage 4:
//     the cache table and InspirationCacheItem are deleted, so the
//     old cache-row checks are rewritten to the conversion the read
//     path still uses: InspirationFavorite.toWork().displaySafe()).
//     The DAO call itself needs Room; verified by inspection +
//     device I6.
//  8. legacy / abnormal rows cannot bypass the gate — §8.
//  9. UI downgrades correctly, no stale image — §9: the exact
//     functions the UI branches on (effectiveContentType) and hands
//     to Coil (displayImageUrl). The Compose branching itself is
//     verified by grep (all four AsyncImage sites use them).
// 10. loading ends after a timeout, no infinite spinner — §10: the
//     retry decision is bounded (max 1, HTTP errors never retried)
//     and callTimeout(30s) is in ArtlogApplication (inspection);
//     every AsyncImage site sets isImageLoading=false on both
//     success and error (inspection, all four sites).
// 11. (stage 4: deleted with the Feed — the Openverse breaker and
//     the parallel multi-source fetch no longer exist, so there is
//     no "one source blocks another" path left to test. The
//     single-source directory opens sites in the system browser.)
// 12. existing features unaffected — §12: regression reruns
//     (TestWebsiteFavorites / TestDirectoryLogic)
//     + verify_migration.py (106 checks) + the full build + dex
//     gate. User artworks, tags, journal, statistics and backup
//     code are untouched. (Test51 / Test512 were deleted with the
//     Feed code they covered, stage 4.)
package com.artlog.app.data

import coil.network.HttpException
import java.io.IOException
import java.net.SocketTimeoutException
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response

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

private const val IMG = "https://upload.wikimedia.org/wikipedia/commons/a/ab/Work.jpg"
private const val THUMB =
    "https://thumb.wikimedia.org/wikipedia/commons/thumb/a/ab/Work.jpg/800px-Work.jpg"
private const val PAGE = "https://commons.wikimedia.org/wiki/File:Work.jpg"

private fun imageWork(
    id: String,
    title: String = "作品 $id",
    source: InspirationSource = InspirationSource.COMMONS,
    license: String? = "CC BY 2.0",
    status: InspirationLicenseStatus = InspirationLicenseStatus.ALLOWED,
    contentType: InspirationContentType = InspirationContentType.IMAGE,
    imageUrl: String? = IMG,
    thumbnailUrl: String? = THUMB,
    sourceUrl: String = PAGE,
    author: String? = "画师",
    category: InspirationCategory = InspirationCategory.ANIME
) = InspirationWork(
    id = id,
    source = source,
    sourceId = id,
    title = title,
    author = author,
    sourceUrl = sourceUrl,
    imageUrl = imageUrl,
    thumbnailUrl = thumbnailUrl,
    width = 800,
    height = 600,
    category = category,
    description = null,
    license = license,
    licenseUrl = null,
    dateText = null,
    place = null,
    contentType = contentType,
    licenseStatus = status
)

private fun favorite(
    id: String,
    license: String? = "CC BY 2.0",
    status: InspirationLicenseStatus = InspirationLicenseStatus.ALLOWED,
    contentType: String = "IMAGE",
    source: String = "COMMONS",
    imageUrl: String? = IMG,
    thumbnailUrl: String? = THUMB,
    sourceUrl: String = PAGE,
    note: String = "我的备注",
    savedAt: Long = 1700000000000L
) = InspirationFavorite(
    id = id,
    source = source,
    sourceId = id,
    title = "作品 $id",
    author = "画师",
    sourceUrl = sourceUrl,
    imageUrl = imageUrl,
    thumbnailUrl = thumbnailUrl,
    width = 800,
    height = 600,
    category = "ANIME",
    description = null,
    license = license,
    licenseUrl = null,
    dateText = null,
    place = null,
    contentType = contentType,
    licenseStatus = status.name,
    savedAt = savedAt,
    note = note
)

private fun httpException(code: Int): HttpException {
    val request = Request.Builder().url("https://api.openverse.org/v1/images/").build()
    val response = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message("error")
        .build()
    return HttpException(response)
}

fun main() {
    // ---- 0. URL validity (the gate's foundation) ----
    check("https URL with path+query is valid", isValidHttpUrl("https://example.com/a/b.jpg?x=1&y=2"))
    check("http URL is valid", isValidHttpUrl("http://example.com/img.png"))
    check("null URL is invalid", !isValidHttpUrl(null))
    check("blank URL is invalid", !isValidHttpUrl("   "))
    check("ftp URL is invalid", !isValidHttpUrl("ftp://example.com/a.jpg"))
    check("javascript: URL is invalid", !isValidHttpUrl("javascript:alert(1)"))
    check("data: URL is invalid", !isValidHttpUrl("data:image/png;base64,aaaa"))
    check("relative URL is invalid", !isValidHttpUrl("/wikipedia/commons/a.jpg"))
    check("scheme-only URL is invalid", !isValidHttpUrl("https://"))
    check("URL with a space is invalid", !isValidHttpUrl("https://exa mple.com/a.jpg"))
    check("bare host without scheme is invalid", !isValidHttpUrl("example.com/a.jpg"))

    // ---- 1. legal IMAGE displays (item 1) ----
    val commons = imageWork("commons:1")
    check("Commons CC BY 2.0 IMAGE passes the gate",
        commons.displaySafe() == commons)
    check("Commons work effective type is IMAGE",
        commons.effectiveContentType() == InspirationContentType.IMAGE)
    check("Commons work hands Coil the thumbnail URL",
        displayImageUrl(commons) == THUMB)
    val openverse = imageWork(
        "openverse:2", source = InspirationSource.OPENVERSE,
        license = "BY-SA", sourceUrl = "https://www.flickr.com/photos/x/1",
        imageUrl = "https://live.staticflickr.com/1/2_b.jpg",
        thumbnailUrl = "https://live.staticflickr.com/1/2_m.jpg"
    )
    check("Openverse BY-SA slug IMAGE passes the gate",
        openverse.effectiveContentType() == InspirationContentType.IMAGE)
    check("Openverse slug license is confirming evidence",
        isConfirmedDisplayLicense("BY-SA"))
    // Stage 4: InspirationSource.MET is deleted with the Feed; the
    // CC0 positive case now uses Openverse (same license evidence).
    val cc0Work = imageWork(
        "openverse:3", source = InspirationSource.OPENVERSE, license = "CC0",
        sourceUrl = "https://www.flickr.com/photos/x/3"
    )
    check("Openverse CC0 IMAGE passes the gate",
        cc0Work.effectiveContentType() == InspirationContentType.IMAGE)
    val noThumb = imageWork("commons:4", thumbnailUrl = null)
    check("IMAGE without a thumbnail still displays via imageUrl",
        noThumb.effectiveContentType() == InspirationContentType.IMAGE &&
            displayImageUrl(noThumb) == IMG)

    // ---- 2. UNKNOWN status IMAGE must not display (item 2) ----
    val unknown = imageWork("commons:5", status = InspirationLicenseStatus.UNKNOWN)
    val unknownSafe = unknown.displaySafe()
    check("UNKNOWN IMAGE downgrades to LINK",
        unknownSafe.contentType == InspirationContentType.LINK)
    check("UNKNOWN IMAGE loses its image URLs",
        unknownSafe.imageUrl == null && unknownSafe.thumbnailUrl == null)
    check("UNKNOWN IMAGE loses width/height",
        unknownSafe.width == null && unknownSafe.height == null)
    check("UNKNOWN IMAGE effective type is LINK",
        unknown.effectiveContentType() == InspirationContentType.LINK)
    check("UNKNOWN IMAGE hands Coil nothing",
        displayImageUrl(unknown) == null)
    val notAllowed = imageWork("commons:6", status = InspirationLicenseStatus.NOT_ALLOWED)
    check("NOT_ALLOWED IMAGE downgrades to LINK",
        notAllowed.displaySafe().contentType == InspirationContentType.LINK)
    val linkOnly = imageWork("commons:7", status = InspirationLicenseStatus.LINK_ONLY)
    check("LINK_ONLY IMAGE downgrades to LINK",
        linkOnly.displaySafe().contentType == InspirationContentType.LINK)

    // ---- 3. IMAGE missing license info must not display (item 3) ----
    // The ALLOWED enum alone is a claim; the license string is the
    // evidence. A row that claims ALLOWED with no confirming license
    // is treated as unconfirmed.
    val noLicense = imageWork("commons:8", license = null)
    check("ALLOWED with null license downgrades to LINK",
        noLicense.displaySafe().contentType == InspirationContentType.LINK)
    val blankLicense = imageWork("commons:9", license = "   ")
    check("ALLOWED with blank license downgrades to LINK",
        blankLicense.displaySafe().contentType == InspirationContentType.LINK)
    val reserved = imageWork("commons:10", license = "All rights reserved")
    check("ALLOWED with 'All rights reserved' downgrades to LINK",
        reserved.displaySafe().contentType == InspirationContentType.LINK)
    val freeUse = imageWork("commons:11", license = "Copyrighted free use")
    check("ALLOWED with 'Copyrighted free use' downgrades to LINK",
        freeUse.displaySafe().contentType == InspirationContentType.LINK)
    check("license 'Public domain' is confirming evidence",
        isConfirmedDisplayLicense("Public domain"))
    check("license 'CC0' is confirming evidence",
        isConfirmedDisplayLicense("CC0"))

    // ---- 4. IMAGE with empty/illegal image URL must not display (item 4) ----
    val noImage = imageWork("commons:12", imageUrl = null)
    check("IMAGE with null imageUrl downgrades to LINK",
        noImage.displaySafe().contentType == InspirationContentType.LINK)
    val blankImage = imageWork("commons:13", imageUrl = "  ")
    check("IMAGE with blank imageUrl downgrades to LINK",
        blankImage.displaySafe().contentType == InspirationContentType.LINK)
    val ftpImage = imageWork("commons:14", imageUrl = "ftp://example.com/a.jpg")
    check("IMAGE with ftp imageUrl downgrades to LINK",
        ftpImage.displaySafe().contentType == InspirationContentType.LINK)
    val relativeImage = imageWork("commons:15", imageUrl = "/commons/a.jpg")
    check("IMAGE with relative imageUrl downgrades to LINK",
        relativeImage.displaySafe().contentType == InspirationContentType.LINK)
    val jsImage = imageWork("commons:16", imageUrl = "javascript:alert(1)")
    check("IMAGE with javascript: imageUrl downgrades to LINK",
        jsImage.displaySafe().contentType == InspirationContentType.LINK)
    val badThumb = imageWork("commons:17", thumbnailUrl = "not a url")
    val badThumbSafe = badThumb.displaySafe()
    check("garbage thumbnail is dropped, imageUrl kept, still IMAGE",
        badThumbSafe.contentType == InspirationContentType.IMAGE &&
            badThumbSafe.thumbnailUrl == null && badThumbSafe.imageUrl == IMG)
    check("garbage thumbnail falls back to imageUrl for Coil",
        displayImageUrl(badThumb) == IMG)

    // ---- 5. LINK keeps no image even with a stale imageUrl (item 5) ----
    val staleLink = imageWork(
        "commons:18", contentType = InspirationContentType.LINK,
        status = InspirationLicenseStatus.ALLOWED
    )
    val staleSafe = staleLink.displaySafe()
    check("LINK with a stale imageUrl loses it",
        staleSafe.contentType == InspirationContentType.LINK &&
            staleSafe.imageUrl == null && staleSafe.thumbnailUrl == null)
    check("LINK hands Coil nothing even with a stale imageUrl",
        displayImageUrl(staleLink) == null)
    check("LINK effective type is LINK",
        staleLink.effectiveContentType() == InspirationContentType.LINK)

    // ---- 6. favorites re-gated on read (item 6) ----
    val cleanFav = favorite("commons:1")
    check("clean favorite passes the gate unchanged",
        cleanFav.displaySafe() == cleanFav)
    check("clean favorite effective type is IMAGE",
        cleanFav.effectiveContentType() == InspirationContentType.IMAGE)
    check("clean favorite hands Coil the thumbnail URL",
        displayImageUrl(cleanFav) == THUMB)
    val dirtyFav = favorite("commons:19", status = InspirationLicenseStatus.UNKNOWN)
    val dirtySafe = dirtyFav.displaySafe()
    check("dirty favorite (UNKNOWN) downgrades to LINK",
        dirtySafe.contentType == "LINK" &&
            dirtySafe.imageUrl == null && dirtySafe.thumbnailUrl == null)
    check("downgraded favorite keeps its note and savedAt",
        dirtySafe.note == "我的备注" && dirtySafe.savedAt == 1700000000000L)
    check("downgraded favorite hands Coil nothing",
        displayImageUrl(dirtyFav) == null)
    val staleFav = favorite("commons:20", contentType = "LINK")
    check("LINK favorite with a stale imageUrl loses it on read",
        staleFav.displaySafe().imageUrl == null)
    val favNoLicense = favorite("commons:21", license = null)
    check("favorite claiming ALLOWED with null license downgrades",
        favNoLicense.displaySafe().contentType == "LINK")

    // ---- 7. favorite <-> work conversion re-gated on read (item 7) ----
    // Stage 4: the cache table and InspirationCacheItem are deleted,
    // so the old §7 (cache rows through the gate) is rewritten to the
    // conversion the read path still uses: a dirty favorite
    // downgrades through toWork(), a clean one passes unchanged.
    // Same assertion count as before (2).
    val dirtyFavWork = favorite("commons:22", status = InspirationLicenseStatus.UNKNOWN).toWork()
    check("dirty favorite downgrades to LINK through toWork() + gate",
        dirtyFavWork.displaySafe().contentType == InspirationContentType.LINK &&
            dirtyFavWork.displaySafe().imageUrl == null)
    val cleanFavWork = favorite("commons:23").toWork()
    check("clean favorite passes the gate unchanged through toWork()",
        cleanFavWork.displaySafe().contentType == InspirationContentType.IMAGE)

    // ---- 8. legacy / abnormal rows cannot bypass the gate (item 8) ----
    val unknownSource = imageWork("x:1", source = InspirationSource.UNKNOWN)
    check("UNKNOWN source IMAGE downgrades to LINK",
        unknownSource.displaySafe().contentType == InspirationContentType.LINK)
    val parsedUnknownSource = favorite("x:2", source = "SOME_NEW_SOURCE")
    check("unrecognized stored source string parses to UNKNOWN and downgrades",
        parsedUnknownSource.displaySafe().contentType == "LINK")
    val parsedUnknownType = favorite("x:3", contentType = "WEIRD")
    check("unrecognized stored contentType parses to LINK",
        parsedUnknownType.displaySafe().contentType == "LINK")
    val parsedUnknownStatus = favorite("x:4", contentType = "IMAGE", status = InspirationLicenseStatus.UNKNOWN)
    check("unrecognized stored licenseStatus is UNKNOWN and downgrades",
        parsedUnknownStatus.displaySafe().contentType == "LINK")
    // Stage 4: normalizeWork is deleted with the Feed write path.
    // The read path never drops a row — it downgrades it to LINK —
    // so these two verdicts are now asserted against displaySafe.
    val badPage = imageWork("commons:24", sourceUrl = "not a url")
    check("read path downgrades a work with an unusable sourceUrl to LINK",
        badPage.displaySafe().contentType == InspirationContentType.LINK &&
            badPage.displaySafe().imageUrl == null)
    val emptyPage = imageWork("commons:25", sourceUrl = "   ")
    check("read path downgrades a work with a blank sourceUrl to LINK",
        emptyPage.displaySafe().contentType == InspirationContentType.LINK)
    val slugLower = imageWork("openverse:26", source = InspirationSource.OPENVERSE, license = "by-sa")
    check("lowercase 'by-sa' slug still counts as confirming evidence",
        slugLower.effectiveContentType() == InspirationContentType.IMAGE)
    val gated = imageWork("commons:27", status = InspirationLicenseStatus.UNKNOWN)
    check("the gate is idempotent",
        gated.displaySafe().displaySafe() == gated.displaySafe())

    // ---- gate convergence (stage 4: normalizeWork deleted with the
    // Feed write path; the same verdicts are now asserted directly
    // against gateImageDisplay / displaySafe — the functions the
    // remaining read path uses. Same assertion count as before (4).) ----
    val w = imageWork("commons:28").displaySafe()
    check("gate keeps a legal IMAGE as IMAGE",
        w.contentType == InspirationContentType.IMAGE && w.imageUrl == IMG)
    val w2 = imageWork("commons:29", license = null).displaySafe()
    check("gate downgrades ALLOWED-without-evidence to LINK",
        w2.contentType == InspirationContentType.LINK && w2.imageUrl == null)
    val w3gated = gateImageDisplay(
        InspirationContentType.IMAGE, InspirationLicenseStatus.ALLOWED,
        InspirationSource.COMMONS, "CC BY 2.0", PAGE, IMG, "garbage"
    )
    check("gate drops a garbage thumbnail, keeps imageUrl",
        w3gated.contentType == InspirationContentType.IMAGE &&
            w3gated.thumbnailUrl == null && w3gated.imageUrl == IMG)
    val w4 = imageWork("commons:31", status = InspirationLicenseStatus.UNKNOWN).displaySafe()
    check("gate downgrade keeps the original licenseStatus for audit",
        w4.contentType == InspirationContentType.LINK &&
            w4.licenseStatus == InspirationLicenseStatus.UNKNOWN)

    // ---- 9. UI downgrade: no stale image can reach Coil (item 9) ----
    // The UI branches on effectiveContentType and hands Coil only
    // displayImageUrl. For every failing shape above, both agree:
    // LINK type AND null URL — there is no combination where the
    // badge says LINK but an image URL still leaks through.
    val failingShapes = listOf(
        imageWork("f:1", status = InspirationLicenseStatus.UNKNOWN),
        imageWork("f:2", license = null),
        imageWork("f:3", imageUrl = null),
        imageWork("f:4", imageUrl = "ftp://example.com/a.jpg"),
        imageWork("f:5", source = InspirationSource.UNKNOWN),
        imageWork("f:6", contentType = InspirationContentType.LINK),
        imageWork("f:7", sourceUrl = "not a url")
    )
    check("every failing shape is LINK with a null Coil URL",
        failingShapes.all {
            it.effectiveContentType() == InspirationContentType.LINK &&
                displayImageUrl(it) == null
        })
    check("every passing shape is IMAGE with a non-null Coil URL",
        listOf(imageWork("p:1"), openverse, cc0Work).all {
            it.effectiveContentType() == InspirationContentType.IMAGE &&
                displayImageUrl(it) != null
        })

    // ---- 10. bounded loading: retry decision (item 10) ----
    // Coil 2.7's HttpException extends RuntimeException, not
    // IOException (verified from the coil-base jar with javap), so
    // HTTP errors never qualified for retry even before this round.
    // The rule is now explicit and tested here.
    val http429 = httpException(429)
    check("HttpException is NOT an IOException (javap-verified)",
        http429 !is IOException)
    check("HTTP 429 is never retried",
        !shouldRetryImageLoad(http429, 0, 1))
    check("HTTP 403 is never retried",
        !shouldRetryImageLoad(httpException(403), 0, 1))
    check("HTTP 404 is never retried",
        !shouldRetryImageLoad(httpException(404), 0, 1))
    check("HTTP 500 is never retried",
        !shouldRetryImageLoad(httpException(500), 0, 1))
    check("a timeout IS retried once",
        shouldRetryImageLoad(SocketTimeoutException("read timed out"), 0, 1))
    check("the one retry is not repeated",
        !shouldRetryImageLoad(SocketTimeoutException("read timed out"), 1, 1))
    check("a generic network IOException IS retried once",
        shouldRetryImageLoad(IOException("connection reset"), 0, 1))
    check("a plain RuntimeException is never retried",
        !shouldRetryImageLoad(RuntimeException("boom"), 0, 1))

    println("JVM TEST GATE: $passed passed, $failed failed")
    if (failed > 0) kotlin.system.exitProcess(1)
}
