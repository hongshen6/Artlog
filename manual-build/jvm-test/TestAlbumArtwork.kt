// JVM test for the album-artwork round (database v7) — NOT part of the
// app build (lives under manual-build/jvm-test, never shipped). Compiles
// the real data-layer sources AlbumCover.kt, MusicStorage.kt,
// MusicTrack.kt, MusicTrackDao.kt and MusicTrackRepository.kt with
// kotlinc and runs against pure functions only: no device, no Android
// runtime, no Room, no network.
//
// Covered:
//  1. extensionForImageBytes: JPEG / PNG magic bytes -> "jpg" / "png";
//     null / empty / too short / GIF / WebP / random bytes -> null.
//  2. coverFileName: `<trackId 去冒号>.<ext>`; unrecognised bytes -> null.
//  3. needsBackfill / selectForBackfill: only tracks with a null or
//     blank coverPath are selected (already-filled tracks are left alone).
//  4. artworkPathFor (the mediaItemFor artwork decision): a cover path
//     is returned, null / blank is not (no artwork is faked). NOTE:
//     PlaybackService.mediaItemFor itself needs Media3 and cannot run
//     on the JVM — its wiring (setArtworkUri only when artworkPathFor
//     is non-null) is verified by code inspection, not by this suite.
//  5. coverFileToDelete: only the track's OWN cover file inside the
//     covers dir is returned; null / blank / foreign-dir / another
//     track's file -> null (never delete another track's cover).
//  6. MusicTrackRepository over a fake in-memory DAO: coverPath is
//     stored, read back, updated (filled -> null) and deleted with
//     the row; the MusicTrack default is null (old rows / old call
//     sites keep compiling).
//  7. shouldDegradeMissingCover / countDegradedCovers (交付复核后
//     由 fail-fast 改为降级)：备份时有 coverPath 但文件缺失 →
//     跳过并计入降级，备份继续；无封面不计降级；文件存在不降级。
//
// NOT covered here (needs Android / the device, see the implementation
// report): MediaMetadataRetriever.getEmbeddedPicture() per-format
// behaviour, MusicStorage.saveCover / deleteTrackFiles file IO,
// CoverBackfill.runOnce, BackupManager.createBackup's copy loop
// itself (its per-track decision is the pure helper in 7 above),
// and the Compose cover UIs.
package com.artlog.app.data

import java.io.File
import java.nio.file.Files
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
    } else {
        failed++
        println("FAIL: $name")
    }
}

private class FakeMusicTrackDao : MusicTrackDao {
    private val rows = LinkedHashMap<String, MusicTrack>()
    private val flow = MutableStateFlow<List<MusicTrack>>(emptyList())

    private fun emit() {
        flow.value = rows.values.sortedByDescending { it.importedAt }
    }

    override suspend fun getTrack(id: String): MusicTrack? = rows[id]
    override fun observeTracks(): Flow<List<MusicTrack>> = flow
    override fun searchTracks(query: String): Flow<List<MusicTrack>> =
        flow.map { list ->
            list.filter { it.title.contains(query, ignoreCase = true) }
        }
    override suspend fun countTracks(): Int = rows.size
    override suspend fun insertTrack(track: MusicTrack) {
        rows[track.id] = track
        emit()
    }
    override suspend fun updateTrack(track: MusicTrack) {
        rows[track.id] = track
        emit()
    }
    override suspend fun deleteTrack(id: String) {
        rows.remove(id)
        emit()
    }
    override suspend fun deleteAllTracks() {
        rows.clear()
        emit()
    }
}

private fun track(
    id: String,
    title: String,
    coverPath: String? = null,
    importedAt: Long = 0L
) = MusicTrack(
    id = id, title = title, artist = null, album = null, durationMs = null,
    fileName = "track.mp3", filePath = "/tmp/track.mp3", fileSizeBytes = 1L,
    mimeType = "audio/mpeg", lyricsText = null, lyricsSource = "NONE",
    lyricsFilePath = null, importedAt = importedAt, coverPath = coverPath
)

private fun jpegBytes(): ByteArray =
    byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) +
        "JFIF".toByteArray(Charsets.US_ASCII) + ByteArray(10)

