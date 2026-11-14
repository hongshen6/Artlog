package com.artlog.app.data

/**
 * 5.1.2 safety round — the SINGLE image-display gate (P0).
 *
 * Problem found by the 2.0 audit (report §1.2 P1): authorization was
 * checked only on the WRITE path. The old write-path normalizer ran
 * after fetching and before storing a favorite, but the favorites
 * Flow handed rows straight to the UI without any re-check, and the
 * old feed screen branched only on `contentType` — never on
 * `licenseStatus`. The whole display side rested on one implicit
 * promise: "an IMAGE row in the database is always ALLOWED". A
 * migrated row, a stale cache row, a hand-edited database or a
 * future source bug could break that promise and an unconfirmed
 * image would be shown.
 * (Minimal v5 dropped the feed cache table, so the old cachedFeed
 * read path no longer exists; the favorites read path is still
 * gated here. Stage 4 removed the Feed entirely — the write-path
 * normalizer is gone with it, and the gate now guards the favorites
 * read path and its tests.)
 *
 * This file is the one place that decides whether an image may be
 * shown. Every display path — favorites list, favorite detail —
 * goes through it, and the UI never re-implements the rule:
 *
 * - Repository read paths call [InspirationWork.displaySafe] /
 *   [InspirationFavorite.displaySafe] (downgrade, never drop).
 * - The UI branches on [effectiveContentType] and hands Coil only
 *   [displayImageUrl] — both derived from this gate — so even a row
 *   that somehow reaches the UI ungated cannot produce an image.
 *
 * An IMAGE work may be displayed only when ALL of these hold:
 * 1. contentType == IMAGE.
 * 2. licenseStatus == ALLOWED — the only authorizing state.
 *    UNKNOWN, NOT_ALLOWED, LINK_ONLY, a missing value or an
 *    unrecognized string all forbid display. (Stored strings parse
 *    through safeValueOf, which maps anything unknown to UNKNOWN.)
 * 3. The record's OWN license string confirms display permission
 *    ([isConfirmedDisplayLicense]): the ALLOWED enum is a claim,
 *    the license string is the evidence. A row that claims ALLOWED
 *    but carries no confirming license string is treated as
 *    unconfirmed. All legitimate ALLOWED rows satisfy this — every
 *    live source sets ALLOWED only on a confirming license, and
 *    MIGRATION_3_4 used the same predicate in SQL — so this check
 *    cannot hide a legitimate image; it only catches dirty rows.
 * 4. source != UNKNOWN — an image from a source the app cannot even
 *    name is never shown.
 * 5. sourceUrl is a valid http(s) URL (the landing page must exist
 *    and be openable; it is also the attribution anchor).
 * 6. imageUrl is a valid http(s) URL and non-empty — the detail
 *    page loads this URL. thumbnailUrl, when present, must also be
 *    a valid http(s) URL; an invalid thumbnail is dropped (the UI
 *    falls back to imageUrl) instead of being handed to Coil.
 *
 * What the gate deliberately does NOT require (P1 field audit):
 * - licenseUrl: nullable in the data — Openverse `license_url` and
 *   Commons `LicenseUrl` can both be absent on legitimate rows, so
 *   requiring it would hide legitimate CC images.
 * - author: nullable — many legitimate Commons works have no Artist
 *   in extmetadata, so requiring it would hide legitimate images.
 * - attribution: there is NO dedicated field (Openverse's
 *   attribution string is stored in `description`; Commons rows
 *   have `description == null`). It cannot be required without a
 *   schema change, which is a v5 matter, not this round.
 * The missing pieces (a dedicated attribution field and a
 * permission-evidence field) are recorded in the README as v5
 * candidates; this round does not add database fields.
 *
 * Downgrade, never drop, on the READ path: a work that fails the
 * gate is returned as LINK with its image fields cleared (and
 * width/height cleared, so a LINK row carries no image data at all).
 * The row itself stays visible as text + original link — a favorite
 * must never vanish from the list because its image lost
 * authorization. The old write path additionally dropped rows whose
 * sourceUrl was not a usable http(s) link; that path was removed
 * with the Feed in stage 4, and the read path never drops rows.
 */

