package com.artlog.app.data

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import com.artlog.app.playback.MusicRepeatMode
import com.artlog.app.playback.PlaybackModeLogic
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Personalized background & appearance settings (stage 8).
 *
 * Storage: a single SharedPreferences file — no DataStore, no database
 * table. The key names below are the contract with the backup module,
 * which reads/writes them as `settings.json` (see implementation-plan
 * §2.5); do not rename them.
 *
 * Keys:
 * - `background_mode`: String, COLOR_PRESET / COLOR_CUSTOM / IMAGE,
 *   default COLOR_PRESET.
 * - `background_preset`: String, preset id, default [AppearanceLogic.DEFAULT_PRESET_ID]
 *   (the existing dark background colour).
 * - `background_custom_color`: Long, ARGB.
 * - `background_image_path`: String?, absolute path, nullable.
 * - `background_scrim_alpha`: Float, default 0.45, range 0–0.6.
 * - `lyrics_enabled`: Boolean, default false. The key is defined here
 *   so the lyrics stage can use it; this stage builds no lyrics UI.
 *   P0 (global lyrics): `lyrics_enabled` is reused as the master
 *   lyrics switch (off = no lyrics anywhere); new keys below are the
 *   floating-lyrics switches and the P1 handover fields. Only the
 *   master switch and the in-app floating switch are wired in P0;
 *   the external switch + position / size / lock are wired by the
 *   P0 acceptance-fix round (system overlay, see
 *   ExternalLyricsService); the lockscreen switch stays defined
 *   with defaults only (P3 wires it), and position / size / lock
 *   for the in-app overlay are wired by P2 stage 4 (see
 *   FloatingLyricsOverlay).
 *
 * Keys (P0):
 * - `floating_lyrics_enabled`: Boolean, default false (opt-in: no
 *   floating layer appears after upgrade until the user turns it on).
 * - `external_lyrics_enabled`: Boolean, default false (P2, not wired).
 * - `lockscreen_lyrics_enabled`: Boolean, default false (P3, not wired).
 * - `floating_lyrics_locked`: Boolean, default false (P2 stage 4 wired).
 * - `floating_lyrics_x` / `floating_lyrics_y`: Float, normalized
 *   0-1, defaults 0.5 / 0.78 (P2 stage 4 drag target).
 * - `floating_lyrics_font_size`: Float, sp, default 16 (P2 stage 4 wired).
 *
 * Keys (P0 验收修复轮：系统级悬浮歌词，独立键，不共用 floating_*):
 * - `external_lyrics_x` / `external_lyrics_y`: Float, normalized
 *   0-1, defaults 0.5 / 0.78 (system overlay drag target).
 * - `external_lyrics_font_size`: Float, sp, default 16.
 * - `external_lyrics_locked`: Boolean, default false.
 *
 * Keys (P1 播放模式：循环档位 + 随机开关，见 PlaybackModeLogic):
 * - `repeat_mode`: String, 枚举名 ORDER / ALL / SINGLE,
 *   default ORDER（非法值回落 ORDER）。
 * - `shuffle_enabled`: Boolean, default false（只存开关；洗牌序列
 *   是内存态，重启后重新洗牌，不伪造续播上一轮）。
 *
 * Keys (P2 首页：首页标题自定义):
 * - `home_title`: String, 用户自定义首页标题；缺省（键不存在或
 *   为空）即默认标题 [HomeTitleLogic.DEFAULT_TITLE]。恢复默认 =
 *   删除该键。校验见 [HomeTitleLogic]（最多 20 字符，按
 *   String.length 计；空白不许保存）。
 *
 * Keys (P2 阶段五：全局文字大小 / 颜色 + 页内歌词字号):
 * - `text_size_scale`: Float, 全局文字缩放系数，默认 1.0，范围
 *   0.85–1.3；由 [ArtlogTheme] 经 LocalDensity.fontScale 应用到
 *   全部 `.sp` 文字（含字阶与各页硬编码字号），不改布局结构。
 * - `text_color`: Long, ARGB；键缺省即主题默认文字色。作用于主
 *   文字与次文字（主题层覆盖 colorScheme 与字阶颜色）；错误色、
 *   成功色等语义色不受影响。恢复默认 = 删除该键。
 * - `lyrics_font_size`: Float, sp，默认 18，范围 10–32；只管页内
 *   瀑布歌词当前行字号（相邻行按 0.875 比例缩小），不跟随
 *   全局 `text_size_scale`；内外悬浮歌词各有自己的字号键，不动。
 *
 * Key（最终完善轮：旧曲目封面一次性回填）：
 * - `cover_backfill_done`: Boolean，默认 false。升级后首次进入
 *   音乐流程时，[CoverBackfill] 对 coverPath 为空的已有曲目跑
 *   一次内嵌封面提取，跑完（无论个别曲目成败）置 true，只跑一
 *   次。恢复场景一致性：封面文件本身进备份，标志位随
 *   settings.json 一同恢复——v7 备份恢复后两者一致（有封面且
 *   标志 true）；v6 备份恢复后标志缺席即 false，回填会再跑一
 *   次（幂等，只处理 coverPath 为空的曲目）。本键不是外观设置，
 *   不进 resetToDefault（外观重置不应重跑数据迁移类的一次性任务）。
 *
 * Background images are picked by the user (system picker), copied
 * byte-for-byte into `filesDir/Artlog/background/` — only one file is
 * kept, replacing it overwrites the old one — and the original is never
 * moved or deleted. Nothing scans the gallery or downloads images.
 */
