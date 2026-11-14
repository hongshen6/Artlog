// JVM test for the P1 playback mode logic — NOT part of the app
// build (lives under manual-build/jvm-test, never shipped). Compiles
// the real PlaybackModeLogic.kt with kotlinc and runs against pure
// functions only: no device, no Android runtime, no MediaController,
// no Room, no network. What is tested is exactly the decision logic
// MusicPlaybackState delegates to: the three-way repeat cycle and its
// ExoPlayer mapping, the shuffle sequence (Fisher-Yates, current
// track first, full coverage, no repeats, next-round reshuffle),
// delete-current-track continuation per mode, the effective queue
// order, and the persistence serialize/parse contract
// (repeat_mode as enum name, shuffle_enabled as Boolean).
//
// Covered:
//  1. nextMode: ORDER -> ALL -> SINGLE -> ORDER.
//  2. toPlayerRepeatMode / fromPlayerRepeatMode: ORDER->OFF(0),
//     ALL->ALL(2), SINGLE->ONE(1) and back; unknown values -> ORDER.
//  3. label: "顺序播放" / "列表循环" / "单曲循环".
//  4. parseRepeatMode: null / blank / unknown -> ORDER; enum names
//     (trimmed) parse; lowercase does not.
//  5. buildShuffleOrder: empty -> empty; single -> itself; current
//     track stays first, the rest are shuffled, every id appears
//     exactly once; same seed -> same order; unknown start id ->
//     library head is the start.
//  6. reshuffleForNextRound: empty/single passthrough; new round
//     covers all ids exactly once and (list >= 2) never starts with
//     the previous round's last track.
//  7. nextAfterDeletion: mid-list delete continues to the following
//     track (ORDER / SINGLE / ALL, shuffled or not); deleting the
//     last track stops under ORDER and SINGLE, wraps to the first
//     remaining track under ALL, and reshuffles a new round under
//     ALL + shuffle; deleting the only track / an unknown id / from
//     an empty list stops.
//  8. effectiveOrderIds: shuffle off -> library order; shuffle on
//     with a sequence -> the sequence; empty sequence -> library
//     order; stale ids dropped, tracks added mid-play appended.
//  9. Persistence contract: mode.name survives parseRepeatMode for
//     all three modes; an invalid stored string falls back to ORDER;
//     a fake-prefs map round-trips both keys (the exact contract
//     AppearanceSettings + BackupManager rely on; the real
//     SharedPreferences read/write stays a device item).
package com.artlog.app.data

