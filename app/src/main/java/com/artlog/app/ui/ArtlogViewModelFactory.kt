package com.artlog.app.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.artlog.app.data.AppearanceSettings
import com.artlog.app.data.ArtworkRepository
import com.artlog.app.data.BackupManager
import com.artlog.app.data.CoverBackfill
import com.artlog.app.data.DirectoryCategoryRepository
import com.artlog.app.data.InspirationRepository
import com.artlog.app.data.MusicImport
import com.artlog.app.data.MusicTrackRepository
import com.artlog.app.data.WebsiteFavoriteRepository
import com.artlog.app.data.WebsiteOverrideRepository
import com.artlog.app.playback.MusicPlaybackState
import com.artlog.app.ui.add.AddViewModel
import com.artlog.app.ui.detail.DetailViewModel
import com.artlog.app.ui.gallery.GalleryViewModel
import com.artlog.app.ui.home.HomeViewModel
import com.artlog.app.ui.directory.LegacyFavoritesViewModel
import com.artlog.app.ui.directory.VisualInspirationViewModel
import com.artlog.app.ui.journal.JournalViewModel
import com.artlog.app.ui.music.MusicViewModel
import com.artlog.app.ui.screens.BackupViewModel
import com.artlog.app.ui.statistics.StatisticsViewModel
import com.artlog.app.ui.tags.TagViewModel

/**
 * Manual ViewModel factory (no DI framework in round 2). Screens that
 * need an artwork id use their own keyed factory at the call site.
 */
class ArtlogViewModelFactory(
    private val repository: ArtworkRepository,
    private val backupManager: BackupManager,
    private val inspirationRepository: InspirationRepository,
    /** Artlog 2.0 (minimal v5): the stage-2 website ViewModel branch
     * and the stage-3 read-only legacy-favorites ViewModel branch.
     * Next iteration stage 4: the same ViewModel also owns website
     * overrides and directory categories. */
    private val websiteFavoriteRepository: WebsiteFavoriteRepository,
    private val websiteOverrideRepository: WebsiteOverrideRepository,
    private val directoryCategoryRepository: DirectoryCategoryRepository,
    /** Next iteration (stages 5-7): music player ViewModel branch. */
    private val musicTrackRepository: MusicTrackRepository,
    private val musicImport: MusicImport,
    private val appearanceSettings: AppearanceSettings,
    /** P0: Application-scoped playback state (controller + lyrics). */
    private val musicPlaybackState: MusicPlaybackState,
    context: Context
) : ViewModelProvider.Factory {

    /** Application context only (never an Activity). Kept for
     * ViewModels that need it; no current ViewModel branch uses it. */
    private val appContext: Context = context.applicationContext

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return when (modelClass) {
            HomeViewModel::class.java -> HomeViewModel(repository)
            GalleryViewModel::class.java -> GalleryViewModel(repository)
            AddViewModel::class.java -> AddViewModel(repository, null)
            JournalViewModel::class.java -> JournalViewModel(repository)
            StatisticsViewModel::class.java -> StatisticsViewModel(repository)
            TagViewModel::class.java -> TagViewModel(repository)
            BackupViewModel::class.java -> BackupViewModel(backupManager)
            VisualInspirationViewModel::class.java ->
                VisualInspirationViewModel(
                    websiteFavoriteRepository,
                    websiteOverrideRepository,
                    directoryCategoryRepository,
                )
            LegacyFavoritesViewModel::class.java ->
                LegacyFavoritesViewModel(inspirationRepository)
            MusicViewModel::class.java ->
                MusicViewModel(
                    musicPlaybackState, musicImport, appearanceSettings,
                    CoverBackfill(
                        appContext, musicTrackRepository,
                        musicImport, appearanceSettings
                    )
                )
            DetailViewModel::class.java ->
                throw IllegalArgumentException(
                    "DetailViewModel needs an artwork id; use detailFactory() instead"
                )
            else -> throw IllegalArgumentException("Unknown ViewModel: $modelClass")
        } as T
    }

    /**
     * Factory for a detail screen bound to one artwork, plus the
     * ordered id list of the list the user came in from (P2 gallery:
     * prev/next walks this list, never the whole library). An empty
     * list means "opened without a list" — prev/next are unavailable.
     */
    fun detailFactory(
        artworkId: String,
        artworkIds: List<String> = emptyList()
    ): ViewModelProvider.Factory =
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                if (modelClass == DetailViewModel::class.java) {
                    return DetailViewModel(repository, artworkId, artworkIds) as T
                }
                throw IllegalArgumentException("Unknown ViewModel: $modelClass")
            }
        }

    /** Factory for the Add screen in edit mode (existing artwork). */
    fun editFactory(artworkId: String): ViewModelProvider.Factory =
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                if (modelClass == AddViewModel::class.java) {
                    return AddViewModel(repository, artworkId) as T
                }
                throw IllegalArgumentException("Unknown ViewModel: $modelClass")
            }
        }
}
