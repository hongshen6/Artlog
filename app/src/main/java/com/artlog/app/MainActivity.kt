package com.artlog.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import com.artlog.app.data.AppearanceLogic
import com.artlog.app.data.AppearanceState
import com.artlog.app.data.ResolvedBackground
import com.artlog.app.playback.ExternalLyricsService
import com.artlog.app.ui.ArtlogApp
import com.artlog.app.ui.music.FloatingLyricsOverlay
import com.artlog.app.ui.theme.ArtlogTheme
import com.artlog.app.ui.theme.UnscaledDensity
import java.io.File

class MainActivity : ComponentActivity() {
    /**
     * P0 验收修复轮：回到前台时复查系统悬浮窗权限与开关
     * （ExternalLyricsService.sync）：用户在系统设置中撤销权限
     * 后回到 App，服务会被停掉、开关状态同步；服务被系统回收
     * 后回到 App，若开关仍开、权限仍在，即重新拉起。
     */
    override fun onResume() {
        super.onResume()
        ExternalLyricsService.sync(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Belt and braces with ArtlogApplication.onCreate: also cover
        // crashes during activity instantiation itself.
        CrashReporter.install(applicationContext)
        super.onCreate(savedInstanceState)
        // 2026-11-14 nav transparency: edge-to-edge so the custom
        // background extends behind the system bars — the status bar
        // and the gesture navigation area — instead of stopping at a
        // black system bar. Both bars are transparent with light icons
        // (dark style), matching the app's always-dark theme; no scrim
        // is added. M3 Scaffold + NavigationBar consume the system-bar
        // insets themselves, so page content and the five nav buttons
        // stay above the gesture area while the background shows
        // through to the screen edge. (android.graphics.Color is
        // fully qualified: SystemBarStyle takes an ARGB Int scrim,
        // and the Compose Color import would clash.)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        val app = application as ArtlogApplication
        val repository = app.repository
        val backupManager = app.backupManager
        val inspirationRepository = app.inspirationRepository
        val websiteFavoriteRepository = app.websiteFavoriteRepository
        val websiteOverrideRepository = app.websiteOverrideRepository
        val directoryCategoryRepository = app.directoryCategoryRepository
        val musicTrackRepository = app.musicTrackRepository
        val musicImport = app.musicImport
        val appearanceSettings = app.appearanceSettings
        val musicPlaybackState = app.musicPlaybackState
        setContent {
            val appearanceState by appearanceSettings.state.collectAsState()
            // P2 stage 5: the global text size / colour settings ride
            // the theme (ArtlogTheme scales LocalDensity.fontScale and
            // overrides the text-facing scheme/typography colours), so
            // every page follows them with no per-page rewrite.
            ArtlogTheme(
                textScale = appearanceState.textSizeScale,
                textColor = appearanceState.textColor?.let { Color(it) }
            ) {
                // The background layer lives here — and only here. It
                // covers every page (Home, Gallery, Add, Journal,
                // Statistics, Settings, Visual Inspiration) because it
                // sits behind the whole app shell; no page draws its
                // own background. The Surface and the Scaffold inside
                // ArtlogApp are transparent so the background shows
                // through; cards, dialogs and artwork images keep their
                // own opaque surfaces. The bottom navigation bar is
                // transparent too (2026-11-14), and the window is
                // edge-to-edge, so the background extends behind the
                // system bars to the screen edge.
                Box(modifier = Modifier.fillMaxSize()) {
                    AppearanceBackground(state = appearanceState)
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = Color.Transparent
                    ) {
                        ArtlogApp(
                            repository = repository,
                            backupManager = backupManager,
                            inspirationRepository = inspirationRepository,
                            websiteFavoriteRepository = websiteFavoriteRepository,
                            websiteOverrideRepository = websiteOverrideRepository,
                            directoryCategoryRepository = directoryCategoryRepository,
                            musicTrackRepository = musicTrackRepository,
                            musicImport = musicImport,
                            appearanceSettings = appearanceSettings,
                            musicPlaybackState = musicPlaybackState,
                        )
                    }
                    // P0: in-app floating lyrics — the only app-wide
                    // floating layer besides the background. Sits above
                    // every page (incl. overlays), below nothing that
                    // needs input: the layer itself passes touches
                    // through except its small buttons (hide, transport,
                    // lock, font size) and its drag gesture when
                    // unlocked (P2 stage 4), and never consumes the
                    // system back key.
                    // P2 stage 5: the in-app floating lyrics keep their
                    // own rendering (white text with shadow over any
                    // background, own font-size key) and must NOT follow
                    // the global text scale — UnscaledDensity restores
                    // the density the app started with for this layer.
                    UnscaledDensity {
                        FloatingLyricsOverlay(
                            holder = musicPlaybackState,
                            appearanceSettings = appearanceSettings
                        )
                    }
                }
            }
        }
    }
}

/**
 * The single background layer (stage 8). Solid colour, or a
 * full-screen image with a dark scrim on top (opacity from
 * `background_scrim_alpha`). The image is loaded with the existing
 * Coil configuration (File model, no config change) and is not counted
 * in ImageLoadStats (that only counts remote loads). A missing or
 * unloadable image silently falls back to the solid colour — no crash,
 * no error popup.
 */
@Composable
private fun AppearanceBackground(state: AppearanceState) {
    val resolved = remember(
        state.mode, state.presetId, state.customColor, state.imagePath
    ) {
        AppearanceLogic.resolveBackground(state)
    }
    when (resolved) {
        is ResolvedBackground.Solid -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(resolved.argb))
            )
        }
        is ResolvedBackground.Image -> {
            val file = remember(resolved.file.absolutePath) { File(resolved.file.absolutePath) }
            var imageFailed by remember(file.absolutePath) { mutableStateOf(false) }
            if (imageFailed) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(AppearanceLogic.presetColor(state.presetId)))
                )
            } else {
                Box(modifier = Modifier.fillMaxSize()) {
                    AsyncImage(
                        model = file,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        onError = { imageFailed = true },
                        modifier = Modifier.fillMaxSize()
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = state.scrimAlpha))
                    )
                }
            }
        }
    }
}
