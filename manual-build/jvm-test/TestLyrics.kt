// JVM test for the stage-7 lyrics time-matching logic — NOT part of
// the app build (lives under manual-build/jvm-test, never shipped).
// Compiles the real data-layer source LyricsParser.kt with kotlinc and
// runs against pure functions only: no device, no Android runtime, no
// Room, no network. What is tested is exactly the logic the lyrics
// view delegates to: matching the current line from the playback
// position against the nearest earlier time tag.
//
// Covered:
//  1. findCurrentLineIndex: empty list -> -1; position before the
//     first line -> -1; exact tag time -> that line; between two tags
//     -> the earlier line; after the last tag -> the last line;
//     untimed (null-time) lines are ignored.
//  2. currentLineAt: the line object for a position, or null before
//     the first line / for untimed-only lyrics.
//  3. ParsedLyrics.timedLines: untimed lines excluded, sorted by time.
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
    val timed = listOf(
        LyricLine(1000L, "one"),
        LyricLine(5000L, "two"),
        LyricLine(9000L, "three")
    )

    // 1. findCurrentLineIndex.
    check("empty list -> -1",
        LyricsParser.findCurrentLineIndex(emptyList(), 5000L) == -1)
    check("position before the first line -> -1",
        LyricsParser.findCurrentLineIndex(timed, 0L) == -1)
    check("position just before the first line -> -1",
        LyricsParser.findCurrentLineIndex(timed, 999L) == -1)
    check("exact tag time matches that line",
        LyricsParser.findCurrentLineIndex(timed, 5000L) == 1)
    check("between two tags matches the earlier line",
        LyricsParser.findCurrentLineIndex(timed, 7000L) == 1)
    check("after the last tag matches the last line",
        LyricsParser.findCurrentLineIndex(timed, 99999L) == 2)
    check("untimed lines are ignored",
        LyricsParser.findCurrentLineIndex(
            listOf(
                LyricLine(null, "intro"),
                LyricLine(1000L, "one"),
                LyricLine(null, "aside"),
                LyricLine(5000L, "two")
            ),
            3000L
        ) == 1)

    // 2. currentLineAt.
    val parsed = ParsedLyrics(timed, emptyMap())
    check("currentLineAt returns the matching line",
        LyricsParser.currentLineAt(parsed, 6000L)?.text == "two")
    check("currentLineAt returns null before the first line",
        LyricsParser.currentLineAt(parsed, 500L) == null)
    check("currentLineAt returns null for untimed-only lyrics",
        LyricsParser.currentLineAt(
            ParsedLyrics(
                listOf(LyricLine(null, "a"), LyricLine(null, "b")),
                emptyMap()
            ),
            5000L
        ) == null)
    check("currentLineAt follows a pause position (no progress, same line)",
        LyricsParser.currentLineAt(parsed, 5000L)?.text == "two" &&
            LyricsParser.currentLineAt(parsed, 5000L)?.text == "two")
    check("currentLineAt jumps to the new line after a seek",
        LyricsParser.currentLineAt(parsed, 1000L)?.text == "one" &&
            LyricsParser.currentLineAt(parsed, 9000L)?.text == "three")

    // 3. timedLines.
    val mixed = LyricsParser.parseLrc(
        "intro line\n[00:05.00]b\n[00:01.00]a\n[ti:x]\n"
    )
    check("timedLines excludes untimed lines and sorts by time",
        mixed.timedLines.map { it.text } == listOf("a", "b"))
    check("hasTimestamps true with timed lines", mixed.hasTimestamps)
    check("offset is applied before matching",
        LyricsParser.currentLineAt(
            LyricsParser.parseLrc("[offset:+1000]\n[00:01.00]shifted\n"),
            1500L
        ) == null &&
            LyricsParser.currentLineAt(
                LyricsParser.parseLrc("[offset:+1000]\n[00:01.00]shifted\n"),
                2000L
            )?.text == "shifted")

    println("TestLyrics: $passed passed, $failed failed")
    if (failed > 0) {
        kotlin.system.exitProcess(1)
    }
}
