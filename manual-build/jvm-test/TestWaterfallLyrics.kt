// JVM test for the P2 stage-4 waterfall-lyrics + in-app floating
// lyrics pure logic — NOT part of the app build (lives under
// manual-build/jvm-test, never shipped). Compiles the real
// data-layer sources LyricsWaterfallLogic.kt and
// FloatingLyricsLogic.kt with kotlinc and runs against pure
// functions only: no device, no Android runtime, no
// SharedPreferences, no Room, no network. What is tested is
// exactly the logic the rendering layers delegate to:
//
//  1. LyricsWaterfallLogic.rowAlpha: distance -> alpha gradient
//     (current line opaque, farther lines weaker, floor 0.30).
//  2. LyricsWaterfallLogic.isLargeLyricJump: adjacent-line advance
//     uses smooth scrolling, large jumps (seek / track change)
//     use direct positioning.
//  3. FloatingLyricsLogic.movePosition: drag pixel delta ->
//     normalized position, clamped to [0, 1], degenerate container
//     sizes leave the position unchanged.
//  4. FloatingLyricsLogic.stepFontSize: A- / A+ step 2sp, clamped
//     to 10–32sp.
//
// The Compose rendering itself (animateScrollToItem,
// animate*AsState, pointerInput drag, touch-through) cannot run on
// the JVM and is covered by the device acceptance steps in
// p2-waterfall-lyrics-report.md / p2-music-ui-report.md instead.
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
    // 1. Row alpha by distance from the current line.
    check("distance 0 (current line) is opaque",
        LyricsWaterfallLogic.rowAlpha(0) == 1f)
    check("distance 1 -> 0.75",
        LyricsWaterfallLogic.rowAlpha(1) == 0.75f)
    check("distance 2 -> 0.55",
        LyricsWaterfallLogic.rowAlpha(2) == 0.55f)
    check("distance 3 -> 0.40",
        LyricsWaterfallLogic.rowAlpha(3) == 0.4f)
    check("distance 4 -> 0.30 floor",
        LyricsWaterfallLogic.rowAlpha(4) == 0.3f)
    check("distance 10 stays at the 0.30 floor",
        LyricsWaterfallLogic.rowAlpha(10) == 0.3f)
    check("negative distance uses absolute value",
        LyricsWaterfallLogic.rowAlpha(-1) == 0.75f &&
            LyricsWaterfallLogic.rowAlpha(-4) == 0.3f)
    check("alpha decreases monotonically with distance",
        LyricsWaterfallLogic.rowAlpha(0) > LyricsWaterfallLogic.rowAlpha(1) &&
            LyricsWaterfallLogic.rowAlpha(1) > LyricsWaterfallLogic.rowAlpha(2) &&
            LyricsWaterfallLogic.rowAlpha(2) > LyricsWaterfallLogic.rowAlpha(3) &&
            LyricsWaterfallLogic.rowAlpha(3) > LyricsWaterfallLogic.rowAlpha(4) &&
            LyricsWaterfallLogic.rowAlpha(4) == LyricsWaterfallLogic.rowAlpha(10))
    check("large-jump threshold is 3 lines",
        LyricsWaterfallLogic.LARGE_JUMP_THRESHOLD == 3)

    // 2. Large-jump decision (smooth scroll vs direct positioning).
    check("unknown previous index (first positioning) is a large jump",
        LyricsWaterfallLogic.isLargeLyricJump(-1, 0))
    check("same index is not a jump",
        !LyricsWaterfallLogic.isLargeLyricJump(5, 5))
    check("adjacent advance (+1) is not a large jump",
        !LyricsWaterfallLogic.isLargeLyricJump(5, 6))
    check("diff exactly at threshold (3) is not a large jump",
        !LyricsWaterfallLogic.isLargeLyricJump(5, 8))
    check("diff above threshold (4) is a large jump",
        LyricsWaterfallLogic.isLargeLyricJump(5, 9))
    check("large backward jump is a large jump",
        LyricsWaterfallLogic.isLargeLyricJump(40, 2))
    check("small backward jump is not a large jump",
        !LyricsWaterfallLogic.isLargeLyricJump(5, 3))
    check("custom threshold is honoured",
        LyricsWaterfallLogic.isLargeLyricJump(5, 7, threshold = 1) &&
            !LyricsWaterfallLogic.isLargeLyricJump(5, 6, threshold = 1))

    // 3. Drag position conversion (normalized 0–1, px deltas).
    check("no movement keeps the position",
        FloatingLyricsLogic.movePosition(0.5f, 0.78f, 0f, 0f, 1080f, 2400f) ==
            (0.5f to 0.78f))
    check("dx of a quarter width moves x by +0.25",
        FloatingLyricsLogic.movePosition(0.5f, 0.78f, 250f, 0f, 1000f, 2000f) ==
            (0.75f to 0.78f))
    check("negative dy moves y upward",
        FloatingLyricsLogic.movePosition(0.5f, 0.78f, 0f, -100f, 1000f, 2000f)
            .let { it.first == 0.5f && kotlin.math.abs(it.second - 0.73f) < 0.001f })
    check("drag past the right edge clamps x to 1",
        FloatingLyricsLogic.movePosition(0.9f, 0.78f, 500f, 0f, 1000f, 2000f).first == 1f)
    check("drag past the left edge clamps x to 0",
        FloatingLyricsLogic.movePosition(0.1f, 0.78f, -500f, 0f, 1000f, 2000f).first == 0f)
    check("drag past the bottom edge clamps y to 1",
        FloatingLyricsLogic.movePosition(0.5f, 0.9f, 0f, 500f, 1000f, 2000f).second == 1f)
    check("drag past the top edge clamps y to 0",
        FloatingLyricsLogic.movePosition(0.5f, 0.1f, 0f, -500f, 1000f, 2000f).second == 0f)
    check("zero container size leaves the position unchanged (clamped)",
        FloatingLyricsLogic.movePosition(0.5f, 0.78f, 100f, 100f, 0f, 2000f) ==
            (0.5f to 0.78f) &&
            FloatingLyricsLogic.movePosition(0.5f, 0.78f, 100f, 100f, 1000f, 0f) ==
            (0.5f to 0.78f))
    check("out-of-range input is clamped even without movement",
        FloatingLyricsLogic.movePosition(1.5f, -0.5f, 0f, 0f, 1000f, 2000f) ==
            (1f to 0f))

    // 4. Font-size step (A− / A＋, 2sp steps, clamped 10–32sp).
    check("font size constants are 10 / 32 / step 2 / default 16",
        FloatingLyricsLogic.FONT_SIZE_MIN_SP == 10f &&
            FloatingLyricsLogic.FONT_SIZE_MAX_SP == 32f &&
            FloatingLyricsLogic.FONT_SIZE_STEP_SP == 2f &&
            FloatingLyricsLogic.DEFAULT_FONT_SIZE_SP == 16f)
    check("step up from 16 is 18",
        FloatingLyricsLogic.stepFontSize(16f, 2f) == 18f)
    check("step down from 16 is 14",
        FloatingLyricsLogic.stepFontSize(16f, -2f) == 14f)
    check("step up clamps at 32",
        FloatingLyricsLogic.stepFontSize(31f, 2f) == 32f &&
            FloatingLyricsLogic.stepFontSize(32f, 2f) == 32f)
    check("step down clamps at 10",
        FloatingLyricsLogic.stepFontSize(11f, -2f) == 10f &&
            FloatingLyricsLogic.stepFontSize(10f, -2f) == 10f)

    println("TestWaterfallLyrics: $passed passed, $failed failed")
    if (failed > 0) {
        kotlin.system.exitProcess(1)
    }
}