class AppearanceSettings(context: Context) {

    companion object {
        const val PREFS_NAME = "artlog_appearance"

        const val KEY_BACKGROUND_MODE = "background_mode"
        const val KEY_BACKGROUND_PRESET = "background_preset"
        const val KEY_BACKGROUND_CUSTOM_COLOR = "background_custom_color"
        const val KEY_BACKGROUND_IMAGE_PATH = "background_image_path"
        const val KEY_BACKGROUND_SCRIM_ALPHA = "background_scrim_alpha"
        const val KEY_LYRICS_ENABLED = "lyrics_enabled"

        /** P0: in-app floating lyrics switch (opt-in, default false). */
        const val KEY_FLOATING_LYRICS_ENABLED = "floating_lyrics_enabled"
        /** P2: system-level floating lyrics switch (defined only, not wired). */
        const val KEY_EXTERNAL_LYRICS_ENABLED = "external_lyrics_enabled"
        /** P3: lockscreen lyrics switch (defined only, not wired). */
        const val KEY_LOCKSCREEN_LYRICS_ENABLED = "lockscreen_lyrics_enabled"
        /** P2 stage 4: floating lyrics position lock (wired in FloatingLyricsOverlay). */
        const val KEY_FLOATING_LYRICS_LOCKED = "floating_lyrics_locked"
        /** P2 stage 4: floating lyrics position, normalized 0–1 (drag target). */
        const val KEY_FLOATING_LYRICS_X = "floating_lyrics_x"
        const val KEY_FLOATING_LYRICS_Y = "floating_lyrics_y"
        /** P2 stage 4: floating lyrics font size in sp (A− / A＋). */
        const val KEY_FLOATING_LYRICS_FONT_SIZE = "floating_lyrics_font_size"
        /** P0 验收修复轮：系统级悬浮歌词位置锁定（独立键，默认 false）。 */
        const val KEY_EXTERNAL_LYRICS_LOCKED = "external_lyrics_locked"
        /** P0 验收修复轮：系统级悬浮歌词位置，归一化 0–1（独立键）。 */
        const val KEY_EXTERNAL_LYRICS_X = "external_lyrics_x"
        const val KEY_EXTERNAL_LYRICS_Y = "external_lyrics_y"
        /** P0 验收修复轮：系统级悬浮歌词字号，sp（独立键）。 */
        const val KEY_EXTERNAL_LYRICS_FONT_SIZE = "external_lyrics_font_size"
        /** P1：循环档位，存 MusicRepeatMode 枚举名，默认 ORDER。 */
        const val KEY_REPEAT_MODE = "repeat_mode"
        /** P1：随机开关，默认 false（只存开关，不存洗牌序列）。 */
        const val KEY_SHUFFLE_ENABLED = "shuffle_enabled"
        /** P2 首页：自定义首页标题（String；缺省即默认标题）。 */
        const val KEY_HOME_TITLE = "home_title"
        /** P2 阶段五：全局文字缩放系数（Float；默认 1.0，范围 0.85–1.3）。 */
        const val KEY_TEXT_SIZE_SCALE = "text_size_scale"
        /** P2 阶段五：全局文字颜色（Long, ARGB；键缺省即主题默认）。 */
        const val KEY_TEXT_COLOR = "text_color"
        /** P2 阶段五：页内歌词字号（Float, sp；默认 22，范围 10–32）。 */
        const val KEY_LYRICS_FONT_SIZE = "lyrics_font_size"
        /** 最终完善轮：旧曲目封面一次性回填已完成（Boolean；默认 false）。 */
        const val KEY_COVER_BACKFILL_DONE = "cover_backfill_done"
        /** v26：页内歌词默认字号 18sp → 22sp 的一次性迁移已跑完
         * （Boolean；默认 false）。内部一次性标记，非用户设置项：
         * 不进设置界面、不进备份白名单（备份校验脚本按 24 键白名单
         * 硬编码，不可扩），只用于保证本迁移在同一设备上只跑一次。 */
        const val KEY_LYRICS_FONT_SIZE_DEFAULT_MIGRATED =
            "lyrics_font_size_default_migrated"

        /** Single background image directory under the app-private files dir. */
        const val BACKGROUND_DIR = "Artlog/background"
    }

    private val appContext = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(readSnapshot())
    /** Current settings; updated whenever any key changes, including
     * writes from another [AppearanceSettings] instance. */
    val state: StateFlow<AppearanceState> = _state.asStateFlow()

