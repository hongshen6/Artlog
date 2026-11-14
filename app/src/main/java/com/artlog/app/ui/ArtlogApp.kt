package com.artlog.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.artlog.app.data.AppearanceSettings
import com.artlog.app.data.ArtworkRepository
import com.artlog.app.data.BackupManager
import com.artlog.app.data.DirectoryCategoryRepository
import com.artlog.app.data.InspirationRepository
import com.artlog.app.data.MusicImport
import com.artlog.app.data.MusicTrackRepository
import com.artlog.app.data.WebsiteFavoriteRepository
import com.artlog.app.data.WebsiteOverrideRepository
import com.artlog.app.playback.MusicPlaybackState
import com.artlog.app.ui.add.AddScreen
import com.artlog.app.ui.detail.DetailScreen
import com.artlog.app.ui.gallery.GalleryScreen
import com.artlog.app.ui.directory.VisualInspirationScreen
import com.artlog.app.ui.home.HomeScreen
import com.artlog.app.ui.journal.JournalScreen
import com.artlog.app.ui.music.MusicScreen
import com.artlog.app.ui.navigation.Destination
import com.artlog.app.ui.screens.SettingsScreen
import com.artlog.app.ui.statistics.StatisticsScreen
import com.artlog.app.ui.tags.TagManagementScreen
import com.artlog.app.ui.theme.ArtlogAccent

/**
 * App shell: bottom navigation with exactly five entries (spec V1.0,
 * section 3). Add is the centre entry and is visually emphasized as the
 * main action.
 *
 * Navigation is a small state machine over [Destination], plus three
 * overlay states: settings, artwork detail and add/edit. The bottom bar
 * is hidden on detail and add/edit screens so the artwork is the focus.
 *
 * Round 2: Home, Gallery, Add and Detail are wired to the real local
 * Room database through [ArtworkRepository].
 *
 * Round 3: Journal and Statistics are real screens over the same
 * database (journal entries, tag system, SQL statistics aggregates),
 * and tag management is an overlay reached from the Gallery.
 */
