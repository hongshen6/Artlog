package com.artlog.app.ui.detail

/**
 * Pure decision logic for the P2 gallery detail page — no Android
 * runtime, no database, no Compose. Covered by the JVM test
 * TestDetailNavigation.
 *
 * Prev/next navigation walks the ordered id list the user came in
 * from (the Gallery list after its current search / status / tag
 * filters and sort — see GalleryViewModel). It never falls back to
 * the whole library: when the list is empty, or the current artwork
 * is not in it, there is no previous/next artwork.
 */
object DetailNavigationLogic {

    /** Index of [currentId] in [ids], or -1 when it is not in the list. */
    fun indexOf(ids: List<String>, currentId: String): Int = ids.indexOf(currentId)

    /** True when a previous artwork exists in the list. */
    fun hasPrevious(ids: List<String>, currentId: String): Boolean {
        val index = indexOf(ids, currentId)
        return index > 0
    }

    /** True when a next artwork exists in the list. */
    fun hasNext(ids: List<String>, currentId: String): Boolean {
        val index = indexOf(ids, currentId)
        return index >= 0 && index < ids.lastIndex
    }

    /** Id of the previous artwork, or null at the head / outside the list. */
    fun previousId(ids: List<String>, currentId: String): String? {
        val index = indexOf(ids, currentId)
        return if (index > 0) ids[index - 1] else null
    }

    /** Id of the next artwork, or null at the tail / outside the list. */
    fun nextId(ids: List<String>, currentId: String): String? {
        val index = indexOf(ids, currentId)
        return if (index >= 0 && index < ids.lastIndex) ids[index + 1] else null
    }

    /**
     * Position label such as "3 / 12", or null when the current
     * artwork is not in the list (no prev/next UI in that case).
     */
    fun positionLabel(ids: List<String>, currentId: String): String? {
        val index = indexOf(ids, currentId)
        return if (index >= 0) "${index + 1} / ${ids.size}" else null
    }
}

/**
 * Pure logic for the collapsible long-text rows in the detail page
 * (creation summary / description). A text only gets a fold control
 * when it is longer than [COLLAPSE_THRESHOLD] characters; shorter
 * texts are shown in full and never get a dangling "展开" button.
 */
object DetailTextLogic {
    const val COLLAPSE_THRESHOLD = 120

    /** True when [text] is long enough to be shown folded at first. */
    fun needsCollapse(text: String): Boolean = text.length > COLLAPSE_THRESHOLD
}
