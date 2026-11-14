package com.artlog.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

private val ArtlogColorScheme = darkColorScheme(
    primary = ArtlogAccent,
    onPrimary = ArtlogBackground,
    secondary = ArtlogTextSecondary,
    onSecondary = ArtlogBackground,
    tertiary = ArtlogTextMuted,
    background = ArtlogBackground,
    onBackground = ArtlogTextPrimary,
    surface = ArtlogSurface,
    onSurface = ArtlogTextPrimary,
    surfaceVariant = ArtlogSurfaceElevated,
    onSurfaceVariant = ArtlogTextSecondary,
    surfaceContainer = ArtlogSurface,
    surfaceContainerHigh = ArtlogSurfaceElevated,
    outline = ArtlogBorder,
    outlineVariant = ArtlogBorder,
    error = ArtlogDanger,
    onError = ArtlogBackground
)

/**
 * The density the app started with, before [ArtlogTheme] applies the
 * global text-size scale. Lyrics surfaces that have their own font-size
 * key (in-app waterfall lyrics, in-app floating lyrics) read this to
 * opt out of the global scale: see [UnscaledDensity].
 */
val LocalUnscaledDensity = compositionLocalOf { Density(1f) }

/**
 * Artlog dark theme. Always dark; there is no light variant.
 *
 * P2 stage 5 — global text size / colour ([AppearanceSettings]
 * `text_size_scale` / `text_color`, defaults 1.0 / theme default):
 *
 * - Size: the theme provides a [LocalDensity] whose `fontScale` is the
 *   system font scale multiplied by [textScale]. Every `.sp` value in
 *   the app — the [ArtlogTypography] steps and the ~260 hard-coded
 *   `fontSize = …sp` sites on every page — is converted through that
 *   density, so the whole UI follows the setting with no per-site
 *   rewrite and no layout-structure change. `dp` sizes (spacing,
 *   images, card heights) are untouched, so nothing is re-laid-out by
 *   the scale itself; long text still wraps / ellipsises as before.
 * - Colour: when [textColor] is non-null, the theme overrides the
 *   text-facing slots — `onSurface` / `onBackground` take the colour
 *   itself, `onSurfaceVariant` takes it at 62% alpha (secondary text)
 *   and `tertiary` at 45% (muted text) — and the same mapping is
 *   applied to the [ArtlogTypography] style colours, because most
 *   `Text` calls take their colour from the style, not the scheme.
 *   Semantic colours (error, success, accent, surfaces, borders)
 *   are deliberately NOT overridden. A null [textColor] means the
 *   `text_color` key is absent and the default palette applies.
 *
 * Surfaces that must NOT follow the global scale wrap themselves in
 * [UnscaledDensity] and apply their own size: the in-page waterfall
 * lyrics (`lyrics_font_size`) and the in-app floating lyrics
 * (`floating_lyrics_font_size`, white text with shadow over any
 * background). The system-level floating lyrics live in a separate
 * WindowManager view outside this Compose tree and are unaffected.
 */
@Composable
fun ArtlogTheme(
    textScale: Float = 1f,
    textColor: Color? = null,
    content: @Composable () -> Unit
) {
    val baseDensity = LocalDensity.current
    val scaledDensity = remember(baseDensity, textScale) {
        Density(
            density = baseDensity.density,
            fontScale = baseDensity.fontScale * textScale
        )
    }
    val colorScheme = remember(textColor) {
        if (textColor == null) {
            ArtlogColorScheme
        } else {
            ArtlogColorScheme.copy(
                onSurface = textColor,
                onBackground = textColor,
                onSurfaceVariant = textColor.copy(alpha = 0.62f),
                tertiary = textColor.copy(alpha = 0.45f)
            )
        }
    }
    val typography = remember(textColor) {
        if (textColor == null) {
            ArtlogTypography
        } else {
            ArtlogTypography.copy(
                headlineLarge = ArtlogTypography.headlineLarge.copy(color = textColor),
                headlineMedium = ArtlogTypography.headlineMedium.copy(color = textColor),
                titleLarge = ArtlogTypography.titleLarge.copy(color = textColor),
                titleMedium = ArtlogTypography.titleMedium.copy(color = textColor),
                bodyLarge = ArtlogTypography.bodyLarge.copy(color = textColor),
                bodyMedium = ArtlogTypography.bodyMedium.copy(
                    color = textColor.copy(alpha = 0.62f)
                ),
                bodySmall = ArtlogTypography.bodySmall.copy(
                    color = textColor.copy(alpha = 0.5f)
                ),
                labelSmall = ArtlogTypography.labelSmall.copy(
                    color = textColor.copy(alpha = 0.5f)
                )
            )
        }
    }
    CompositionLocalProvider(
        LocalDensity provides scaledDensity,
        LocalUnscaledDensity provides baseDensity
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = typography,
            content = content
        )
    }
}

/**
 * Run [content] with the density the app started with, ignoring the
 * global text-size scale from [ArtlogTheme]. For lyrics surfaces with
 * their own font-size key only — not a general escape hatch.
 */
@Composable
fun UnscaledDensity(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalDensity provides LocalUnscaledDensity.current
    ) {
        content()
    }
}
