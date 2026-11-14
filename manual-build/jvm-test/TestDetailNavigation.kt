// JVM test for the P2 gallery detail logic — NOT part of the app
// build (lives under manual-build/jvm-test, never shipped). Compiles
// the real DetailNavigationLogic.kt with kotlinc and runs against
// pure functions only: no device, no Android runtime, no Compose,
// no Room, no network. What is tested is exactly the decision logic
// DetailViewModel delegates to: prev/next inside the ordered id list
// the user came in from, the position label, and the long-text fold
// threshold used by the creation-summary rows.
//
// Covered:
//  1. indexOf / hasPrevious / hasNext: head, middle, tail, single,
//     empty list, unknown id.
//  2. previousId / nextId: exact neighbours; null at both ends and
//     outside the list (never wraps, never falls back to the library).
//  3. positionLabel: "1 / 5" .. "5 / 5"; null when the list is empty
//     or the current artwork is not in it (prev/next UI hidden).
//  4. DetailTextLogic.needsCollapse: threshold 120 — 119/120 chars
//     show in full, 121 folds; empty and blank texts never fold.

package com.artlog.app.data

import com.artlog.app.ui.detail.DetailNavigationLogic
import com.artlog.app.ui.detail.DetailTextLogic

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
    val ids = listOf("a", "b", "c", "d", "e")

    // 1. Index and availability.
    check("indexOf middle", DetailNavigationLogic.indexOf(ids, "c") == 2)
    check("indexOf head", DetailNavigationLogic.indexOf(ids, "a") == 0)
    check("indexOf tail", DetailNavigationLogic.indexOf(ids, "e") == 4)
    check("indexOf unknown -> -1", DetailNavigationLogic.indexOf(ids, "z") == -1)
    check("indexOf empty -> -1", DetailNavigationLogic.indexOf(emptyList(), "a") == -1)
    check("hasPrevious middle", DetailNavigationLogic.hasPrevious(ids, "c"))
    check("hasPrevious head false", !DetailNavigationLogic.hasPrevious(ids, "a"))
    check("hasPrevious unknown false", !DetailNavigationLogic.hasPrevious(ids, "z"))
    check("hasPrevious empty false", !DetailNavigationLogic.hasPrevious(emptyList(), "a"))
    check("hasNext middle", DetailNavigationLogic.hasNext(ids, "c"))
    check("hasNext tail false", !DetailNavigationLogic.hasNext(ids, "e"))
    check("hasNext unknown false", !DetailNavigationLogic.hasNext(ids, "z"))
    check("hasNext empty false", !DetailNavigationLogic.hasNext(emptyList(), "a"))
    check("single item: no prev", !DetailNavigationLogic.hasPrevious(listOf("a"), "a"))
    check("single item: no next", !DetailNavigationLogic.hasNext(listOf("a"), "a"))

    // 2. Neighbours, no wrap-around.
    check("previousId middle", DetailNavigationLogic.previousId(ids, "c") == "b")
    check("previousId head -> null", DetailNavigationLogic.previousId(ids, "a") == null)
    check("previousId unknown -> null", DetailNavigationLogic.previousId(ids, "z") == null)
    check("previousId empty -> null", DetailNavigationLogic.previousId(emptyList(), "a") == null)
    check("nextId middle", DetailNavigationLogic.nextId(ids, "c") == "d")
    check("nextId tail -> null", DetailNavigationLogic.nextId(ids, "e") == null)
    check("nextId unknown -> null", DetailNavigationLogic.nextId(ids, "z") == null)
    check("nextId empty -> null", DetailNavigationLogic.nextId(emptyList(), "a") == null)
    check("walk head to tail",
        DetailNavigationLogic.nextId(ids, "a") == "b" &&
            DetailNavigationLogic.nextId(ids, "b") == "c" &&
            DetailNavigationLogic.nextId(ids, "c") == "d" &&
            DetailNavigationLogic.nextId(ids, "d") == "e" &&
            DetailNavigationLogic.nextId(ids, "e") == null)
    check("walk tail to head",
        DetailNavigationLogic.previousId(ids, "e") == "d" &&
            DetailNavigationLogic.previousId(ids, "d") == "c" &&
            DetailNavigationLogic.previousId(ids, "c") == "b" &&
            DetailNavigationLogic.previousId(ids, "b") == "a" &&
            DetailNavigationLogic.previousId(ids, "a") == null)

    // 3. Position label.
    check("positionLabel head", DetailNavigationLogic.positionLabel(ids, "a") == "1 / 5")
    check("positionLabel middle", DetailNavigationLogic.positionLabel(ids, "c") == "3 / 5")
    check("positionLabel tail", DetailNavigationLogic.positionLabel(ids, "e") == "5 / 5")
    check("positionLabel single", DetailNavigationLogic.positionLabel(listOf("a"), "a") == "1 / 1")
    check("positionLabel unknown -> null",
        DetailNavigationLogic.positionLabel(ids, "z") == null)
    check("positionLabel empty -> null",
        DetailNavigationLogic.positionLabel(emptyList(), "a") == null)

    // 4. Long-text fold threshold (120 chars).
    check("needsCollapse 119 chars false",
        !DetailTextLogic.needsCollapse("x".repeat(119)))
    check("needsCollapse 120 chars false",
        !DetailTextLogic.needsCollapse("x".repeat(120)))
    check("needsCollapse 121 chars true",
        DetailTextLogic.needsCollapse("x".repeat(121)))
    check("needsCollapse empty false", !DetailTextLogic.needsCollapse(""))
    check("needsCollapse blank short false",
        !DetailTextLogic.needsCollapse("   "))
    check("needsCollapse long text true",
        DetailTextLogic.needsCollapse("这是一段很长的创作笔记。".repeat(20)))
    check("COLLAPSE_THRESHOLD is 120", DetailTextLogic.COLLAPSE_THRESHOLD == 120)

    println("TestDetailNavigation: $passed passed, $failed failed")
    if (failed > 0) throw AssertionError("$failed checks failed")
}
