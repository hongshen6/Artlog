// JVM test for the stage-5 music data layer — NOT part of the app
// build (lives under manual-build/jvm-test, never shipped). Compiles
// the real data-layer sources LyricsParser.kt, MusicImport.kt,
// MusicStorage.kt, MusicTrack.kt, MusicTrackDao.kt and
// MusicTrackRepository.kt with kotlinc and runs against pure functions
// only: no device, no Android runtime, no Room, no network. What is
// tested is exactly the logic the import flow delegates to: LRC
// parsing (illegal lines / offset / millisecond digits), USLT and
// (c)lyr embedded-lyrics extraction, title fallback, extension
// mapping, in-memory track search, and the repository over a fake DAO.
//
// Covered:
//  1. titleFromMetadata: embedded title wins; blank -> file name
//     without extension; empty name -> "未知曲目".
//  2. extensionFor: MIME mappings for mp3/m4a/aac/flac/wav/ogg;
//     unknown MIME falls back to the file-name extension; neither
//     known -> null (import must fail, not guess).
//  3. filterMusicTracks: empty query returns all; matches title and
//     file name only (artist is NOT searched); English is
//     case-insensitive; Chinese and Japanese match; no match -> empty.
//  4. LRC parse: [mm:ss.xx], [mm:ss], 2- vs 3-digit milliseconds,
//     multiple tags on one line, [offset:+/-ms] shifting times,
//     [ti:/ar:/al:/by:] metadata stored, untimed lines kept as
//     null-time lines, illegal lines dropped without crashing.
//  5. USLT: ID3v2.3 frame, encodings 0 (Latin-1) and 3 (UTF-8);
//     SYLT-only file -> null; no ID3 header -> null; truncated
//     bytes -> null, never a crash.
//  6. (c)lyr: minimal MP4-style atom, UTF-8 payload.
//  7. MusicTrackRepository over a fake in-memory DAO: insert / get /
//     count / update / delete.
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
            list.filter {
                it.title.contains(query, ignoreCase = true) ||
                    (it.artist?.contains(query, ignoreCase = true) == true)
            }
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
    fileName: String,
    importedAt: Long = 0L
) = MusicTrack(
    id = id, title = title, artist = null, album = null, durationMs = null,
    fileName = fileName, filePath = "/tmp/$fileName", fileSizeBytes = 1L,
    mimeType = "audio/mpeg", lyricsText = null, lyricsSource = "NONE",
    lyricsFilePath = null, importedAt = importedAt
)

// ---- ID3v2 test-byte builders ----

private fun synchsafe(value: Int): ByteArray = byteArrayOf(
    ((value shr 21) and 0x7F).toByte(),
    ((value shr 14) and 0x7F).toByte(),
    ((value shr 7) and 0x7F).toByte(),
    (value and 0x7F).toByte()
)

private fun int32BE(value: Int): ByteArray = byteArrayOf(
    ((value shr 24) and 0xFF).toByte(),
    ((value shr 16) and 0xFF).toByte(),
    ((value shr 8) and 0xFF).toByte(),
    (value and 0xFF).toByte()
)

/** One ID3v2 frame body: encoding byte + language + descriptor + text.
 * The descriptor terminator is two zero bytes for UTF-16 encodings
 * (1/2) and one zero byte otherwise. */
private fun usltFrame(encoding: Int, textBytes: ByteArray): ByteArray {
    val terminator = if (encoding == 1 || encoding == 2) byteArrayOf(0, 0)
        else byteArrayOf(0)
    val body = byteArrayOf(encoding.toByte()) +
        "eng".toByteArray(Charsets.US_ASCII) +
        terminator +
        textBytes
    return "USLT".toByteArray(Charsets.US_ASCII) +
        int32BE(body.size) + byteArrayOf(0, 0) + body
}

private fun id3File(vararg frames: ByteArray): ByteArray {
    val payload = frames.fold(byteArrayOf()) { acc, f -> acc + f }
    return "ID3".toByteArray(Charsets.US_ASCII) +
        byteArrayOf(3, 0, 0) + synchsafe(payload.size) + payload
}

/** Minimal MP4-style (c)lyr atom with a UTF-8 payload. */
private fun mp4LyricsFile(text: String): ByteArray {
    val textBytes = text.toByteArray(Charsets.UTF_8)
    val dataAtom = int32BE(16 + textBytes.size) +
        "data".toByteArray(Charsets.US_ASCII) +
        int32BE(1) + int32BE(0) + textBytes
    val lyrAtom = int32BE(8 + dataAtom.size) +
        byteArrayOf(0xA9.toByte(), 'l'.code.toByte(), 'y'.code.toByte(), 'r'.code.toByte()) +
        dataAtom
    return "ftypM4A ".toByteArray(Charsets.US_ASCII) + lyrAtom
}

