package com.artlog.app.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Book
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Image
import androidx.compose.ui.graphics.vector.ImageVector

/** Bottom navigation destinations. Five entries, fixed order (spec V1.0, section 3). */
sealed class Destination(
    val route: String,
    val label: String,
    val icon: ImageVector
) {
    data object Home : Destination("home", "Home", Icons.Filled.Home)
    data object Gallery : Destination("gallery", "Gallery", Icons.Filled.Image)
    data object Add : Destination("add", "Add", Icons.Filled.AddCircle)
    data object Journal : Destination("journal", "Journal", Icons.Filled.Book)
    data object Statistics : Destination("statistics", "Statistics", Icons.Filled.BarChart)

    companion object {
        /**
         * Built lazily, NOT in <clinit>: the list must not be constructed
         * while the Destination class is still initializing. The five
         * `data object` subclasses are initialized on first access, and
         * the first access at runtime is `Destination.Home` (ArtlogApp's
         * initial state). Initializing `Home` first runs the superclass
         * `Destination.<clinit>` while `Home.<clinit>` is still in
         * progress; a re-entrant read of `Home.INSTANCE` at that point
         * returns null (JLS 12.4.2 — the field is only assigned at the
         * end of the subclass <clinit>), so an eager list would contain
         * a null at index 0 and crash the bottom bar on first
         * composition (v6 crash log, 2026-11-12). `by lazy` defers the
         * list construction until after all class initialization has
         * completed, so every element is a real instance.
         */
        val bottomNavItems: List<Destination> by lazy {
            listOf(Home, Gallery, Add, Journal, Statistics)
        }
    }
}

/** Settings is reached from the Home top bar; it is not a bottom-nav entry. */
object SettingsRoute {
    const val ROUTE = "settings"
}
