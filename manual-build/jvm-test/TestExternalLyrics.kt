// JVM test for the P0 acceptance-fix round — NOT part of the app
// build (lives under manual-build/jvm-test, never shipped).
// Compiles the real sources LyricsParser.kt, FloatingLyricsLogic.kt
// and AppearanceSettings.kt with kotlinc and runs against pure
// functions only: no device, no Android runtime, no Room, no network.
//
// Covered:
//  1. FloatingLyricsLogic.shouldShowExternalLyrics: the system overlay
//     needs master + external switch + a track (same triple as the
//     in-app overlay, second condition swapped to external_*).
//  2. FloatingLyricsLogic.externalDisplayLines: same line-selection
//     rules as the in-app FloatingLyricsBody (error / no lyrics /
//     no timestamps / prev+current+next / before-first-line dim).
//  3. AppearanceState defaults for the four new external_* keys
//     (off / 0.5 / 0.78 / 16sp, independent of the floating_* keys).
//  4. GBK real file: manual-build/jvm-test/testdata/sample-gbk.lrc
//     (a real GBK-encoded file, 20 timed lines, [offset:500],
//     mixed Chinese / ASCII / digits) is decoded with decodeLrcBytes
//     and parsed — metadata, offset, timestamps and Chinese text
//     must all come out right. The same file with a UTF-8 BOM
//     prepended must parse identically.
//  5. GBK/UTF-8 misjudgement boundary: the GBK bytes of "一"
//     (D2 BB) are also a valid UTF-8 sequence (U+04BB "һ"), so a
//     file containing only that character decodes via the UTF-8
//     branch — documented current behaviour of decodeLrcBytes
//     (UTF-8 first), a known limitation, NOT a passing claim that
//     the boundary is "fixed". A mixed line ("一你好") breaks UTF-8
//     validity, so the GBK branch is taken and decodes correctly;
//     realistic multi-line GBK files are therefore safe.
package com.artlog.app.data

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

