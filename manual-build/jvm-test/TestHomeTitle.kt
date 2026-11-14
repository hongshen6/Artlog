// JVM test for the P2 home-title logic — NOT part of the app build
// (lives under manual-build/jvm-test, never shipped). Compiles the
// real data-layer source AppearanceSettings.kt with kotlinc and runs
// against pure functions only: no device, no Android runtime, no
// SharedPreferences, no Room, no network. What is tested is exactly
// the logic the HomeScreen title editor delegates to: the default
// title, the 20-character limit (String.length units), the blank
// rule, and the missing/blank -> default fallback used by
// readSnapshot() and by "restore default" (delete the key).
//
// Covered:
//  1. Default title is the previously hard-coded headline.
//  2. isSaveable: normal text saves; empty / whitespace-only does
//     not; exactly 20 characters saves; 21 does not.
//  3. Counting is String.length: one emoji counts as 2, so 10 emoji
//     fill the limit and 11 do not save.
//  4. displayTitle: null / blank -> default; a stored custom title
//     is returned unchanged.
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
    // 1. Default title.
    check("default title is the old hard-coded headline",
        HomeTitleLogic.DEFAULT_TITLE == "每一笔，都是来时的路。")
    check("max title length is 20",
        HomeTitleLogic.MAX_TITLE_LENGTH == 20)
    check("key name is home_title",
        AppearanceSettings.KEY_HOME_TITLE == "home_title")
    check("AppearanceState default title is the default",
        AppearanceState().homeTitle == HomeTitleLogic.DEFAULT_TITLE)

    // 2. isSaveable: blank / length rules.
    check("normal title saves",
        HomeTitleLogic.isSaveable("我的工作室"))
    check("empty title does not save",
        !HomeTitleLogic.isSaveable(""))
    check("whitespace-only title does not save",
        !HomeTitleLogic.isSaveable("   "))
    check("exactly 20 characters saves",
        HomeTitleLogic.isSaveable("一二三四五六七八九十一二三四五六七八九十"))
    check("21 characters does not save",
        !HomeTitleLogic.isSaveable("一二三四五六七八九十一二三四五六七八九十一"))

    // 3. Counting is String.length: one emoji counts as 2.
    val tenEmoji = "🎨".repeat(10)
    check("one emoji counts as 2 chars", "🎨".length == 2)
    check("10 emoji fill the 20-char limit",
        tenEmoji.length == 20 && HomeTitleLogic.isSaveable(tenEmoji))
    check("11 emoji exceed the limit",
        !HomeTitleLogic.isSaveable("🎨".repeat(11)))

    // 4. displayTitle fallback.
    check("displayTitle null -> default",
        HomeTitleLogic.displayTitle(null) == HomeTitleLogic.DEFAULT_TITLE)
    check("displayTitle blank -> default",
        HomeTitleLogic.displayTitle("   ") == HomeTitleLogic.DEFAULT_TITLE)
    check("displayTitle empty -> default",
        HomeTitleLogic.displayTitle("") == HomeTitleLogic.DEFAULT_TITLE)
    check("displayTitle custom title unchanged",
        HomeTitleLogic.displayTitle("我的工作室") == "我的工作室")

    println("TestHomeTitle: $passed passed, $failed failed")
    if (failed > 0) {
        kotlin.system.exitProcess(1)
    }
}