/** A URL is usable for display / opening only when it is a non-blank
 * http(s) URL with a host. Relative URLs, javascript: / data: /
 * file: schemes and whitespace are rejected. Pure function. */
fun isValidHttpUrl(url: String?): Boolean {
    val trimmed = url?.trim() ?: return false
    if (trimmed.isEmpty()) return false
    if (trimmed.any { it.isWhitespace() }) return false
    if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
        return false
    }
    val host = runCatching { java.net.URL(trimmed).host }.getOrNull() ?: return false
    return host.isNotEmpty()
}

/**
 * Per-record license evidence: true only when the record's OWN license
 * field names a CC license or a public-domain mark. A platform name
 * never counts as evidence.
 *
 * Why the slug list: Openverse stores CC license SLUGS in this field
 * ("by", "by-sa", "by-nc", "by-nd", "by-nc-sa", "by-nc-nd", "cc0",
 * "pdm", … — uppercased by the old Openverse source), not "CC BY …" strings.
 * A check for "%CC%" alone would miss every "BY-*" Openverse record
 * and wrongly downgrade it, even though the slug IS the per-record CC
 * evidence. Commons stores "CC BY-SA 4.0" / "Public domain" style
 * names, Met/AIC store "CC0" — all covered by the substring checks.
 * The same predicate is mirrored in SQL in MIGRATION_3_4 (which
 * cannot call this function); keep the two in sync.
 *
 * Moved here from Inspiration.kt (stage 1 prep): the gate is the one
 * place that decides whether an image may be shown, and this predicate
 * is rule 3 of that gate — it belongs with the gate, not with the
 * feed data model. Logic unchanged, verbatim.
 */
fun isConfirmedDisplayLicense(license: String?): Boolean {
    val value = license?.trim()?.uppercase() ?: return false
    if (value.isEmpty()) return false
    if ("CC" in value || "PUBLIC DOMAIN" in value) return true
    return value in setOf(
        "BY", "BY-SA", "BY-NC", "BY-ND", "BY-NC-SA", "BY-NC-ND",
        "CC0", "PDM", "PUBLIC DOMAIN MARK", "SAMPLING+", "NC-SAMPLING+"
    )
}

/**
 * The gate's verdict for one work's image fields. LINK means "do not
 * show an image for this work" — the image URLs are always null in
 * that case, so no caller can accidentally load one.
 */
internal data class GatedImage(
    val contentType: InspirationContentType,
    val imageUrl: String?,
    val thumbnailUrl: String?
)

/** The single gate. Pure function — no Room, no network, no Android. */
internal fun gateImageDisplay(
    contentType: InspirationContentType,
    licenseStatus: InspirationLicenseStatus,
    source: InspirationSource,
    license: String?,
    sourceUrl: String,
    imageUrl: String?,
    thumbnailUrl: String?
): GatedImage {
    val link = GatedImage(InspirationContentType.LINK, null, null)
    if (contentType != InspirationContentType.IMAGE) return link
    if (source == InspirationSource.UNKNOWN) return link
    if (licenseStatus != InspirationLicenseStatus.ALLOWED) return link
    // The ALLOWED claim must be backed by the record's own license
    // string (see the file header, rule 3).
    if (!isConfirmedDisplayLicense(license)) return link
    if (!isValidHttpUrl(sourceUrl)) return link
    val cleanImage = imageUrl?.trim()?.takeIf { isValidHttpUrl(it) } ?: return link
    val cleanThumb = thumbnailUrl?.trim()?.takeIf { isValidHttpUrl(it) }
    return GatedImage(InspirationContentType.IMAGE, cleanImage, cleanThumb)
}