fun main() {
    // 1. Title fallback.
    check("metadata title wins",
        MusicImport.titleFromMetadata("  真实标题  ", "file.mp3") == "真实标题")
    check("blank metadata title falls back to file name",
        MusicImport.titleFromMetadata("  ", "my song.mp3") == "my song")
    check("null metadata title falls back to file name",
        MusicImport.titleFromMetadata(null, "song.flac") == "song")
    check("empty file name falls back to 未知曲目",
        MusicImport.titleFromMetadata(null, "") == "未知曲目")

    // 2. Extension mapping.
    check("audio/mpeg -> mp3", MusicStorage.extensionFor("audio/mpeg", "x") == "mp3")
    check("audio/mp4 -> m4a", MusicStorage.extensionFor("audio/mp4", "x") == "m4a")
    check("audio/aac -> aac", MusicStorage.extensionFor("audio/aac", "x") == "aac")
    check("audio/flac -> flac", MusicStorage.extensionFor("audio/flac", "x") == "flac")
    check("audio/x-wav -> wav", MusicStorage.extensionFor("audio/x-wav", "x") == "wav")
    check("audio/ogg -> ogg", MusicStorage.extensionFor("audio/ogg", "x") == "ogg")
    check("unknown MIME falls back to file-name extension",
        MusicStorage.extensionFor("application/octet-stream", "song.MP3") == "mp3")
    check("unknown MIME and unknown extension -> null",
        MusicStorage.extensionFor("application/octet-stream", "song.txt") == null)
    check("null MIME and null name -> null",
        MusicStorage.extensionFor(null, null) == null)
    check("isSupportedAudioFile accepts mp3, rejects txt",
        MusicStorage.isSupportedAudioFile("a.mp3") &&
            !MusicStorage.isSupportedAudioFile("a.txt"))
    check("isLyricsFile accepts .lrc only",
        MusicStorage.isLyricsFile("a.lrc") && !MusicStorage.isLyricsFile("a.mp3"))

    // 3. In-memory search (title / file name only).
    val library = listOf(
        track("track:1", "Hello World", "hello.mp3", 3L),
        track("track:2", "夜曲", "yequ.flac", 2L),
        track("track:3", "さくら", "sakura.m4a", 1L)
    )
    check("empty query returns all",
        MusicImport.filterMusicTracks(library, "  ").size == 3)
    check("title match",
        MusicImport.filterMusicTracks(library, "hello").map { it.id } == listOf("track:1"))
    check("English search is case-insensitive",
        MusicImport.filterMusicTracks(library, "HELLO").map { it.id } == listOf("track:1"))
    check("file-name match",
        MusicImport.filterMusicTracks(library, "sakura").map { it.id } == listOf("track:3"))
    check("Chinese match",
        MusicImport.filterMusicTracks(library, "夜曲").map { it.id } == listOf("track:2"))
    check("Japanese match",
        MusicImport.filterMusicTracks(library, "さくら").map { it.id } == listOf("track:3"))
    check("no match returns empty",
        MusicImport.filterMusicTracks(library, "不存在").isEmpty())
    check("artist is not searched",
        MusicImport.filterMusicTracks(
            listOf(track("track:9", "AAA", "aaa.mp3").copy(artist = "夜曲")),
            "夜曲"
        ).isEmpty())

    // 4. LRC parsing.
    val basic = LyricsParser.parseLrc(
        "[ti:Test Song]\n[ar:Someone]\n[00:01.00]first line\n[00:05]second line\nplain line\n"
    )
    check("metadata stored, not a lyric line",
        basic.metadata["ti"] == "Test Song" && basic.metadata["ar"] == "Someone" &&
            basic.lines.size == 3)
    check("[mm:ss.xx] parsed to ms",
        basic.lines[0] == LyricLine(1000L, "first line"))
    check("[mm:ss] without fraction parsed",
        basic.lines[1] == LyricLine(5000L, "second line"))
    check("untimed line kept with null time",
        basic.lines[2] == LyricLine(null, "plain line"))
    check("hasTimestamps true when any timed line", basic.hasTimestamps)

    val digits = LyricsParser.parseLrc("[00:01.5]a\n[00:01.50]b\n[00:01.500]c\n")
    check("1/2/3-digit milliseconds agree",
        digits.timedLines.map { it.timeMs } == listOf(1500L, 1500L, 1500L))

    val multi = LyricsParser.parseLrc("[00:01.00][00:03.00]chorus\n")
    check("multiple tags on one line produce one line per tag",
        multi.lines == listOf(LyricLine(1000L, "chorus"), LyricLine(3000L, "chorus")))

    val offsetUp = LyricsParser.parseLrc("[offset:+500]\n[00:01.00]late\n")
    check("positive offset delays the line",
        offsetUp.lines.singleOrNull()?.timeMs == 1500L)
    val offsetDown = LyricsParser.parseLrc("[offset:-500]\n[00:01.00]early\n")
    check("negative offset advances the line",
        offsetDown.lines.singleOrNull()?.timeMs == 500L)

    val illegal = LyricsParser.parseLrc(
        "[xx:yy]bad tag\n[99:99.99]bad seconds\n[00:02.00]good\n\n[00:03.00]\n"
    )
    check("illegal lines dropped, good line kept, no crash",
        illegal.lines == listOf(LyricLine(2000L, "good")))
    check("timedLines are sorted by time",
        LyricsParser.parseLrc("[00:05.00]b\n[00:01.00]a\n").timedLines
            .map { it.text } == listOf("a", "b"))
    check("untimed-only lyrics have no timestamps",
        !LyricsParser.parseLrc("line one\nline two\n").hasTimestamps)

    // 5. USLT extraction.
    val latin1 = LyricsParser.extractEmbeddedLyrics(
        id3File(usltFrame(0, "Hello lyrics".toByteArray(Charsets.ISO_8859_1)))
    )
    check("USLT encoding 0 (Latin-1) decoded", latin1 == "Hello lyrics")
    val utf8 = LyricsParser.extractEmbeddedLyrics(
        id3File(usltFrame(3, "中文歌词".toByteArray(Charsets.UTF_8)))
    )
    check("USLT encoding 3 (UTF-8) decoded", utf8 == "中文歌词")
    val utf16 = LyricsParser.extractEmbeddedLyrics(
        id3File(
            usltFrame(
                1,
                byteArrayOf(0xFF.toByte(), 0xFE.toByte()) +
                    "AB".toByteArray(Charsets.UTF_16LE)
            )
        )
    )
    check("USLT encoding 1 (UTF-16 with BOM) decoded", utf16 == "AB")
    val syltOnly = "SYLT".toByteArray(Charsets.US_ASCII) +
        int32BE(5) + byteArrayOf(0, 0) + byteArrayOf(0, 0, 0, 0, 0)
    check("SYLT-only file returns null, no error",
        LyricsParser.extractEmbeddedLyrics(id3File(syltOnly)) == null)
    check("no ID3 header returns null",
        LyricsParser.extractEmbeddedLyrics("plain text".toByteArray()) == null)
    check("truncated ID3 returns null, no crash",
        LyricsParser.extractEmbeddedLyrics(byteArrayOf('I'.code.toByte(), 'D'.code.toByte())) == null)
    check("empty bytes return null",
        LyricsParser.extractEmbeddedLyrics(byteArrayOf()) == null)

    // 6. (c)lyr extraction.
    check("(c)lyr UTF-8 payload decoded",
        LyricsParser.extractEmbeddedLyrics(mp4LyricsFile("mp4 lyrics here")) == "mp4 lyrics here")
    check("(c)lyr missing returns null",
        LyricsParser.extractEmbeddedLyrics(
            "ftypM4A no lyrics here".toByteArray(Charsets.US_ASCII)
        ) == null)

    // 7. Repository over a fake DAO.
    runBlocking {
        val repo = MusicTrackRepository(FakeMusicTrackDao())
        val t1 = track("track:1", "One", "one.mp3", 100L)
        val t2 = track("track:2", "Two", "two.mp3", 200L)
        repo.insertTrack(t1)
        repo.insertTrack(t2)
        check("count after two inserts", repo.countTracks() == 2)
        check("get returns the inserted row", repo.getTrack("track:1")?.title == "One")
        check("observe returns newest first",
            repo.observeTracks().first().map { it.id } == listOf("track:2", "track:1"))
        repo.updateTrack(t1.copy(title = "One (edited)"))
        check("update changes the row",
            repo.getTrack("track:1")?.title == "One (edited)")
        repo.deleteTrack("track:2")
        check("delete removes the row",
            repo.countTracks() == 1 && repo.getTrack("track:2") == null)
    }

    println("TestMusic: $passed passed, $failed failed")
    if (failed > 0) {
        kotlin.system.exitProcess(1)
    }
}