@Composable
fun ArtlogApp(
    repository: ArtworkRepository,
    backupManager: BackupManager,
    inspirationRepository: InspirationRepository,
    websiteFavoriteRepository: WebsiteFavoriteRepository,
    websiteOverrideRepository: WebsiteOverrideRepository,
    directoryCategoryRepository: DirectoryCategoryRepository,
    /** Next iteration (stages 5-7): music player overlay. */
    musicTrackRepository: MusicTrackRepository,
    musicImport: MusicImport,
    appearanceSettings: AppearanceSettings,
    /** P0: Application-scoped playback state (passed to the factory). */
    musicPlaybackState: MusicPlaybackState,
) {
    val appContext = LocalContext.current.applicationContext
    val viewModelFactory = remember(
        repository, backupManager, inspirationRepository, websiteFavoriteRepository,
        websiteOverrideRepository, directoryCategoryRepository,
        musicTrackRepository, musicImport, appearanceSettings, musicPlaybackState
    ) {
        ArtlogViewModelFactory(
            repository, backupManager, inspirationRepository,
            websiteFavoriteRepository, websiteOverrideRepository,
            directoryCategoryRepository,
            musicTrackRepository, musicImport, appearanceSettings,
            musicPlaybackState, appContext
        )
    }
    var currentDestination by remember { mutableStateOf<Destination>(Destination.Home) }
    var showSettings by remember { mutableStateOf(false) }
    var showTagManagement by remember { mutableStateOf(false) }
    var detailArtworkId by remember { mutableStateOf<String?>(null) }
    /** P2 gallery: ordered ids of the list the user came in from
     * (the Gallery list after its current filters and sort). Empty
     * when the detail page was opened without a list (Home, Journal,
     * Add-after-save) — prev/next are then unavailable, never a
     * silent fall-back to the whole library. */
    var detailArtworkIds by remember { mutableStateOf<List<String>>(emptyList()) }
    var editArtworkId by remember { mutableStateOf<String?>(null) }
    /** Artlog 2.0: the Visual Inspiration overlay, reached from Home. Not a bottom-nav tab. */
    var showVisualInspiration by remember { mutableStateOf(false) }
    /** Next iteration (stages 5-7): the Music overlay, reached from Home. Not a bottom-nav tab. */
    var showMusic by remember { mutableStateOf(false) }
    /** When set, the Journal tab opens the editor for this entry. */
    var journalEditEntryId by remember { mutableStateOf<String?>(null) }

    val inOverlay = showSettings || showTagManagement ||
        detailArtworkId != null || editArtworkId != null || showVisualInspiration ||
        showMusic

    /**
     * Unified back handling: the in-app back arrows and the Android
     * system back gesture call this same function, so both behave
     * identically. Only the topmost overlay is closed, in the same
     * priority order as the `when` branches below. Returns false when
     * no overlay is open, leaving the system default (finish the
     * Activity); back on a plain bottom-nav tab is never redirected
     * to Home.
     *
     * Note: closing the edit page only clears editArtworkId — the
     * detail page underneath stays open because detailArtworkId is
     * untouched. Opening an overlay never modifies
     * currentDestination, so closing one restores the tab the user
     * came from.
     */
    fun handleBack(): Boolean = when {
        editArtworkId != null -> {
            editArtworkId = null
            true
        }
        detailArtworkId != null -> {
            detailArtworkId = null
            true
        }
        showVisualInspiration -> {
            showVisualInspiration = false
            true
        }
        showMusic -> {
            showMusic = false
            true
        }
        showTagManagement -> {
            showTagManagement = false
            true
        }
        showSettings -> {
            showSettings = false
            true
        }
        else -> false
    }

    // Intercept the system back gesture only while an overlay is
    // showing; on a plain tab the system default applies.
    BackHandler(enabled = inOverlay) { handleBack() }

    // Stage 8: transparent so the background layer in MainActivity
    // shows through on every page. The bottom bar is transparent too
    // (2026-11-14: the nav bar used to keep its own opaque surface
    // colour, which cut the custom background off in a black band);
    // the background layer in MainActivity already covers the whole
    // screen behind the shell, so only the bar's container colour
    // had to change. Icons, labels, the selected indicator pill and
    // click behaviour are untouched.
    Scaffold(
        containerColor = Color.Transparent,
        bottomBar = {
            if (!inOverlay) {
                NavigationBar(
                    containerColor = Color.Transparent
                ) {
                    Destination.bottomNavItems.forEach { destination ->
                        val selected = currentDestination == destination
                        val isAdd = destination is Destination.Add
                        NavigationBarItem(
                            selected = selected,
                            onClick = { currentDestination = destination },
                            icon = {
                                Icon(
                                    imageVector = destination.icon,
                                    contentDescription = destination.label,
                                    modifier = if (isAdd) Modifier.size(30.dp) else Modifier.size(24.dp)
                                )
                            },
                            label = {
                                Text(
                                    text = destination.label,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            },
                            // P2 stage 6: unselected nav colours come from the
                            // theme scheme (onSurfaceVariant / tertiary) so they
                            // follow the user's custom text colour; the
                            // selected state keeps the accent as its signal.
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = ArtlogAccent,
                                selectedTextColor = ArtlogAccent,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.tertiary,
                                indicatorColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        androidx.compose.foundation.layout.Box(
            modifier = Modifier.padding(innerPadding)
        ) {
            when {
                editArtworkId != null -> {
                    val id = editArtworkId!!
                    AddScreen(
                        onSaved = { savedId ->
                            editArtworkId = null
                            detailArtworkId = savedId
                        },
                        onBackClick = { handleBack() },
                        viewModelFactory = viewModelFactory,
                        editArtworkId = id
                    )
                }
                detailArtworkId != null -> {
                    val id = detailArtworkId!!
                    DetailScreen(
                        onBackClick = { handleBack() },
                        onEditClick = { editId -> editArtworkId = editId },
                        onDeleted = { detailArtworkId = null },
                        onJournalEntryClick = { entryId ->
                            journalEditEntryId = entryId
                            currentDestination = Destination.Journal
                            detailArtworkId = null
                        },
                        viewModelFactory = viewModelFactory,
                        artworkId = id,
                        artworkIds = detailArtworkIds
                    )
                }
                showVisualInspiration -> {
                    VisualInspirationScreen(
                        onBackClick = { handleBack() },
                        viewModelFactory = viewModelFactory
                    )
                }
                showMusic -> {
                    MusicScreen(
                        onBackClick = { handleBack() },
                        viewModelFactory = viewModelFactory
                    )
                }
                showTagManagement -> {
                    TagManagementScreen(
                        onBackClick = { handleBack() },
                        viewModelFactory = viewModelFactory
                    )
                }
                showSettings -> {
                    SettingsScreen(
                        onBackClick = { handleBack() },
                        viewModelFactory = viewModelFactory
                    )
                }
                else -> {
                    when (currentDestination) {
                        Destination.Home -> HomeScreen(
                            onSettingsClick = { showSettings = true },
                            onGalleryClick = { currentDestination = Destination.Gallery },
                            onJournalClick = { currentDestination = Destination.Journal },
                            onArtworkClick = {
                                detailArtworkId = it
                                detailArtworkIds = emptyList()
                            },
                            onInspirationClick = { showVisualInspiration = true },
                            onMusicClick = { showMusic = true },
                            appearanceSettings = appearanceSettings,
                            musicPlaybackState = musicPlaybackState,
                            viewModelFactory = viewModelFactory
                        )
                        Destination.Gallery -> GalleryScreen(
                            onArtworkClick = { id, ids ->
                                detailArtworkId = id
                                detailArtworkIds = ids
                            },
                            onManageTagsClick = { showTagManagement = true },
                            viewModelFactory = viewModelFactory
                        )
                        Destination.Add -> AddScreen(
                            onSaved = { savedId ->
                                detailArtworkId = savedId
                                detailArtworkIds = emptyList()
                            },
                            viewModelFactory = viewModelFactory
                        )
                        Destination.Journal -> JournalScreen(
                            viewModelFactory = viewModelFactory,
                            onArtworkClick = {
                                detailArtworkId = it
                                detailArtworkIds = emptyList()
                            },
                            openEntryId = journalEditEntryId,
                            onEntryOpened = { journalEditEntryId = null }
                        )
                        Destination.Statistics -> StatisticsScreen(
                            viewModelFactory = viewModelFactory
                        )
                    }
                }
            }
        }
    }
}