    private val listener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> refresh() }

    init {
        prefs.registerOnSharedPreferenceChangeListener(listener)
        // v26：页内歌词默认字号 18sp → 22sp 的一次性迁移（见
        // migrateLyricsFontSizeDefault）。迁移后显式 refresh()：
        // _state 在属性初始化时已按迁移前快照取值，迁移改写了
        // prefs，必须重读一次，音乐页读到的才是迁移后的字号。
        migrateLyricsFontSizeDefault()
        refresh()
    }

    /** Read the current settings straight from SharedPreferences. */
    fun snapshot(): AppearanceState = readSnapshot()

    fun setMode(mode: BackgroundMode) {
        prefs.edit().putString(KEY_BACKGROUND_MODE, mode.name).apply()
    }

    fun setPreset(presetId: String) {
        val id = if (AppearanceLogic.presetById(presetId) != null) presetId
            else AppearanceLogic.DEFAULT_PRESET_ID
        prefs.edit().putString(KEY_BACKGROUND_PRESET, id).apply()
    }

    fun setCustomColor(argb: Long) {
        prefs.edit().putLong(KEY_BACKGROUND_CUSTOM_COLOR, argb).apply()
    }

    fun setScrimAlpha(alpha: Float) {
        prefs.edit()
            .putFloat(KEY_BACKGROUND_SCRIM_ALPHA, AppearanceLogic.clampScrimAlpha(alpha))
            .apply()
    }

    fun setLyricsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_LYRICS_ENABLED, enabled).apply()
    }

    /** P0 wired: in-app floating lyrics switch. */
    fun setFloatingLyricsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_FLOATING_LYRICS_ENABLED, enabled).apply()
    }

    /** P2 handover (not wired in P0): system-level floating switch. */
    fun setExternalLyricsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_EXTERNAL_LYRICS_ENABLED, enabled).apply()
    }

    /** P3 handover (not wired in P0): lockscreen lyrics switch. */
    fun setLockscreenLyricsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_LOCKSCREEN_LYRICS_ENABLED, enabled).apply()
    }

    /** P2 stage 4: floating lyrics position lock (wired in FloatingLyricsOverlay). */
    fun setFloatingLyricsLocked(locked: Boolean) {
        prefs.edit().putBoolean(KEY_FLOATING_LYRICS_LOCKED, locked).apply()
    }

    /** P2 stage 4: normalized position (clamped 0–1), written on drag end. */
    fun setFloatingLyricsPosition(x: Float, y: Float) {
        val (cx, cy) = FloatingLyricsLogic.clampPosition(x, y)
        prefs.edit()
            .putFloat(KEY_FLOATING_LYRICS_X, cx)
            .putFloat(KEY_FLOATING_LYRICS_Y, cy)
            .apply()
    }

    /** P2 stage 4: floating lyrics font size (sp), A− / A＋ step 2sp. */
    fun setFloatingLyricsFontSize(sp: Float) {
        prefs.edit().putFloat(KEY_FLOATING_LYRICS_FONT_SIZE, sp.coerceIn(10f, 32f)).apply()
    }

    /** P0 验收修复轮：系统级悬浮歌词位置锁定。 */
    fun setExternalLyricsLocked(locked: Boolean) {
        prefs.edit().putBoolean(KEY_EXTERNAL_LYRICS_LOCKED, locked).apply()
    }

    /** P0 验收修复轮：系统级悬浮歌词归一化位置（钳制 0–1，与内部悬浮分开存）。 */
    fun setExternalLyricsPosition(x: Float, y: Float) {
        val (cx, cy) = FloatingLyricsLogic.clampPosition(x, y)
        prefs.edit()
            .putFloat(KEY_EXTERNAL_LYRICS_X, cx)
            .putFloat(KEY_EXTERNAL_LYRICS_Y, cy)
            .apply()
    }

    /** P0 验收修复轮：系统级悬浮歌词字号（sp，钳制 10–32）。 */
    fun setExternalLyricsFontSize(sp: Float) {
        prefs.edit().putFloat(KEY_EXTERNAL_LYRICS_FONT_SIZE, sp.coerceIn(10f, 32f)).apply()
    }

    /** P1：循环档位（存枚举名；非法值由读侧回落 ORDER）。 */
    fun setRepeatMode(mode: MusicRepeatMode) {
        prefs.edit().putString(KEY_REPEAT_MODE, mode.name).apply()
    }

    /** P1：随机开关（只存开关；洗牌序列是内存态，不持久化）。 */
    fun setShuffleEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SHUFFLE_ENABLED, enabled).apply()
    }

    /**
     * P2 首页：保存自定义标题。只接受 [HomeTitleLogic.isSaveable] 为
     * 真的文本（非空白且 ≤20 字符），存入前去首尾空白；不合法的输入
     * 直接忽略，不写键、不改现有标题。
     */
    fun setHomeTitle(title: String) {
        if (!HomeTitleLogic.isSaveable(title)) return
        prefs.edit().putString(KEY_HOME_TITLE, title.trim()).apply()
    }

    /** P2 首页：恢复默认标题 = 删除该键，读取自然回落到默认值。 */
    fun clearHomeTitle() {
        prefs.edit().remove(KEY_HOME_TITLE).apply()
    }

    /** P2 阶段五：全局文字缩放系数（钳制 0.85–1.3，见 AppearanceLogic）。 */
    fun setTextSizeScale(scale: Float) {
        prefs.edit()
            .putFloat(KEY_TEXT_SIZE_SCALE, AppearanceLogic.clampTextSizeScale(scale))
            .apply()
    }

    /** P2 阶段五：全局文字颜色（ARGB Long；写后立即经主题层生效）。 */
    fun setTextColor(argb: Long) {
        prefs.edit().putLong(KEY_TEXT_COLOR, argb).apply()
    }

    /** P2 阶段五：恢复默认文字颜色 = 删除该键，读取回落到主题默认。 */
    fun clearTextColor() {
        prefs.edit().remove(KEY_TEXT_COLOR).apply()
    }

    /** P2 阶段五：页内歌词字号（sp，钳制 10–32，见 AppearanceLogic）。 */
    fun setLyricsFontSize(sp: Float) {
        prefs.edit()
            .putFloat(KEY_LYRICS_FONT_SIZE, AppearanceLogic.clampLyricsFontSize(sp))
            .apply()
    }

    /** 最终完善轮：旧曲目封面一次性回填是否已跑完。 */
    fun isCoverBackfillDone(): Boolean =
        prefs.getBoolean(KEY_COVER_BACKFILL_DONE, false)

    /** 最终完善轮：标记旧曲目封面一次性回填已跑完（只跑一次）。 */
    fun markCoverBackfillDone() {
        prefs.edit().putBoolean(KEY_COVER_BACKFILL_DONE, true).apply()
    }

    /**
     * v26：页内歌词默认字号 18sp → 22sp 的一次性迁移（只跑一次）。
     *
     * 背景：`lyrics_font_size` 键缺省时读默认值；启动时从不写该键，
     * 它只在用户拖动设置页滑杆、点“恢复默认外观”或恢复备份时才
     * 会写入。所以只改默认值的话，设备上若已有存值恰好等于旧默认
     * 值 18f，新默认值会被挡住、用户看不到 22sp。
     *
     * 处理（侵入最小）：存值恰好等于旧默认值 18f 时删键，让新默认
     * 值 22sp 生效；存值 ≠ 18f（用户主动调过的自定义字号）一律
     * 不动；键缺省时新默认值直接生效，无须处理。跑一次后立标记，
     * 此后用户再把滑杆拖到 18sp 也不会被再次迁移。标记键是内部
     * 一次性 bookkeeping，不进备份白名单、不清空任何用户设置。
     * 注意：存值 18f 无法区分“恢复默认写入”与“用户恰好拖到 18sp”，
     * 后一种极小概率情况会被一并迁移到 22sp（用户可在设置里再拖
     * 回 18sp，迁移标记已立，不会再被改动）。
     */
    private fun migrateLyricsFontSizeDefault() {
        if (prefs.getBoolean(KEY_LYRICS_FONT_SIZE_DEFAULT_MIGRATED, false)) return
        if (prefs.contains(KEY_LYRICS_FONT_SIZE)) {
            val stored = prefs.getFloat(
                KEY_LYRICS_FONT_SIZE, AppearanceLogic.DEFAULT_LYRICS_FONT_SIZE
            )
            if (stored == AppearanceLogic.PREVIOUS_DEFAULT_LYRICS_FONT_SIZE) {
                prefs.edit().remove(KEY_LYRICS_FONT_SIZE).apply()
            }
        }
        prefs.edit().putBoolean(KEY_LYRICS_FONT_SIZE_DEFAULT_MIGRATED, true).apply()
    }

    /**
     * Copy a user-picked image into the private background directory and
     * activate it (mode IMAGE). Only one background file is kept: the
     * copy first lands in a temp file, then any old background file is
     * removed and the temp file is renamed in. The picked original is
     * never moved or deleted. Returns false (without changing the
     * current background) when the copy fails. Must be called from a
     * coroutine; the copy itself runs on Dispatchers.IO.
     */
    suspend fun importBackgroundImage(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        val dir = File(appContext.filesDir, BACKGROUND_DIR).apply { mkdirs() }
        val extension = extensionFor(appContext.contentResolver.getType(uri))
        val tempFile = File(dir, "background.tmp.$extension")
        val finalFile = File(dir, "background.$extension")
        try {
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output -> input.copyTo(output) }
            } ?: return@withContext false
            if (tempFile.length() == 0L) {
                tempFile.delete()
                return@withContext false
            }
            // Only one background image is kept: drop the previous file(s)
            // once the new copy is safely on disk, then promote the temp file.
            dir.listFiles()?.forEach { file ->
                if (file.name.startsWith("background.") && file != tempFile) file.delete()
            }
            if (!tempFile.renameTo(finalFile)) {
                tempFile.delete()
                return@withContext false
            }
            prefs.edit()
                .putString(KEY_BACKGROUND_IMAGE_PATH, finalFile.absolutePath)
                .putString(KEY_BACKGROUND_MODE, BackgroundMode.IMAGE.name)
                .apply()
            true
        } catch (_: Exception) {
            tempFile.delete()
            false
        }
    }

    /** Delete the background image file (if any), clear its path and
     * fall back to the preset colour mode. The picked original is
     * untouched — only the private copy is removed. */
    fun removeBackgroundImage() {
        backgroundFile()?.delete()
        prefs.edit()
            .remove(KEY_BACKGROUND_IMAGE_PATH)
            .putString(KEY_BACKGROUND_MODE, BackgroundMode.COLOR_PRESET.name)
            .apply()
    }

    /**
     * Reset every appearance setting (and the background image) to the
     * defaults. The lyrics switch is left alone: it belongs to the
     * lyrics stage, not to the background. P0 decision 3: all NEW
     * lyrics / floating keys ARE reset here (the master switch
     * `lyrics_enabled` keeps its pre-existing behaviour and is not
     * reset, so a background reset never silently kills lyrics).
     * P1 decision: the two new playback-mode keys ARE reset here too
     * (repeat_mode → ORDER, shuffle_enabled → false). Reason: they
     * are playback preferences, not the master lyrics switch; reset
     * restores predictable default playback (sequential, non-shuffle),
     * the same rule P0 applied to every new key except lyrics_enabled.
     * P2 decision: the home title key is removed here too, so a
     * background reset also restores the default home title
     * (lyrics_enabled keeps its pre-existing behaviour and is not reset).
     * P2 stage 5 decision: the three new text keys ARE reset here too
     * (text_size_scale → 1.0, text_color removed → theme default,
     * lyrics_font_size → 18sp), the same rule P0/P1 applied to every
     * new key except lyrics_enabled. The "恢复默认外观" button in
     * Settings calls this function; it resets appearance only, never
     * artworks, journals, tags, statistics or music.
     */
    fun resetToDefault() {
        backgroundFile()?.delete()
        prefs.edit()
            .putString(KEY_BACKGROUND_MODE, BackgroundMode.COLOR_PRESET.name)
            .putString(KEY_BACKGROUND_PRESET, AppearanceLogic.DEFAULT_PRESET_ID)
            .putLong(KEY_BACKGROUND_CUSTOM_COLOR, AppearanceLogic.DEFAULT_CUSTOM_COLOR)
            .remove(KEY_BACKGROUND_IMAGE_PATH)
            .putFloat(KEY_BACKGROUND_SCRIM_ALPHA, AppearanceLogic.DEFAULT_SCRIM_ALPHA)
            .putBoolean(KEY_FLOATING_LYRICS_ENABLED, false)
            .putBoolean(KEY_EXTERNAL_LYRICS_ENABLED, false)
            .putBoolean(KEY_LOCKSCREEN_LYRICS_ENABLED, false)
            .putBoolean(KEY_FLOATING_LYRICS_LOCKED, false)
            .putFloat(KEY_FLOATING_LYRICS_X, FloatingLyricsLogic.DEFAULT_X)
            .putFloat(KEY_FLOATING_LYRICS_Y, FloatingLyricsLogic.DEFAULT_Y)
            .putFloat(
                KEY_FLOATING_LYRICS_FONT_SIZE,
                FloatingLyricsLogic.DEFAULT_FONT_SIZE_SP
            )
            .putBoolean(KEY_EXTERNAL_LYRICS_LOCKED, false)
            .putFloat(KEY_EXTERNAL_LYRICS_X, FloatingLyricsLogic.DEFAULT_X)
            .putFloat(KEY_EXTERNAL_LYRICS_Y, FloatingLyricsLogic.DEFAULT_Y)
            .putFloat(
                KEY_EXTERNAL_LYRICS_FONT_SIZE,
                FloatingLyricsLogic.DEFAULT_FONT_SIZE_SP
            )
            .putString(KEY_REPEAT_MODE, MusicRepeatMode.ORDER.name)
            .putBoolean(KEY_SHUFFLE_ENABLED, false)
            .remove(KEY_HOME_TITLE)
            .putFloat(KEY_TEXT_SIZE_SCALE, AppearanceLogic.DEFAULT_TEXT_SIZE_SCALE)
            .remove(KEY_TEXT_COLOR)
            .putFloat(KEY_LYRICS_FONT_SIZE, AppearanceLogic.DEFAULT_LYRICS_FONT_SIZE)
            .apply()
    }

    /** The stored background image file, or null when no image is set.
     * Callers must check existence themselves — a file deleted outside
     * the app is handled by falling back to a solid colour. */
    fun backgroundFile(): File? {
        val path = prefs.getString(KEY_BACKGROUND_IMAGE_PATH, null) ?: return null
        return File(path)
    }

    private fun refresh() {
        _state.value = readSnapshot()
    }

    private fun readSnapshot(): AppearanceState {
        val rawMode = prefs.getString(KEY_BACKGROUND_MODE, null)
        val rawPreset = prefs.getString(KEY_BACKGROUND_PRESET, null)
        return AppearanceState(
            mode = AppearanceLogic.parseMode(rawMode),
            presetId = if (AppearanceLogic.presetById(rawPreset) != null) rawPreset!!
                else AppearanceLogic.DEFAULT_PRESET_ID,
            customColor = prefs.getLong(
                KEY_BACKGROUND_CUSTOM_COLOR, AppearanceLogic.DEFAULT_CUSTOM_COLOR
            ),
            imagePath = prefs.getString(KEY_BACKGROUND_IMAGE_PATH, null),
            scrimAlpha = AppearanceLogic.clampScrimAlpha(
                prefs.getFloat(KEY_BACKGROUND_SCRIM_ALPHA, AppearanceLogic.DEFAULT_SCRIM_ALPHA)
            ),
            lyricsEnabled = prefs.getBoolean(KEY_LYRICS_ENABLED, false),
            floatingLyricsEnabled = prefs.getBoolean(KEY_FLOATING_LYRICS_ENABLED, false),
            externalLyricsEnabled = prefs.getBoolean(KEY_EXTERNAL_LYRICS_ENABLED, false),
            lockscreenLyricsEnabled =
                prefs.getBoolean(KEY_LOCKSCREEN_LYRICS_ENABLED, false),
            floatingLyricsLocked = prefs.getBoolean(KEY_FLOATING_LYRICS_LOCKED, false),
            floatingLyricsX = FloatingLyricsLogic.clampCoordinate(
                prefs.getFloat(KEY_FLOATING_LYRICS_X, FloatingLyricsLogic.DEFAULT_X)
            ),
            floatingLyricsY = FloatingLyricsLogic.clampCoordinate(
                prefs.getFloat(KEY_FLOATING_LYRICS_Y, FloatingLyricsLogic.DEFAULT_Y)
            ),
            floatingLyricsFontSize = prefs.getFloat(
                KEY_FLOATING_LYRICS_FONT_SIZE, FloatingLyricsLogic.DEFAULT_FONT_SIZE_SP
            ).coerceIn(10f, 32f),
            externalLyricsLocked = prefs.getBoolean(KEY_EXTERNAL_LYRICS_LOCKED, false),
            externalLyricsX = FloatingLyricsLogic.clampCoordinate(
                prefs.getFloat(KEY_EXTERNAL_LYRICS_X, FloatingLyricsLogic.DEFAULT_X)
            ),
            externalLyricsY = FloatingLyricsLogic.clampCoordinate(
                prefs.getFloat(KEY_EXTERNAL_LYRICS_Y, FloatingLyricsLogic.DEFAULT_Y)
            ),
            externalLyricsFontSize = prefs.getFloat(
                KEY_EXTERNAL_LYRICS_FONT_SIZE, FloatingLyricsLogic.DEFAULT_FONT_SIZE_SP
            ).coerceIn(10f, 32f),
            repeatMode = PlaybackModeLogic.parseRepeatMode(
                prefs.getString(KEY_REPEAT_MODE, null)
            ),
            shuffleEnabled = prefs.getBoolean(KEY_SHUFFLE_ENABLED, false),
            homeTitle = HomeTitleLogic.displayTitle(
                prefs.getString(KEY_HOME_TITLE, null)
            ),
            textSizeScale = AppearanceLogic.clampTextSizeScale(
                prefs.getFloat(
                    KEY_TEXT_SIZE_SCALE, AppearanceLogic.DEFAULT_TEXT_SIZE_SCALE
                )
            ),
            textColor = if (prefs.contains(KEY_TEXT_COLOR)) {
                prefs.getLong(KEY_TEXT_COLOR, AppearanceLogic.DEFAULT_TEXT_COLOR_ARGB)
            } else {
                null
            },
            lyricsFontSize = AppearanceLogic.clampLyricsFontSize(
                prefs.getFloat(
                    KEY_LYRICS_FONT_SIZE, AppearanceLogic.DEFAULT_LYRICS_FONT_SIZE
                )
            )
        )
    }

    private fun extensionFor(mimeType: String?): String = when (mimeType) {
        "image/png" -> "png"
        "image/webp" -> "webp"
        "image/jpeg", "image/jpg" -> "jpg"
        else -> "jpg"
    }
}

