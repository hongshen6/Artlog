// JVM test for the stage-8 appearance logic — NOT part of the app
// build (lives under manual-build/jvm-test, never shipped). Compiles
// the real data-layer source AppearanceSettings.kt with kotlinc and
// runs against pure functions only: no device, no Android runtime, no
// SharedPreferences, no Room, no network. What is tested is exactly
// the logic the Settings UI and the MainActivity background layer
// delegate to: defaults, preset table, mode parsing, scrim clamping,
// colour hex parsing/formatting, and the resolve-to-solid fallback
// when a background image file is missing.
//
// Covered:
//  1. Defaults: scrim 0.45 in the 0–0.6 range; default preset "ink"
//     is the existing dark background colour (0xFF111214).
//  2. Presets: 8 entries, unique ids, all low-saturation dark colours
//     (opaque, every RGB channel <= 0x40).
//  3. parseMode: null / unknown -> COLOR_PRESET, never a crash.
//  4. clampScrimAlpha: below 0 -> 0, above 0.6 -> 0.6, inside unchanged.
//  5. parseColorHex: "#RRGGBB" / "#AARRGGBB" / without "#" accepted;
//     wrong length / non-hex / empty -> null.
//  6. formatColorHex round-trips parseColorHex.
//  7. resolveBackground: IMAGE + existing file -> Image; IMAGE + null
//     path / missing file -> Solid (silent fallback); COLOR_CUSTOM
//     -> Solid(custom colour); unknown preset -> default preset colour.
package com.artlog.app.data

import java.io.File

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
    check("default scrim is 0.45", AppearanceLogic.DEFAULT_SCRIM_ALPHA == 0.45f)
    check("scrim range is 0..0.6",
        AppearanceLogic.SCRIM_ALPHA_MIN == 0f && AppearanceLogic.SCRIM_ALPHA_MAX == 0.6f)
    check("default preset id is ink", AppearanceLogic.DEFAULT_PRESET_ID == "ink")
    check("default preset colour is the existing dark background",
        AppearanceLogic.presetColor("ink") == 0xFF111214L)
    check("default custom colour is the existing dark background",
        AppearanceLogic.DEFAULT_CUSTOM_COLOR == 0xFF111214L)

    // 2. Preset table.
    check("8 presets", AppearanceLogic.PRESETS.size == 8)
    check("preset ids unique",
        AppearanceLogic.PRESETS.map { it.id }.toSet().size == AppearanceLogic.PRESETS.size)
    check("all presets opaque dark low-saturation",
        AppearanceLogic.PRESETS.all { preset ->
            val argb = preset.argb
            val a = (argb shr 24) and 0xFF
            val r = (argb shr 16) and 0xFF
            val g = (argb shr 8) and 0xFF
            val b = argb and 0xFF
            a == 0xFFL && r <= 0x40 && g <= 0x40 && b <= 0x40
        })

    // 3. parseMode.
    check("parseMode null -> PRESET",
        AppearanceLogic.parseMode(null) == BackgroundMode.COLOR_PRESET)
    check("parseMode unknown -> PRESET",
        AppearanceLogic.parseMode("bogus") == BackgroundMode.COLOR_PRESET)
    check("parseMode COLOR_CUSTOM",
        AppearanceLogic.parseMode("COLOR_CUSTOM") == BackgroundMode.COLOR_CUSTOM)
    check("parseMode IMAGE",
        AppearanceLogic.parseMode("IMAGE") == BackgroundMode.IMAGE)

    // 4. clampScrimAlpha.
    check("clamp below 0 -> 0", AppearanceLogic.clampScrimAlpha(-0.1f) == 0f)
    check("clamp above 0.6 -> 0.6", AppearanceLogic.clampScrimAlpha(0.9f) == 0.6f)
    check("clamp inside unchanged", AppearanceLogic.clampScrimAlpha(0.3f) == 0.3f)

    // 5. parseColorHex.
    check("parse #RRGGBB",
        AppearanceLogic.parseColorHex("#1A1E26") == 0xFF1A1E26L)
    check("parse RRGGBB without hash",
        AppearanceLogic.parseColorHex("1A1E26") == 0xFF1A1E26L)
    check("parse #AARRGGBB",
        AppearanceLogic.parseColorHex("#801A1E26") == 0x801A1E26L)
    check("parse lowercase hex",
        AppearanceLogic.parseColorHex("#1a1e26") == 0xFF1A1E26L)
    check("parse non-hex -> null",
        AppearanceLogic.parseColorHex("#GGGGGG") == null)
    check("parse wrong length -> null",
        AppearanceLogic.parseColorHex("#12345") == null)
    check("parse 7 hex digits -> null",
        AppearanceLogic.parseColorHex("#1234567") == null)
    check("parse empty -> null",
        AppearanceLogic.parseColorHex("") == null)
    check("parse whitespace padded accepted",
        AppearanceLogic.parseColorHex("  #1A1E26  ") == 0xFF1A1E26L)

    // 6. formatColorHex round-trip.
    check("format round-trips",
        AppearanceLogic.formatColorHex(0xFF1A1E26L) == "#FF1A1E26")
    check("format then parse round-trips",
        AppearanceLogic.parseColorHex(
            AppearanceLogic.formatColorHex(0x801A1E26L)
        ) == 0x801A1E26L)

    // 7. resolveBackground.
    check("preset resolves to solid preset colour",
        AppearanceLogic.resolveBackground(
            BackgroundMode.COLOR_PRESET, "indigo", 0xFF000000L, null
        ) == ResolvedBackground.Solid(0xFF1A1E26L))
    check("unknown preset falls back to default",
        AppearanceLogic.resolveBackground(
            BackgroundMode.COLOR_PRESET, "nope", 0xFF000000L, null
        ) == ResolvedBackground.Solid(0xFF111214L))
    check("custom mode resolves to custom colour",
        AppearanceLogic.resolveBackground(
            BackgroundMode.COLOR_CUSTOM, "ink", 0xFF112233L, null
        ) == ResolvedBackground.Solid(0xFF112233L))
    check("image mode with null path falls back to solid",
        AppearanceLogic.resolveBackground(
            BackgroundMode.IMAGE, "ink", 0xFF000000L, null
        ) == ResolvedBackground.Solid(0xFF111214L))
    check("image mode with missing file falls back to solid",
        AppearanceLogic.resolveBackground(
            BackgroundMode.IMAGE, "slate", 0xFF000000L, "/nonexistent/background.jpg"
        ) == ResolvedBackground.Solid(0xFF191A1DL))
    val tmp = File.createTempFile("appearance-bg", ".jpg")
    try {
        val resolved = AppearanceLogic.resolveBackground(
            BackgroundMode.IMAGE, "ink", 0xFF000000L, tmp.absolutePath
        )
        check("image mode with existing file resolves to Image",
            resolved is ResolvedBackground.Image &&
                (resolved as ResolvedBackground.Image).file.absolutePath == tmp.absolutePath)
    } finally {
        tmp.delete()
    }
    check("snapshot overload agrees with field overload",
        AppearanceLogic.resolveBackground(
            AppearanceState(
                mode = BackgroundMode.COLOR_CUSTOM,
                customColor = 0xFF445566L
            )
        ) == ResolvedBackground.Solid(0xFF445566L))

    println("TestAppearance: $passed passed, $failed failed")
    if (failed > 0) {
        kotlin.system.exitProcess(1)
    }
}
