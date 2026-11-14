package com.artlog.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.artlog.app.data.ArtworkDatabase
import com.artlog.app.data.ArtworkRepository
import com.artlog.app.data.ImageRetryInterceptor
import com.artlog.app.data.InspirationImageEventListener
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Manual dependency holder (round 2). No DI framework is introduced yet;
 * the repository is created once here and passed down to the UI.
 */
class ArtlogApplication : Application(), ImageLoaderFactory {
    override fun onCreate() {
        // Crash reporter first, before anything else can fail: without a
        // stack trace we cannot fix a crash we cannot reproduce.
        CrashReporter.install(this)
        super.onCreate()
    }

    /**
     * Round-5 optimization: one shared, tuned Coil loader for the
     * whole app (inspiration feed + local artwork images).
     * Memory cache 25%, disk cache 256 MB, crossfade on, one limited
     * retry for network image loads, remote-load stats for the
     * debug panel (see data/CoilConfig.kt).
     */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.25).build() }
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDir.resolve("coil_image_cache"))
                .maxSizeBytes(256L * 1024 * 1024)
                .build()
        }
        .crossfade(true)
        .okHttpClient {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                // 5.1.2 P0-3: cap the total time of one image call
                // (including the one limited retry) at 30s. 5.1.1
                // measured the worst case without it at 40s+ of a
                // queue slot held by a single slow image; the UI
                // already offers tap-to-retry, so a 30s image is not
                // worth its slot. Concurrency is deliberately left at
                // OkHttp's default (5 per host): the 800px list thumbs
                // cut per-image bytes ~3x, which drains the queue
                // faster without asking a public-good CDN for more
                // parallel load. See README "第五轮 5.1.2" for the
                // full Coil assessment.
                .callTimeout(30, TimeUnit.SECONDS)
                .build()
        }
        .eventListener(InspirationImageEventListener())
        .components { add(ImageRetryInterceptor()) }
        .build()

    val database: ArtworkDatabase by lazy { ArtworkDatabase.get(this) }
    val repository: ArtworkRepository by lazy {
        ArtworkRepository(this, database.artworkDao(), database.archiveDao())
    }

    /** Round 4: local backup / restore / integrity checking. */
    val backupManager: com.artlog.app.data.BackupManager by lazy {
        com.artlog.app.data.BackupManager(
            this, database, database.artworkDao(), database.archiveDao(),
            database.inspirationDao(), database.websiteFavoriteDao(),
            database.websiteOverrideDao(), database.directoryCategoryDao(),
            database.musicTrackDao()
        )
    }

    /** Round 5: global inspiration feed, cache and favorites. */
    val inspirationRepository: com.artlog.app.data.InspirationRepository by lazy {
        com.artlog.app.data.InspirationRepository(database.inspirationDao())
    }

    /** Artlog 2.0 (minimal v5): website favorites (official + custom). */
    val websiteFavoriteRepository: com.artlog.app.data.WebsiteFavoriteRepository by lazy {
        com.artlog.app.data.WebsiteFavoriteRepository(database.websiteFavoriteDao())
    }

    /** Next iteration (database v6): user overrides of official directory sites. */
    val websiteOverrideRepository: com.artlog.app.data.WebsiteOverrideRepository by lazy {
        com.artlog.app.data.WebsiteOverrideRepository(database.websiteOverrideDao())
    }

    /** Next iteration (database v6): directory categories (builtin + custom). */
    val directoryCategoryRepository: com.artlog.app.data.DirectoryCategoryRepository by lazy {
        com.artlog.app.data.DirectoryCategoryRepository(database.directoryCategoryDao())
    }

    /** Next iteration (database v6): local music library metadata. */
    val musicTrackRepository: com.artlog.app.data.MusicTrackRepository by lazy {
        com.artlog.app.data.MusicTrackRepository(database.musicTrackDao())
    }

    /** Next iteration (stages 5-7): music import (SAF copy + metadata + lyrics). */
    val musicImport: com.artlog.app.data.MusicImport by lazy {
        com.artlog.app.data.MusicImport(this, musicTrackRepository)
    }

    /** Next iteration (stage 8): appearance settings incl. the lyrics switch. */
    val appearanceSettings: com.artlog.app.data.AppearanceSettings by lazy {
        com.artlog.app.data.AppearanceSettings(this)
    }

    /**
     * P0 global lyrics: the single Application-scoped playback state
     * holder (MediaController + playback/lyrics state). Lives as long
     * as the process; the music page closing never clears it. The
     * floating lyrics overlay reads the same instance.
     */
    val musicPlaybackState: com.artlog.app.playback.MusicPlaybackState by lazy {
        com.artlog.app.playback.MusicPlaybackState(
            musicTrackRepository, appearanceSettings, this
        )
    }
}