/** Background mode: preset colour / custom colour / background image. */
enum class BackgroundMode {
    COLOR_PRESET, COLOR_CUSTOM, IMAGE
}

/** One preset background colour: low-saturation dark, in line with
 * the existing Color.kt palette. Hard-coded here by design. */
data class BackgroundPreset(
    val id: String,
    val name: String,
    /** ARGB colour as a Long (0xAARRGGBB). */
    val argb: Long
)

/** A snapshot of every appearance setting. */
data class AppearanceState(
    val mode: BackgroundMode = BackgroundMode.COLOR_PRESET,
    val presetId: String = AppearanceLogic.DEFAULT_PRESET_ID,
    val customColor: Long = AppearanceLogic.DEFAULT_CUSTOM_COLOR,
    val imagePath: String? = null,
    val scrimAlpha: Float = AppearanceLogic.DEFAULT_SCRIM_ALPHA,
    /** Master lyrics switch (lyrics_enabled), default false. */
    val lyricsEnabled: Boolean = false,
    /** P0 wired: in-app floating lyrics switch, default false (opt-in). */
    val floatingLyricsEnabled: Boolean = false,
    /** P2 handover: system-level floating switch, default false. */
    val externalLyricsEnabled: Boolean = false,
    /** P3 handover: lockscreen lyrics switch, default false. */
    val lockscreenLyricsEnabled: Boolean = false,
    /** P1 handover: floating position lock, default false. */
    val floatingLyricsLocked: Boolean = false,
    /** P1 handover: floating position, normalized 0–1. */
    val floatingLyricsX: Float = FloatingLyricsLogic.DEFAULT_X,
    val floatingLyricsY: Float = FloatingLyricsLogic.DEFAULT_Y,
    /** P1 handover: floating lyrics font size (sp). */
    val floatingLyricsFontSize: Float = FloatingLyricsLogic.DEFAULT_FONT_SIZE_SP,
    /** P0 验收修复轮：系统级悬浮歌词位置锁定，默认 false。 */
    val externalLyricsLocked: Boolean = false,
    /** P0 验收修复轮：系统级悬浮歌词位置，归一化 0–1（与内部悬浮分开）。 */
    val externalLyricsX: Float = FloatingLyricsLogic.DEFAULT_X,
    val externalLyricsY: Float = FloatingLyricsLogic.DEFAULT_Y,
    /** P0 验收修复轮：系统级悬浮歌词字号（sp）。 */
    val externalLyricsFontSize: Float = FloatingLyricsLogic.DEFAULT_FONT_SIZE_SP,
    /** P1：循环档位（repeat_mode），默认 ORDER。 */
    val repeatMode: MusicRepeatMode = MusicRepeatMode.ORDER,
    /** P1：随机开关（shuffle_enabled），默认 false。 */
    val shuffleEnabled: Boolean = false,
    /** P2 首页：自定义首页标题；缺省即默认标题。 */
    val homeTitle: String = HomeTitleLogic.DEFAULT_TITLE,
    /** P2 阶段五：全局文字缩放系数，默认 1.0（范围 0.85–1.3）。 */
    val textSizeScale: Float = AppearanceLogic.DEFAULT_TEXT_SIZE_SCALE,
    /**
     * P2 阶段五：全局文字颜色（ARGB Long）；null = 键缺省，即主题
     * 默认文字色。注意：这里存的是“用户是否自定义过”，而不是颜色
     * 值本身；null 与“用户恰好选了默认色”在显示上相同，但恢复默认
     * 时删键即可，不必区分。
     */
    val textColor: Long? = null,
    /** P2 阶段五：页内歌词字号（sp），默认 22（范围 10–32）。 */
    val lyricsFontSize: Float = AppearanceLogic.DEFAULT_LYRICS_FONT_SIZE
)