fun main() {
    // 1. shouldShowExternalLyrics.
    check("external shows only with master + external + track",
        FloatingLyricsLogic.shouldShowExternalLyrics(true, true, true))
    check("external hidden when master is off",
        !FloatingLyricsLogic.shouldShowExternalLyrics(false, true, true))
    check("external hidden when external switch is off",
        !FloatingLyricsLogic.shouldShowExternalLyrics(true, false, true))
    check("external hidden with no current track",
        !FloatingLyricsLogic.shouldShowExternalLyrics(true, true, false))

    // 2. externalDisplayLines.
    val parsed = LyricsParser.parseLrc(
        "[00:01.00]第一行\n[00:05.00]第二行\n[00:09.00]第三行\n"
    )
    check("error line is shown dim",
        FloatingLyricsLogic.externalDisplayLines(parsed, 1, "歌词解析失败")
            .let { it.size == 1 && it[0].text == "歌词解析失败" && !it[0].isCurrent })
    check("no lyrics shows placeholder dim",
        FloatingLyricsLogic.externalDisplayLines(null, -1, null)
            .let { it.size == 1 && it[0].text == "当前歌曲暂无歌词" && !it[0].isCurrent })
    val noTime = LyricsParser.parseLrc("整段第一行\n整段第二行\n整段第三行\n")
    check("no timestamps shows first two lines",
        FloatingLyricsLogic.externalDisplayLines(noTime, -1, null)
            .let { it.size == 2 && it[0].text == "整段第一行" && it[1].text == "整段第二行" })
    check("timed middle line shows prev + current + next",
        FloatingLyricsLogic.externalDisplayLines(parsed, 1, null)
            .let {
                it.size == 3 && it[0] == FloatingLyricsLogic.ExternalLyricLine("第一行", false) &&
                    it[1] == FloatingLyricsLogic.ExternalLyricLine("第二行", true) &&
                    it[2] == FloatingLyricsLogic.ExternalLyricLine("第三行", false)
            })
    check("before first line shows first line dim + next",
        FloatingLyricsLogic.externalDisplayLines(parsed, -1, null)
            .let {
                it.size == 2 && it[0] == FloatingLyricsLogic.ExternalLyricLine("第一行", false) &&
                    it[1] == FloatingLyricsLogic.ExternalLyricLine("第二行", false)
            })
    check("last line shows prev + current, no next",
        FloatingLyricsLogic.externalDisplayLines(parsed, 2, null)
            .let {
                it.size == 2 && it[0] == FloatingLyricsLogic.ExternalLyricLine("第二行", false) &&
                    it[1] == FloatingLyricsLogic.ExternalLyricLine("第三行", true)
            })

    // 3. AppearanceState defaults for the new external_* keys.
    val defaults = AppearanceState()
    check("external lyrics defaults off", !defaults.externalLyricsEnabled)
    check("external position lock defaults off", !defaults.externalLyricsLocked)
    check("external position defaults to 0.5 / 0.78",
        defaults.externalLyricsX == 0.5f && defaults.externalLyricsY == 0.78f)
    check("external font size defaults to 16sp",
        defaults.externalLyricsFontSize == 16f)
    check("external defaults do not touch floating defaults",
        defaults.floatingLyricsX == 0.5f && defaults.floatingLyricsY == 0.78f &&
            defaults.floatingLyricsFontSize == 16f && !defaults.floatingLyricsLocked)

    // 4. GBK real file (not synthetic short bytes).
    val gbkFile = java.io.File("manual-build/jvm-test/testdata/sample-gbk.lrc")
    check("gbk sample file exists", gbkFile.isFile)
    val gbkBytes = if (gbkFile.isFile) gbkFile.readBytes() else byteArrayOf()
    check("gbk sample file is non-empty", gbkBytes.isNotEmpty())
    val gbkText = LyricsParser.decodeLrcBytes(gbkBytes)
    val gbkParsed = LyricsParser.parseLrc(gbkText)
    check("gbk file metadata ti/ar survive",
        gbkParsed.metadata["ti"] == "月光小夜曲" && gbkParsed.metadata["ar"] == "佚名")
    check("gbk file offset is 500ms", gbkParsed.offsetMs == 500L)
    check("gbk file has 20 timed lines", gbkParsed.timedLines.size == 20)
    check("gbk first line gets offset applied",
        gbkParsed.timedLines.firstOrNull()?.let {
            it.timeMs == 500L && it.text == "月光洒在窗台上"
        } == true)
    check("gbk digit line decodes with offset",
        gbkParsed.timedLines.firstOrNull { it.text == "123, 456 数字也会唱歌" }
            ?.timeMs == 31_000L)
    check("gbk last line decodes with offset",
        gbkParsed.timedLines.lastOrNull()?.let {
            it.timeMs == 74_500L && it.text == "明天见"
        } == true)
    check("gbk mixed ascii/chinese line intact",
        gbkParsed.timedLines.any { it.text == "La la la, 月光啊" } &&
            gbkParsed.timedLines.any { it.text == "Hello, 月光, Hello, 夜" })
    val gbkWithBom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + gbkBytes
    check("gbk file with utf-8 bom prepended parses identically",
        LyricsParser.parseLrc(LyricsParser.decodeLrcBytes(gbkWithBom)).timedLines.size == 20 &&
            LyricsParser.parseLrc(LyricsParser.decodeLrcBytes(gbkWithBom))
                .metadata["ti"] == "月光小夜曲")

    // 5. GBK/UTF-8 misjudgement boundary (see header comment).
    val boundaryGbk = "[00:01.00]一".toByteArray(charset("GBK"))
    check("gbk bytes of 一 are also valid utf-8 (boundary exists)",
        runCatching { String(boundaryGbk, Charsets.UTF_8) }.isSuccess &&
            !String(boundaryGbk, Charsets.UTF_8).contains('\uFFFD'))
    check("single-char gbk file decodes via utf-8 branch (known limitation)",
        LyricsParser.decodeLrcBytes(boundaryGbk) == "[00:01.00]һ")
    val mixedGbk = "[00:01.00]一你好".toByteArray(charset("GBK"))
    check("mixed gbk line breaks utf-8 validity and decodes via gbk",
        LyricsParser.decodeLrcBytes(mixedGbk) == "[00:01.00]一你好")
    check("mixed gbk line parses to the right timed text",
        LyricsParser.parseLrc(LyricsParser.decodeLrcBytes(mixedGbk))
            .timedLines.firstOrNull()?.text == "一你好")

    println("TestExternalLyrics: $passed passed, $failed failed")
    if (failed > 0) throw AssertionError("$failed checks failed")
}