import com.artlog.app.playback.MusicRepeatMode
import com.artlog.app.playback.PlaybackModeLogic
import kotlin.random.Random

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
    // 1. 三档循环切换。
    check("nextMode ORDER -> ALL",
        PlaybackModeLogic.nextMode(MusicRepeatMode.ORDER) == MusicRepeatMode.ALL)
    check("nextMode ALL -> SINGLE",
        PlaybackModeLogic.nextMode(MusicRepeatMode.ALL) == MusicRepeatMode.SINGLE)
    check("nextMode SINGLE -> ORDER",
        PlaybackModeLogic.nextMode(MusicRepeatMode.SINGLE) == MusicRepeatMode.ORDER)

    // 2. 档位 <-> ExoPlayer repeatMode（数值与 Player 常量一致：
    // OFF=0、ONE=1、ALL=2）。
    check("toPlayer ORDER -> 0",
        PlaybackModeLogic.toPlayerRepeatMode(MusicRepeatMode.ORDER) == 0)
    check("toPlayer ALL -> 2",
        PlaybackModeLogic.toPlayerRepeatMode(MusicRepeatMode.ALL) == 2)
    check("toPlayer SINGLE -> 1",
        PlaybackModeLogic.toPlayerRepeatMode(MusicRepeatMode.SINGLE) == 1)
    check("fromPlayer 0 -> ORDER",
        PlaybackModeLogic.fromPlayerRepeatMode(0) == MusicRepeatMode.ORDER)
    check("fromPlayer 1 -> SINGLE",
        PlaybackModeLogic.fromPlayerRepeatMode(1) == MusicRepeatMode.SINGLE)
    check("fromPlayer 2 -> ALL",
        PlaybackModeLogic.fromPlayerRepeatMode(2) == MusicRepeatMode.ALL)
    check("fromPlayer 99 -> ORDER (unknown falls back)",
        PlaybackModeLogic.fromPlayerRepeatMode(99) == MusicRepeatMode.ORDER)
    check("fromPlayer -1 -> ORDER (unknown falls back)",
        PlaybackModeLogic.fromPlayerRepeatMode(-1) == MusicRepeatMode.ORDER)

    // 3. 三档文字各有区分。
    check("label ORDER", PlaybackModeLogic.label(MusicRepeatMode.ORDER) == "顺序播放")
    check("label ALL", PlaybackModeLogic.label(MusicRepeatMode.ALL) == "列表循环")
    check("label SINGLE", PlaybackModeLogic.label(MusicRepeatMode.SINGLE) == "单曲循环")

    // 4. 持久化解析：非法值回落 ORDER。
    check("parse null -> ORDER",
        PlaybackModeLogic.parseRepeatMode(null) == MusicRepeatMode.ORDER)
    check("parse blank -> ORDER",
        PlaybackModeLogic.parseRepeatMode("   ") == MusicRepeatMode.ORDER)
    check("parse ORDER", PlaybackModeLogic.parseRepeatMode("ORDER") == MusicRepeatMode.ORDER)
    check("parse ALL", PlaybackModeLogic.parseRepeatMode("ALL") == MusicRepeatMode.ALL)
    check("parse SINGLE",
        PlaybackModeLogic.parseRepeatMode("SINGLE") == MusicRepeatMode.SINGLE)
    check("parse trimmed ' ALL ' -> ALL",
        PlaybackModeLogic.parseRepeatMode(" ALL ") == MusicRepeatMode.ALL)
    check("parse bogus -> ORDER",
        PlaybackModeLogic.parseRepeatMode("BOGUS") == MusicRepeatMode.ORDER)
    check("parse lowercase 'all' -> ORDER (names are case-sensitive)",
        PlaybackModeLogic.parseRepeatMode("all") == MusicRepeatMode.ORDER)

    // 5. 洗牌序列。
    val ids = listOf("a", "b", "c", "d", "e")
    check("shuffle empty -> empty",
        PlaybackModeLogic.buildShuffleOrder(emptyList(), "a") == emptyList<String>())
    check("shuffle single -> itself",
        PlaybackModeLogic.buildShuffleOrder(listOf("a"), "a") == listOf("a"))
    val shuffled = PlaybackModeLogic.buildShuffleOrder(ids, "c", Random(42))
    check("shuffle keeps current track first", shuffled.first() == "c")
    check("shuffle covers every id exactly once",
        shuffled.size == 5 && shuffled.toSet() == ids.toSet())
    check("shuffle rest is actually shuffled (seed 42)",
        shuffled.drop(1).toSet() == setOf("a", "b", "d", "e"))
    check("shuffle is deterministic for the same seed",
        PlaybackModeLogic.buildShuffleOrder(ids, "c", Random(42)) == shuffled)
    val shuffledOtherSeed = PlaybackModeLogic.buildShuffleOrder(ids, "c", Random(7))
    check("shuffle other seed still covers all ids",
        shuffledOtherSeed.first() == "c" &&
            shuffledOtherSeed.toSet() == ids.toSet())
    check("shuffle unknown start id -> library head first",
        PlaybackModeLogic.buildShuffleOrder(ids, "zzz", Random(3)).first() == "a")
    check("shuffle null start id -> library head first",
        PlaybackModeLogic.buildShuffleOrder(ids, null, Random(3)).first() == "a")

    // 6. 下一轮重洗。
    check("reshuffle empty -> empty",
        PlaybackModeLogic.reshuffleForNextRound(listOf("a"), emptyList()) ==
            emptyList<String>())
    check("reshuffle single -> itself",
        PlaybackModeLogic.reshuffleForNextRound(listOf("a"), listOf("a")) ==
            listOf("a"))
    val prevRound = listOf("c", "a", "e", "b", "d")
    var reshuffleOk = true
    for (seed in 0 until 20) {
        val round = PlaybackModeLogic.reshuffleForNextRound(
            prevRound, ids, Random(seed)
        )
        if (round.size != 5 || round.toSet() != ids.toSet() ||
            round.first() == prevRound.last()
        ) {
            reshuffleOk = false
        }
    }
    check("reshuffle: 20 seeds all cover all ids and never start with prev last",
        reshuffleOk)
    check("reshuffle with no previous last still covers all ids",
        PlaybackModeLogic.reshuffleForNextRound(emptyList(), ids, Random(5))
            .toSet() == ids.toSet())

    // 7. 删正在播放的歌：按模式算下一首。
    val order = listOf("a", "b", "c", "d")
    val midOrder = PlaybackModeLogic.nextAfterDeletion(
        order, "b", MusicRepeatMode.ORDER, false, Random(1)
    )
    check("delete mid (ORDER) -> next is following track",
        midOrder is PlaybackModeLogic.DeleteNext.Play &&
            (midOrder as PlaybackModeLogic.DeleteNext.Play).nextId == "c" &&
            midOrder.newOrder == listOf("a", "c", "d"))
    val midSingle = PlaybackModeLogic.nextAfterDeletion(
        order, "b", MusicRepeatMode.SINGLE, false, Random(1)
    )
    check("delete mid (SINGLE) -> next is following track",
        midSingle is PlaybackModeLogic.DeleteNext.Play &&
            (midSingle as PlaybackModeLogic.DeleteNext.Play).nextId == "c")
    val midShuffle = PlaybackModeLogic.nextAfterDeletion(
        listOf("c", "a", "d", "b"), "a", MusicRepeatMode.ALL, true, Random(1)
    )
    check("delete mid (ALL+shuffle) -> next in shuffle order",
        midShuffle is PlaybackModeLogic.DeleteNext.Play &&
            (midShuffle as PlaybackModeLogic.DeleteNext.Play).nextId == "d" &&
            midShuffle.newOrder == listOf("c", "d", "b"))
    check("delete last (ORDER) -> stop",
        PlaybackModeLogic.nextAfterDeletion(
            order, "d", MusicRepeatMode.ORDER, false, Random(1)
        ) is PlaybackModeLogic.DeleteNext.Stop)
    check("delete last (ORDER+shuffle) -> stop",
        PlaybackModeLogic.nextAfterDeletion(
            order, "d", MusicRepeatMode.ORDER, true, Random(1)
        ) is PlaybackModeLogic.DeleteNext.Stop)
    check("delete last (SINGLE) -> stop",
        PlaybackModeLogic.nextAfterDeletion(
            order, "d", MusicRepeatMode.SINGLE, false, Random(1)
        ) is PlaybackModeLogic.DeleteNext.Stop)
    val wrapAll = PlaybackModeLogic.nextAfterDeletion(
        order, "d", MusicRepeatMode.ALL, false, Random(1)
    )
    check("delete last (ALL) -> wraps to first remaining",
        wrapAll is PlaybackModeLogic.DeleteNext.Play &&
            (wrapAll as PlaybackModeLogic.DeleteNext.Play).nextId == "a" &&
            wrapAll.newOrder == listOf("a", "b", "c"))
    val wrapShuffle = PlaybackModeLogic.nextAfterDeletion(
        order, "d", MusicRepeatMode.ALL, true, Random(11)
    )
    check("delete last (ALL+shuffle) -> new round, next is its first",
        wrapShuffle is PlaybackModeLogic.DeleteNext.Play &&
            (wrapShuffle as PlaybackModeLogic.DeleteNext.Play).newOrder.size == 3 &&
            wrapShuffle.newOrder.toSet() == setOf("a", "b", "c") &&
            wrapShuffle.nextId == wrapShuffle.newOrder.first())
    check("delete only track -> stop",
        PlaybackModeLogic.nextAfterDeletion(
            listOf("a"), "a", MusicRepeatMode.ALL, false, Random(1)
        ) is PlaybackModeLogic.DeleteNext.Stop)
    check("delete from empty list -> stop",
        PlaybackModeLogic.nextAfterDeletion(
            emptyList(), "a", MusicRepeatMode.ALL, false, Random(1)
        ) is PlaybackModeLogic.DeleteNext.Stop)
    check("delete unknown id -> stop",
        PlaybackModeLogic.nextAfterDeletion(
            order, "zzz", MusicRepeatMode.ALL, false, Random(1)
        ) is PlaybackModeLogic.DeleteNext.Stop)
    val firstDelete = PlaybackModeLogic.nextAfterDeletion(
        order, "a", MusicRepeatMode.ORDER, false, Random(1)
    )
    check("delete first (ORDER) -> next is second track",
        firstDelete is PlaybackModeLogic.DeleteNext.Play &&
            (firstDelete as PlaybackModeLogic.DeleteNext.Play).nextId == "b" &&
            firstDelete.newOrder == listOf("b", "c", "d"))

    // 8. 当前有效队列顺序。
    check("effective order: shuffle off -> library order",
        PlaybackModeLogic.effectiveOrderIds(ids, false, listOf("c", "a")) == ids)
    check("effective order: shuffle on -> shuffle sequence",
        PlaybackModeLogic.effectiveOrderIds(
            ids, true, listOf("c", "a", "e", "b", "d")
        ) == listOf("c", "a", "e", "b", "d"))
    check("effective order: shuffle on but empty sequence -> library order",
        PlaybackModeLogic.effectiveOrderIds(ids, true, emptyList()) == ids)
    check("effective order: stale ids dropped, new tracks appended",
        PlaybackModeLogic.effectiveOrderIds(
            listOf("a", "b", "c", "d", "f"), true, listOf("c", "a", "zzz")
        ) == listOf("c", "a", "b", "d", "f"))

    // 9. 持久化往返（fake-prefs map：与 AppearanceSettings /
    // BackupManager 依赖的契约一致）。
    val fakePrefs = mutableMapOf<String, Any?>()
    for (mode in MusicRepeatMode.entries) {
        fakePrefs["repeat_mode"] = mode.name
        check("prefs round-trip ${mode.name}",
            PlaybackModeLogic.parseRepeatMode(fakePrefs["repeat_mode"] as String?) == mode)
    }
    fakePrefs["shuffle_enabled"] = true
    check("prefs round-trip shuffle true",
        fakePrefs["shuffle_enabled"] as Boolean)
    fakePrefs["shuffle_enabled"] = false
    check("prefs round-trip shuffle false",
        !(fakePrefs["shuffle_enabled"] as Boolean))
    fakePrefs["repeat_mode"] = "BOGUS"
    check("prefs invalid stored repeat_mode -> ORDER default",
        PlaybackModeLogic.parseRepeatMode(fakePrefs["repeat_mode"] as String?) ==
            MusicRepeatMode.ORDER)
    fakePrefs.remove("repeat_mode")
    check("prefs missing repeat_mode -> ORDER default",
        PlaybackModeLogic.parseRepeatMode(fakePrefs["repeat_mode"] as String?) ==
            MusicRepeatMode.ORDER)

    println("TestPlaybackMode: $passed passed, $failed failed")
    if (failed > 0) throw AssertionError("$failed checks failed")
}