/** What the background layer should actually draw. */
sealed interface ResolvedBackground {
    /** Solid colour (ARGB Long). */
    data class Solid(val argb: Long) : ResolvedBackground

    /** Image file to draw full-screen, with a scrim on top. */
    data class Image(val file: File) : ResolvedBackground
}

/**
 * Pure appearance logic — no Android framework calls, so it is covered
 * by the JVM test (TestAppearance). Everything here is a pure function
 * of its inputs.
 */
object AppearanceLogic {

    /** Default scrim opacity over a background image (0–0.6). */
    const val DEFAULT_SCRIM_ALPHA = 0.45f

    /** Scrim slider range. */
    const val SCRIM_ALPHA_MIN = 0f
    const val SCRIM_ALPHA_MAX = 0.6f

    /** The existing dark background colour (ArtlogBackground). */
    const val DEFAULT_CUSTOM_COLOR = 0xFF111214L

    /** P2 阶段五：全局文字缩放系数默认值与范围（Slider 0.85–1.3）。 */
    const val DEFAULT_TEXT_SIZE_SCALE = 1.0f
    const val TEXT_SIZE_SCALE_MIN = 0.85f
    const val TEXT_SIZE_SCALE_MAX = 1.3f

    /** P2 阶段五：页内歌词字号默认值与范围（sp，Slider 10–32）。
     * 最终验收修改轮：默认值 16sp → 18sp；v26：默认值 18sp → 22sp
     *（相邻行按 0.875 比例自动变为 19.25sp）；范围与钳制逻辑不变，
     * 用户仍可在设置里调回。 */
    const val DEFAULT_LYRICS_FONT_SIZE = 22f
    /** v26 一次性迁移用：上一版默认值 18sp。见
     * [AppearanceSettings.migrateLyricsFontSizeDefault]。 */
    const val PREVIOUS_DEFAULT_LYRICS_FONT_SIZE = 18f
    const val LYRICS_FONT_SIZE_MIN = 10f
    const val LYRICS_FONT_SIZE_MAX = 32f