/**
 * Read-time gate for a work: returns the work as it may be shown —
 * unchanged when the gate passes, downgraded to LINK (image fields
 * cleared) when it does not. Never returns null and never drops the
 * row: a downgraded work stays visible as text + original link.
 */
fun InspirationWork.displaySafe(): InspirationWork {
    val gated = gateImageDisplay(
        contentType, licenseStatus, source, license, sourceUrl, imageUrl, thumbnailUrl
    )
    return if (gated.contentType == InspirationContentType.IMAGE) {
        copy(imageUrl = gated.imageUrl, thumbnailUrl = gated.thumbnailUrl)
    } else {
        copy(
            contentType = InspirationContentType.LINK,
            imageUrl = null,
            thumbnailUrl = null,
            width = null,
            height = null
        )
    }
}

/** Read-time gate for a favorite: same rule as [InspirationWork.displaySafe].
 * The favorite's own fields (note, savedAt) are never touched — only
 * the image-carrying fields can be downgraded. */
fun InspirationFavorite.displaySafe(): InspirationFavorite {
    val gated = gateImageDisplay(
        InspirationContentType.safeValueOf(contentType),
        InspirationLicenseStatus.safeValueOf(licenseStatus),
        InspirationSource.safeValueOf(source),
        license, sourceUrl, imageUrl, thumbnailUrl
    )
    return if (gated.contentType == InspirationContentType.IMAGE) {
        copy(imageUrl = gated.imageUrl, thumbnailUrl = gated.thumbnailUrl)
    } else {
        copy(
            contentType = InspirationContentType.LINK.name,
            imageUrl = null,
            thumbnailUrl = null,
            width = null,
            height = null
        )
    }
}

/**
 * The content type the UI must branch on: IMAGE only when the gate
 * passes, LINK otherwise. UI code branches on this — never on the
 * stored [InspirationWork.contentType] alone — so a row that reaches
 * the UI without going through the repository gate still cannot
 * produce an image.
 */
fun InspirationWork.effectiveContentType(): InspirationContentType =
    gateImageDisplay(
        contentType, licenseStatus, source, license, sourceUrl, imageUrl, thumbnailUrl
    ).contentType

/** Same as [InspirationWork.effectiveContentType], for favorites. */
fun InspirationFavorite.effectiveContentType(): InspirationContentType =
    gateImageDisplay(
        InspirationContentType.safeValueOf(contentType),
        InspirationLicenseStatus.safeValueOf(licenseStatus),
        InspirationSource.safeValueOf(source),
        license, sourceUrl, imageUrl, thumbnailUrl
    ).contentType

/**
 * The only URL the UI may hand to Coil for a work: the list
 * thumbnail when usable, else the detail image URL — and only when
 * the gate passes. Returns null for every non-displayable work, so
 * passing its result to an image loader can never show an image
 * that failed the gate.
 */
fun displayImageUrl(work: InspirationWork): String? {
    val gated = gateImageDisplay(
        work.contentType, work.licenseStatus, work.source, work.license,
        work.sourceUrl, work.imageUrl, work.thumbnailUrl
    )
    if (gated.contentType != InspirationContentType.IMAGE) return null
    return gated.thumbnailUrl ?: gated.imageUrl
}

/** Same as [displayImageUrl] for a favorite. */
fun displayImageUrl(favorite: InspirationFavorite): String? {
    val gated = gateImageDisplay(
        InspirationContentType.safeValueOf(favorite.contentType),
        InspirationLicenseStatus.safeValueOf(favorite.licenseStatus),
        InspirationSource.safeValueOf(favorite.source),
        favorite.license, favorite.sourceUrl, favorite.imageUrl, favorite.thumbnailUrl
    )
    if (gated.contentType != InspirationContentType.IMAGE) return null
    return gated.thumbnailUrl ?: gated.imageUrl
}
