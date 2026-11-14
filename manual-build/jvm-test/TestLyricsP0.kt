// JVM test for the P0 global-lyrics wave — NOT part of the app
// build (lives under manual-build/jvm-test, never shipped).
// Compiles the real sources LyricsParser.kt, AudioFocusPolicy.kt,
// FloatingLyricsLogic.kt and AppearanceSettings.kt with kotlinc and
// runs against pure functions only: no device, no Android runtime,
// no Room, no network.
//
// Covered:
//  1. decodeLrcBytes: UTF-8 passthrough; UTF-8 BOM stripped; GBK
//     bytes decode to the right Chinese text (the old UTF-8-only
//     path garbled them); invalid bytes never throw; empty -> "".
//  2. AudioFocusPolicy: LOSS / LOSS_TRANSIENT pause + mark for
//     resume; DUCK only lowers volume; GAIN resumes only when paused
//     by focus loss and restores ducked volume; unknown values no-op;
//     becoming-noisy pauses only while playing.
//  3. FloatingLyricsLogic: clampPosition pulls out-of-range values
//     back to [0, 1]; defaults are x=0.5 / y=0.78 / 16sp;
//     shouldShowFloatingLyrics needs master + floating + a track.
//  4. AppearanceState defaults: every new lyrics key defaults to
//     off / default position / 16sp (opt-in: nothing appears after
//     upgrade until the user turns it on).
package com.artlog.app.data

import com.artlog.app.playback.AudioFocusPolicy

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
    // 1. decodeLrcBytes.
    val utf8 = "[00:01.00]你好世界".toByteArray(Charsets.UTF_8)
    check("utf8 passthrough",
        LyricsParser.decodeLrcBytes(utf8) == "[00:01.00]你好世界")
    val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + utf8
    check("utf8 bom stripped",
        LyricsParser.decodeLrcBytes(bom) == "[00:01.00]你好世界")
    val gbk = "[00:01.00]你好世界".toByteArray(charset("GBK"))
    check("gbk decodes to correct chinese text",
        LyricsParser.decodeLrcBytes(gbk) == "[00:01.00]你好世界")
    check("gbk text parses to a timed line",
        LyricsParser.parseLrc(LyricsParser.decodeLrcBytes(gbk)).timedLines
            .firstOrNull()?.text == "你好世界")
    check("empty bytes -> empty string",
        LyricsParser.decodeLrcBytes(byteArrayOf()) == "")
    check("invalid bytes never throw",
        runCatching {
            LyricsParser.decodeLrcBytes(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x41))
        }.isSuccess)
    check("ascii-only lrc unchanged",
        LyricsParser.decodeLrcBytes("[00:01.00]hello".toByteArray(Charsets.UTF_8)) ==
            "[00:01.00]hello")

    // 2. AudioFocusPolicy.
    val loss = AudioFocusPolicy.onFocusChange(
        AudioFocusPolicy.FOCUS_LOSS, wasPlaying = true,
        pausedByFocusLoss = false, isDucked = false
    )
    check("loss pauses and marks for resume",
        loss.pause && loss.markPausedByFocusLoss && !loss.resume && !loss.duck)
    val lossIdle = AudioFocusPolicy.onFocusChange(
        AudioFocusPolicy.FOCUS_LOSS, wasPlaying = false,
        pausedByFocusLoss = false, isDucked = false
    )
    check("loss while idle does not mark for resume",
        !lossIdle.pause && !lossIdle.markPausedByFocusLoss)
    val transient = AudioFocusPolicy.onFocusChange(
        AudioFocusPolicy.FOCUS_LOSS_TRANSIENT, wasPlaying = true,
        pausedByFocusLoss = false, isDucked = false
    )
    check("transient loss pauses and marks for resume",
        transient.pause && transient.markPausedByFocusLoss)
    val duck = AudioFocusPolicy.onFocusChange(
        AudioFocusPolicy.FOCUS_LOSS_TRANSIENT_CAN_DUCK, wasPlaying = true,
        pausedByFocusLoss = false, isDucked = false
    )
    check("duck lowers volume without pausing",
        duck.duck && !duck.pause && !duck.resume)
    val gain = AudioFocusPolicy.onFocusChange(
        AudioFocusPolicy.FOCUS_GAIN, wasPlaying = false,
        pausedByFocusLoss = true, isDucked = true
    )
    check("gain resumes and restores volume",
        gain.resume && gain.restoreVolume && !gain.pause)
    val gainNoMark = AudioFocusPolicy.onFocusChange(
        AudioFocusPolicy.FOCUS_GAIN, wasPlaying = false,
        pausedByFocusLoss = false, isDucked = false
    )
    check("gain without focus-loss pause does not resume",
        !gainNoMark.resume && !gainNoMark.restoreVolume)
    val unknown = AudioFocusPolicy.onFocusChange(
        999, wasPlaying = true, pausedByFocusLoss = false, isDucked = false
    )
    check("unknown focus value is a no-op",
        !unknown.pause && !unknown.duck && !unknown.resume)
    check("duck factor is 0.3",
        AudioFocusPolicy.DUCK_VOLUME_FACTOR == 0.3f)
    check("becoming noisy pauses only while playing",
        AudioFocusPolicy.shouldPauseOnBecomingNoisy(true) &&
            !AudioFocusPolicy.shouldPauseOnBecomingNoisy(false))

    // 3. FloatingLyricsLogic.
    check("clamp keeps in-range values",
        FloatingLyricsLogic.clampPosition(0.5f, 0.78f) == (0.5f to 0.78f))
    check("clamp pulls out-of-range values back",
        FloatingLyricsLogic.clampPosition(-0.2f, 1.5f) == (0f to 1f))
    check("default position is centered, 78% down",
        FloatingLyricsLogic.DEFAULT_X == 0.5f &&
            FloatingLyricsLogic.DEFAULT_Y == 0.78f)
    check("default floating font size is 16sp",
        FloatingLyricsLogic.DEFAULT_FONT_SIZE_SP == 16f)
    check("floating shows only with master + floating + track",
        FloatingLyricsLogic.shouldShowFloatingLyrics(true, true, true))
    check("floating hidden when master is off",
        !FloatingLyricsLogic.shouldShowFloatingLyrics(false, true, true))
    check("floating hidden when floating switch is off",
        !FloatingLyricsLogic.shouldShowFloatingLyrics(true, false, true))
    check("floating hidden with no current track",
        !FloatingLyricsLogic.shouldShowFloatingLyrics(true, true, false))

    // 4. AppearanceState defaults for the new keys.
    val defaults = AppearanceState()
    check("master lyrics switch defaults off", !defaults.lyricsEnabled)
    check("floating lyrics defaults off", !defaults.floatingLyricsEnabled)
    check("external lyrics defaults off", !defaults.externalLyricsEnabled)
    check("lockscreen lyrics defaults off", !defaults.lockscreenLyricsEnabled)
    check("floating position lock defaults off", !defaults.floatingLyricsLocked)
    check("floating position defaults to 0.5 / 0.78",
        defaults.floatingLyricsX == 0.5f && defaults.floatingLyricsY == 0.78f)
    check("floating font size defaults to 16sp",
        defaults.floatingLyricsFontSize == 16f)

    println("TestLyricsP0: $passed passed, $failed failed")
    if (failed > 0) throw AssertionError("$failed checks failed")
}