    /**
     * P2 阶段五：默认主文字色（ArtlogTextPrimary，0xFFF0F0EF）。只
     * 在 `text_color` 键已存在时作 getLong() 的兜底默认值；键缺省
     * 本身用 null 表示（即主题默认），不由该值表示。
     */
    const val DEFAULT_TEXT_COLOR_ARGB = 0xFFF0F0EFL

    /** P2 阶段五：相邻歌词行相对当前行的字号比例（15.75/18）。 */
    const val LYRICS_ADJACENT_SIZE_RATIO = 0.875f

    const val DEFAULT_PRESET_ID = "ink"

    /** 8 low-saturation dark presets, in line with the Color.kt palette. */
    val PRESETS: List<BackgroundPreset> = listOf(
        BackgroundPreset("ink", "墨色", 0xFF111214L),
        BackgroundPreset("slate", "石板", 0xFF191A1DL),
        BackgroundPreset("charcoal", "炭灰", 0xFF222429L),
        BackgroundPreset("pine", "松绿", 0xFF181E1AL),
        BackgroundPreset("indigo", "靛青", 0xFF1A1E26L),
        BackgroundPreset("plum", "暗梅", 0xFF211C20L),
        BackgroundPreset("umber", "赭石", 0xFF201C18L),
        BackgroundPreset("teal", "深青", 0xFF16201EL)
    )

