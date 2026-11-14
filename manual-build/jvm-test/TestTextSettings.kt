// JVM test for the P2 stage-5 text settings — NOT part of the app
// build (lives under manual-build/jvm-test, never shipped). Compiles
// the real data-layer source AppearanceSettings.kt with kotlinc and
// runs against pure functions only: no device, no Android runtime, no
// SharedPreferences, no Room, no network. What is tested is exactly
// the logic the Settings UI, ArtlogTheme and LyricsView delegate to:
// the three new keys' defaults, ranges and clamps, and the adjacent
// lyric-line size ratio.
//
// Covered:
//  1. Defaults: text_size_scale 1.0 (range 0.85–1.3), text_color
//     absent (null = theme default), lyrics_font_size 22sp
//     (range 10–32; 最终验收修改轮：默认 16sp → 18sp；v26：默认
//     18sp → 22sp).
//  2. clampTextSizeScale: below min -> 0.85, above max -> 1.3,
//     inside unchanged, NaN -> 1.0 (never a crash, never a NaN
//     fontScale reaching the theme).
//  3. clampLyricsFontSize: below min -> 10, above max -> 32, inside
//     unchanged, NaN -> 22.
//  4. Adjacent lyric line ratio 0.875 (19.25/22): 22sp -> 19.25sp,
//     32sp -> 28sp, 10sp -> 8.75sp.
//  5. Default text colour constant is the theme primary text colour
//     (ArtlogTextPrimary, 0xFFF0F0EF).
//
// NOT covered here (needs Android / device):
//  - SharedPreferences read/write/reset of the three keys (covered
//    by the backup mirror script's source-parsing checks +
//    device acceptance).
//  - The theme density scaling and the colour override in Compose
//    (covered by the build + device acceptance: no JVM Compose).
//  - The backup round-trip itself (backup-stage5-settings-validation.py).
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
    // 1. Defaults.
    check("default text size scale is 1.0",
        AppearanceLogic.DEFAULT_TEXT_SIZE_SCALE == 1.0f)
    check("text size scale range is 0.85..1.3",
        AppearanceLogic.TEXT_SIZE_SCALE_MIN == 0.85f &&
            AppearanceLogic.TEXT_SIZE_SCALE_MAX == 1.3f)
    check("default lyrics font size is 22sp",
        AppearanceLogic.DEFAULT_LYRICS_FONT_SIZE == 22f)
    check("lyrics font size range is 10..32sp",
        AppearanceLogic.LYRICS_FONT_SIZE_MIN == 10f &&
            AppearanceLogic.LYRICS_FONT_SIZE_MAX == 32f)
    check("AppearanceState default text size scale is 1.0",
        AppearanceState().textSizeScale == 1.0f)
    check("AppearanceState default text colour is null (theme default)",
        AppearanceState().textColor == null)
    check("AppearanceState default lyrics font size is 22sp",
        AppearanceState().lyricsFontSize == 22f)

    // 2. Text size scale clamping.
    check("scale below min clamps to 0.85",
        AppearanceLogic.clampTextSizeScale(0.5f) == 0.85f)
    check("scale above max clamps to 1.3",
        AppearanceLogic.clampTextSizeScale(2.0f) == 1.3f)
    check("scale inside range unchanged",
        AppearanceLogic.clampTextSizeScale(1.15f) == 1.15f)
    check("scale at min boundary unchanged",
        AppearanceLogic.clampTextSizeScale(0.85f) == 0.85f)
    check("scale at max boundary unchanged",
        AppearanceLogic.clampTextSizeScale(1.3f) == 1.3f)
    check("scale NaN falls back to 1.0",
        AppearanceLogic.clampTextSizeScale(Float.NaN) == 1.0f)

    // 3. Lyrics font size clamping.
    check("lyrics size below min clamps to 10",
        AppearanceLogic.clampLyricsFontSize(5f) == 10f)
    check("lyrics size above max clamps to 32",
        AppearanceLogic.clampLyricsFontSize(40f) == 32f)
    check("lyrics size inside range unchanged",
        AppearanceLogic.clampLyricsFontSize(20f) == 20f)
    check("lyrics size at min boundary unchanged",
        AppearanceLogic.clampLyricsFontSize(10f) == 10f)
    check("lyrics size at max boundary unchanged",
        AppearanceLogic.clampLyricsFontSize(32f) == 32f)
    check("lyrics size NaN falls back to 22",
        AppearanceLogic.clampLyricsFontSize(Float.NaN) == 22f)

    // 4. Adjacent lyric line ratio.
    check("adjacent ratio is 0.875",
        AppearanceLogic.LYRICS_ADJACENT_SIZE_RATIO == 0.875f)
    check("22sp current line -> 19.25sp adjacent line",
        22f * AppearanceLogic.LYRICS_ADJACENT_SIZE_RATIO == 19.25f)
    check("32sp current line -> 28sp adjacent line",
        32f * AppearanceLogic.LYRICS_ADJACENT_SIZE_RATIO == 28f)
    check("10sp current line -> 8.75sp adjacent line",
        10f * AppearanceLogic.LYRICS_ADJACENT_SIZE_RATIO == 8.75f)

    // 5. Default text colour constant.
    check("default text colour is ArtlogTextPrimary",
        AppearanceLogic.DEFAULT_TEXT_COLOR_ARGB == 0xFFF0F0EFL)

    println("TestTextSettings: $passed passed, $failed failed")
    if (failed > 0) {
        kotlin.system.exitProcess(1)
    }
}