private fun pngBytes(): ByteArray =
    byteArrayOf(
        0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte(),
        0x0D.toByte(), 0x0A.toByte(), 0x1A.toByte(), 0x0A.toByte()
    ) + ByteArray(10)

fun main() {
    // 1. Image magic-byte detection.
    check("JPEG magic -> jpg",
        AlbumCoverLogic.extensionForImageBytes(jpegBytes()) == "jpg")
    check("PNG magic -> png",
        AlbumCoverLogic.extensionForImageBytes(pngBytes()) == "png")
    check("null bytes -> null",
        AlbumCoverLogic.extensionForImageBytes(null) == null)
    check("empty bytes -> null",
        AlbumCoverLogic.extensionForImageBytes(ByteArray(0)) == null)
    check("2-byte JPEG prefix is too short -> null",
        AlbumCoverLogic.extensionForImageBytes(
            byteArrayOf(0xFF.toByte(), 0xD8.toByte())) == null)
    check("GIF header -> null (unsupported)",
        AlbumCoverLogic.extensionForImageBytes(
            "GIF89a".toByteArray(Charsets.US_ASCII) + ByteArray(10)) == null)
    check("WebP RIFF header -> null (unsupported)",
        AlbumCoverLogic.extensionForImageBytes(
            "RIFF".toByteArray(Charsets.US_ASCII) + ByteArray(4) +
                "WEBP".toByteArray(Charsets.US_ASCII)) == null)
    check("random bytes -> null",
        AlbumCoverLogic.extensionForImageBytes(
            byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)) == null)
    check("PNG prefix with one wrong byte -> null",
        AlbumCoverLogic.extensionForImageBytes(
            byteArrayOf(
                0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte(),
                0x0D.toByte(), 0x0A.toByte(), 0x1A.toByte(), 0x0B.toByte()
            ) + ByteArray(4)) == null)

    // 2. Cover file naming.
    check("JPEG cover file name uses track id without colon",
        AlbumCoverLogic.coverFileName("track:abc-123", jpegBytes()) ==
            "track-abc-123.jpg")
    check("PNG cover file name uses .png",
        AlbumCoverLogic.coverFileName("track:abc-123", pngBytes()) ==
            "track-abc-123.png")
    check("unrecognised bytes -> no file name",
        AlbumCoverLogic.coverFileName("track:abc-123", ByteArray(10)) == null)
    check("null bytes -> no file name",
        AlbumCoverLogic.coverFileName("track:abc-123", null) == null)
    check("MusicStorage fileNameFor strips the colon",
        MusicStorage.fileNameFor("track:abc-123") == "track-abc-123")

    // 3. Backfill selection: only coverPath-empty tracks.
    val noCover = track("track:1", "One")
    val blankCover = track("track:2", "Two", coverPath = "   ")
    val hasCover = track(
        "track:3", "Three",
        coverPath = "/covers/track-3.jpg"
    )
    check("null coverPath needs backfill",
        AlbumCoverLogic.needsBackfill(noCover))
    check("blank coverPath needs backfill",
        AlbumCoverLogic.needsBackfill(blankCover))
    check("filled coverPath does not need backfill",
        !AlbumCoverLogic.needsBackfill(hasCover))
    check("selectForBackfill picks only the empty ones",
        AlbumCoverLogic.selectForBackfill(listOf(noCover, hasCover, blankCover))
            .map { it.id } == listOf("track:1", "track:2"))
    check("selectForBackfill on all-filled list is empty",
        AlbumCoverLogic.selectForBackfill(listOf(hasCover)).isEmpty())

    // 4. mediaItemFor artwork decision (pure part).
    check("artworkPathFor returns the cover path",
        AlbumCoverLogic.artworkPathFor(hasCover) == "/covers/track-3.jpg")
    check("artworkPathFor returns null without a cover",
        AlbumCoverLogic.artworkPathFor(noCover) == null)
    check("artworkPathFor returns null for a blank cover path",
        AlbumCoverLogic.artworkPathFor(blankCover) == null)

    // 5. Delete guard: only the track's own cover file, in the covers dir.
    val coversDir = Files.createTempDirectory("artlog-covers-test").toFile()
    try {
        val ownJpg = File(coversDir, "track-1.jpg")
        val ownPng = File(coversDir, "track-1.png")
        val otherTrack = File(coversDir, "track-9.jpg")
        val elsewhere = File(
            Files.createTempDirectory("artlog-elsewhere-test").toFile(),
            "track-1.jpg"
        )
        val ownTrack = track("track:1", "One", coverPath = ownJpg.absolutePath)
        check("own .jpg cover in covers dir is deletable",
            AlbumCoverLogic.coverFileToDelete(ownTrack, coversDir) == ownJpg)
        check("own .png cover in covers dir is deletable",
            AlbumCoverLogic.coverFileToDelete(
                ownTrack.copy(coverPath = ownPng.absolutePath), coversDir
            ) == ownPng)
        check("another track's cover file is NOT deletable",
            AlbumCoverLogic.coverFileToDelete(
                ownTrack.copy(coverPath = otherTrack.absolutePath), coversDir
            ) == null)
        check("own file name outside the covers dir is NOT deletable",
            AlbumCoverLogic.coverFileToDelete(
                ownTrack.copy(coverPath = elsewhere.absolutePath), coversDir
            ) == null)
        check("null coverPath deletes nothing",
            AlbumCoverLogic.coverFileToDelete(noCover, coversDir) == null)
        check("blank coverPath deletes nothing",
            AlbumCoverLogic.coverFileToDelete(blankCover, coversDir) == null)
    } finally {
        coversDir.deleteRecursively()
    }

    // 6. Repository round-trip with coverPath (fake DAO).
    runBlocking {
        val repo = MusicTrackRepository(FakeMusicTrackDao())
        val t1 = track("track:1", "One", importedAt = 100L)
        repo.insertTrack(t1)
        check("MusicTrack default coverPath is null",
            repo.getTrack("track:1")?.coverPath == null)
        repo.updateTrack(
            t1.copy(coverPath = "/covers/track-1.jpg")
        )
        check("coverPath survives insert + update",
            repo.getTrack("track:1")?.coverPath == "/covers/track-1.jpg")
        repo.updateTrack(
            t1.copy(coverPath = null)
        )
        check("coverPath can be cleared back to null",
            repo.getTrack("track:1")?.coverPath == null)
        repo.insertTrack(
            track("track:2", "Two", coverPath = "/covers/track-2.png", importedAt = 200L)
        )
        check("observe returns the stored coverPath",
            repo.observeTracks().first()
                .first { it.id == "track:2" }.coverPath == "/covers/track-2.png")
        repo.deleteTrack("track:2")
        check("delete removes the row (cover file deletion itself is "
            + "MusicStorage.deleteTrackFiles, needs Context)",
            repo.countTracks() == 1 && repo.getTrack("track:2") == null)
    }

    // 7. Backup degrade decision (交付复核：备份时封面缺失降级，不失败).
    check("missing cover file degrades instead of failing the backup",
        AlbumCoverLogic.shouldDegradeMissingCover("/covers/track-1.jpg", false))
    check("present cover file is copied, not degraded",
        !AlbumCoverLogic.shouldDegradeMissingCover("/covers/track-1.jpg", true))
    check("no coverPath degrades nothing",
        !AlbumCoverLogic.shouldDegradeMissingCover(null, false))
    check("blank coverPath degrades nothing",
        !AlbumCoverLogic.shouldDegradeMissingCover("   ", false))
    check("countDegradedCovers counts only referenced-but-missing covers",
        AlbumCoverLogic.countDegradedCovers(
            listOf(noCover, hasCover, blankCover),
            { path -> path == "/covers/track-3.jpg" }
        ) == 0)
    check("countDegradedCovers counts the missing one",
        AlbumCoverLogic.countDegradedCovers(
            listOf(noCover, hasCover, blankCover),
            { _ -> false }
        ) == 1)
    check("countDegradedCovers on an empty library is 0",
        AlbumCoverLogic.countDegradedCovers(emptyList()) { _ -> false } == 0)

    println("TestAlbumArtwork: $passed passed, $failed failed")
    if (failed > 0) {
        kotlin.system.exitProcess(1)
    }
}