    fun presetById(id: String?): BackgroundPreset? =
        PRESETS.firstOrNull { it.id == id }

    /** Preset colour for [presetId]; unknown ids fall back to the default. */
    fun presetColor(presetId: String?): Long =
        presetById(presetId)?.argb ?: presetById(DEFAULT_PRESET_ID)!!.argb

    /** Unknown / null stored values fall back to COLOR_PRESET, never crash. */
    fun parseMode(raw: String?): BackgroundMode = when (raw) {
        BackgroundMode.COLOR_CUSTOM.name -> BackgroundMode.COLOR_CUSTOM
        BackgroundMode.IMAGE.name -> BackgroundMode.IMAGE
        else -> BackgroundMode.COLOR_PRESET
    }

    /** Clamp the scrim opacity into the 0–0.6 slider range. */
    fun clampScrimAlpha(value: Float): Float =
        value.coerceIn(SCRIM_ALPHA_MIN, SCRIM_ALPHA_MAX)

    /** P2 阶段五：全局文字缩放系数钳制到 0.85–1.3；NaN 回落到 1.0。 */
    fun clampTextSizeScale(value: Float): Float =
        if (value.isNaN()) DEFAULT_TEXT_SIZE_SCALE
        else value.coerceIn(TEXT_SIZE_SCALE_MIN, TEXT_SIZE_SCALE_MAX)

    /** P2 阶段五：页内歌词字号（sp）钳制到 10–32；NaN 回落到 18。 */
    fun clampLyricsFontSize(value: Float): Float =
        if (value.isNaN()) DEFAULT_LYRICS_FONT_SIZE
        else value.coerceIn(LYRICS_FONT_SIZE_MIN, LYRICS_FONT_SIZE_MAX)

    /**
     * Parse a user-typed colour: `#RRGGBB`, `#AARRGGBB`, or the same
     * without `#`. A 6-digit value is opaque (alpha FF). Returns null
     * for anything else — callers show an error, never a crash.
     */
    fun parseColorHex(text: String): Long? {
        val hex = text.trim().removePrefix("#")
        if (hex.length != 6 && hex.length != 8) return null
        if (!hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        val full = if (hex.length == 6) "FF$hex" else hex
        return full.toLongOrNull(16)
    }

    /** Format an ARGB Long as `#AARRGGBB` for display / editing. */
    fun formatColorHex(argb: Long): String = "#%08X".format(argb)

    /**
     * Decide what the background layer draws. Image mode with a missing
     * path, a blank path or a deleted file silently falls back to the
     * solid colour — never a crash, never an error popup.
     */
    fun resolveBackground(
        mode: BackgroundMode,
        presetId: String?,
        customColor: Long,
        imagePath: String?
    ): ResolvedBackground {
        if (mode == BackgroundMode.IMAGE && !imagePath.isNullOrBlank()) {
            val file = File(imagePath)
            if (file.isFile) return ResolvedBackground.Image(file)
        }
        val solid = if (mode == BackgroundMode.COLOR_CUSTOM) customColor
            else presetColor(presetId)
        return ResolvedBackground.Solid(solid)
    }

    /** Convenience overload for a settings snapshot. */
    fun resolveBackground(state: AppearanceState): ResolvedBackground =
        resolveBackground(state.mode, state.presetId, state.customColor, state.imagePath)
}

/**
 * Pure home-title logic (P2 首页标题编辑) — no Android framework
 * calls, so it is covered by the JVM test (TestHomeTitle). The UI
 * enforces the same rules: input longer than [MAX_TITLE_LENGTH] is
 * rejected at typing time (never stored truncated), and the save
 * button is disabled while [isSaveable] is false.
 *
 * Counting is Kotlin [String.length] (UTF-16 code units), the same
 * unit Compose TextField length checks use: one emoji counts as 2.
 * Restoring the default deletes the key; [displayTitle] maps a
 * missing / blank stored value back to the default.
 */
object HomeTitleLogic {

    /** Default home title (the previously hard-coded headline). */
    const val DEFAULT_TITLE = "每一笔，都是来时的路。"

    /** Maximum title length in characters ([String.length] units). */
    const val MAX_TITLE_LENGTH = 20

    /** True when [text] may be saved: non-blank and within the limit. */
    fun isSaveable(text: String): Boolean =
        text.isNotBlank() && text.length <= MAX_TITLE_LENGTH

    /** Title to display for a stored value; null / blank falls back. */
    fun displayTitle(stored: String?): String =
        stored?.takeIf { it.isNotBlank() } ?: DEFAULT_TITLE
}
