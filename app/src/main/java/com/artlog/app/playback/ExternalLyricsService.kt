package com.artlog.app.playback

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.IBinder
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.artlog.app.ArtlogApplication
import com.artlog.app.data.AppearanceSettings
import com.artlog.app.data.FloatingLyricsLogic
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 系统级悬浮歌词宿主（P0 验收修复轮）。
 *
 * 独立 Service，只承载覆盖层窗口，不复用、不重写 PlaybackService /
 * MediaSession / MusicPlaybackState。数据源与内部悬浮歌词共用同一份
 * [MusicPlaybackState]（parsedLyrics + currentLyricIndex +
 * lyricsError + currentTrack），不建第二个播放器、不另建解析。
 *
 * 窗口：WindowManager + TYPE_APPLICATION_OVERLAY，FLAG_NOT_FOCUSABLE
 *（不抢焦点）+ FLAG_NOT_TOUCH_MODAL（窗口外触摸透传给下层 App）；
 * 只有歌词卡片区域可触摸（歌词区拖动、右上角关闭按钮），其余区域
 * 透传。开关、位置、大小、锁定全部走独立的 external_* 键，与内部
 * 悬浮（floating_*）互不干扰；关闭其中一个不影响另一个。
 *
 * 生命周期：
 * - 启动：只由用户在设置页打开 external_lyrics_enabled 开关后经
 *   [sync] / [start] 启动（且须已获 SYSTEM_ALERT_WINDOW 授权）；
 *   绝不在 App 启动时自动拉起。
 * - 被杀恢复：onStartCommand 返回 START_STICKY，系统回收后会重建
 *   Service；重建时先复查权限与开关，不满足即自停，不留僵尸窗口。
 *   另有 MainActivity.onResume 的 [sync] 复查：用户回到 App 前台
 *   时若开关仍开、权限仍在，即重新拉起。
 * - 进程退出 / onDestroy：取消自己的 collect scope 并 removeView，
 *   窗口随 Service 销毁而清理；绝不调用 holder.release()（那会
 *   停掉全 App 的播放状态流，生产代码零调用方是刻意设计）。
 * - 权限撤销：启动时检查 + 回到前台时复查（MainActivity.onResume
 *   经 [sync]）+ 存活期间每 2 秒复查；撤销后同步关闭
 *   external_lyrics_enabled 开关、移除窗口、停掉 Service。
 *
 * 本轮不给系统悬浮歌词加切歌按钮（切歌按钮是 P1 需求）。
 * 应用内悬浮歌词的拖动 / 大小 / 锁定也是 P1，不在本 Service 内做。
 */
class ExternalLyricsService : Service() {

    companion object {
        /** 系统悬浮窗权限是否已授予（Settings.canDrawOverlays）。 */
        fun isOverlayGranted(context: Context): Boolean =
            Settings.canDrawOverlays(context)

        /**
         * 跳系统悬浮窗授权页的 Intent（应用专属页面，带 package: URI）。
         * 不假设一定能直接进入应用专属页面：部分系统 / ROM 会落到
         * 通用悬浮窗列表页，设置页文案已按此容忍（提示用户在列表
         * 中找到 Artlog 再开启）。
         */
        fun overlaySettingsIntent(context: Context): Intent =
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + context.packageName)
            )

        /** 启动本服务（未授权时静默不启动，不抛异常、不弹窗）。 */
        fun start(context: Context) {
            if (!isOverlayGranted(context)) return
            try {
                context.startService(
                    Intent(context, ExternalLyricsService::class.java)
                )
            } catch (_: Exception) {
                // 后台拉起受限等系统拒绝：不崩溃；用户回到前台时
                // MainActivity.onResume 的 sync() 会再次尝试。
            }
        }

        /** 停掉本服务（未在跑时是 no-op）。 */
        fun stop(context: Context) {
            context.stopService(Intent(context, ExternalLyricsService::class.java))
        }

        /**
         * 按“权限 + 总开关 + 系统级开关”三者同步服务状态：
         * 三者齐全则启动，任一缺失则停掉。调用方：设置页开关切换、
         * MainActivity.onResume（回到前台复查）、设置页回到前台。
         */
        fun sync(context: Context) {
            val app = context.applicationContext as ArtlogApplication
            val state = app.appearanceSettings.snapshot()
            if (isOverlayGranted(context) && state.lyricsEnabled &&
                state.externalLyricsEnabled
            ) {
                start(context)
            } else {
                stop(context)
            }
        }
    }

    private lateinit var windowManager: WindowManager
    private lateinit var appearanceSettings: AppearanceSettings
    private lateinit var holder: MusicPlaybackState

    /** 自己的 scope：onDestroy 时取消，只停自己的订阅，不碰 holder。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var cardView: LinearLayout? = null
    private var textContainer: LinearLayout? = null
    private var lineViews: List<TextView> = emptyList()
    private var params: WindowManager.LayoutParams? = null
    private var viewAdded = false

    /** 拖动时读取的最新锁定态（render 时更新）。 */
    private var locked = false

    override fun onCreate() {
        super.onCreate()
        val app = application as ArtlogApplication
        appearanceSettings = app.appearanceSettings
        holder = app.musicPlaybackState
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        buildView()
        scope.launch {
            combine(
                holder.parsedLyrics,
                holder.currentLyricIndex,
                holder.lyricsError,
                holder.currentTrack,
                appearanceSettings.state
            ) { parsed, index, error, track, appearance ->
                OverlayRenderState(parsed, index, error, track != null, appearance)
            }.collect { render(it) }
        }
        // 存活期间的撤销复查：系统设置中撤销权限后，窗口会被系统
        // 移除；App 侧同步停掉服务并更新开关状态，不留僵尸窗口。
        scope.launch {
            while (isActive) {
                delay(2000)
                if (!isOverlayGranted(this@ExternalLyricsService)) {
                    onOverlayRevoked()
                    break
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 被杀重建时先复查：权限或开关任一缺失即自停，不重建窗口。
        val state = appearanceSettings.snapshot()
        if (!isOverlayGranted(this) || !state.lyricsEnabled ||
            !state.externalLyricsEnabled
        ) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        removeOverlay()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private data class OverlayRenderState(
        val parsed: com.artlog.app.data.ParsedLyrics?,
        val currentLyricIndex: Int,
        val lyricsError: String?,
        val hasCurrentTrack: Boolean,
        val appearance: com.artlog.app.data.AppearanceState
    )

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).roundToInt()

    private fun buildView() {
        val density = resources.displayMetrics.density
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.argb(140, 0, 0, 0))
                cornerRadius = 12f * density
            }
            setPadding(dp(14), dp(8), dp(14), dp(8))
        }
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }
        val lines = (0 until 3).map {
            TextView(this).apply {
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setShadowLayer(4f, 0f, 1f, Color.argb(204, 0, 0, 0))
            }
        }
        lines.forEach { texts.addView(it) }
        val close = TextView(this).apply {
            text = "✕"
            contentDescription = "关闭系统悬浮歌词"
            setTextColor(Color.argb(153, 255, 255, 255))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            gravity = Gravity.CENTER
            setPadding(dp(4), 0, 0, 0)
            setOnClickListener {
                // 只关系统级开关（持久化）；订阅到状态变化后
                // render() 会移除窗口并停掉本服务。
                appearanceSettings.setExternalLyricsEnabled(false)
            }
        }
        card.addView(texts)
        card.addView(close)
        cardView = card
        textContainer = texts
        lineViews = lines

        // 拖动：只挂在歌词文本区（关闭按钮不受影响，仍可点）。
        // 锁定后不拖动；拖动结束（ACTION_UP）才持久化归一化位置。
        var downRawX = 0f
        var downRawY = 0f
        var downParamsX = 0
        var downParamsY = 0
        var dragging = false
        texts.setOnTouchListener { _, event ->
            if (locked) return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    downParamsX = params?.x ?: 0
                    downParamsY = params?.y ?: 0
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - downRawX).roundToInt()
                    val dy = (event.rawY - downRawY).roundToInt()
                    if (!dragging && dx * dx + dy * dy < 8 * 8) {
                        return@setOnTouchListener true
                    }
                    dragging = true
                    val p = params ?: return@setOnTouchListener true
                    val metrics = resources.displayMetrics
                    val cardW = cardView?.width ?: 0
                    val cardH = cardView?.height ?: 0
                    p.x = (downParamsX + dx)
                        .coerceIn(0, (metrics.widthPixels - cardW).coerceAtLeast(0))
                    p.y = (downParamsY + dy)
                        .coerceIn(0, (metrics.heightPixels - cardH).coerceAtLeast(0))
                    try {
                        windowManager.updateViewLayout(cardView, p)
                    } catch (_: Exception) {
                        // 窗口已被系统移除：忽略，本轮 render / 复查会处理。
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        val metrics = resources.displayMetrics
                        val cardW = cardView?.width ?: 0
                        val cardH = cardView?.height ?: 0
                        val p = params
                        if (p != null && cardW > 0 && cardH > 0) {
                            appearanceSettings.setExternalLyricsPosition(
                                (p.x + cardW / 2f) / metrics.widthPixels,
                                (p.y + cardH / 2f) / metrics.heightPixels
                            )
                        }
                    }
                    dragging = false
                    true
                }
                else -> false
            }
        }
    }

    private fun render(state: OverlayRenderState) {
        val appearance = state.appearance
        // 权限被撤销：同步关开关、移除窗口、停服务（不静默失败）。
        if (!isOverlayGranted(this)) {
            onOverlayRevoked()
            return
        }
        val visible = FloatingLyricsLogic.shouldShowExternalLyrics(
            lyricsEnabled = appearance.lyricsEnabled,
            externalLyricsEnabled = appearance.externalLyricsEnabled,
            hasCurrentTrack = state.hasCurrentTrack
        )
        if (!visible) {
            removeOverlay()
            stopSelf()
            return
        }
        locked = appearance.externalLyricsLocked
        if (!viewAdded) addOverlay()
        val lines = FloatingLyricsLogic.externalDisplayLines(
            state.parsed, state.currentLyricIndex, state.lyricsError
        )
        val currentSize = appearance.externalLyricsFontSize
        val dimSize = (currentSize - 3f).coerceAtLeast(10f)
        lineViews.forEachIndexed { index, view ->
            val line = lines.getOrNull(index)
            if (line == null) {
                view.visibility = View.GONE
            } else {
                view.visibility = View.VISIBLE
                view.text = line.text
                if (line.isCurrent) {
                    view.setTextColor(Color.WHITE)
                    view.setTextSize(TypedValue.COMPLEX_UNIT_SP, currentSize)
                    view.setTypeface(null, Typeface.BOLD)
                } else {
                    view.setTextColor(Color.argb(140, 255, 255, 255))
                    view.setTextSize(TypedValue.COMPLEX_UNIT_SP, dimSize)
                    view.setTypeface(null, Typeface.NORMAL)
                }
            }
        }
        // 字号变化可能改变卡片尺寸：重排一次位置（按中心点归一化坐标）。
        cardView?.post { reposition() }
    }

    /** 权限被系统设置撤销后的处理：关开关、清窗口、停服务。 */
    private fun onOverlayRevoked() {
        if (appearanceSettings.snapshot().externalLyricsEnabled) {
            appearanceSettings.setExternalLyricsEnabled(false)
        }
        removeOverlay()
        stopSelf()
    }

    private fun addOverlay() {
        val card = cardView ?: return
        val appearance = appearanceSettings.snapshot()
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            val metrics = resources.displayMetrics
            x = (appearance.externalLyricsX * metrics.widthPixels).roundToInt()
            y = (appearance.externalLyricsY * metrics.heightPixels).roundToInt()
        }
        params = p
        try {
            windowManager.addView(card, p)
            viewAdded = true
        } catch (_: Exception) {
            // 未授权 / 窗口类型被拒：不崩溃；开关保持原值，用户可在
            // 设置页看到未授权说明并重新申请。
            viewAdded = false
            return
        }
        // 首次布局后按卡片实测尺寸把中心点对准归一化坐标。
        card.post { reposition() }
    }

    /** 按当前归一化位置（卡片中心点）重算窗口左上角坐标并钳制在屏内。 */
    private fun reposition() {
        val card = cardView ?: return
        val p = params ?: return
        if (!viewAdded) return
        val cardW = card.width
        val cardH = card.height
        if (cardW <= 0 || cardH <= 0) return
        val appearance = appearanceSettings.snapshot()
        val (x, y) = FloatingLyricsLogic.clampPosition(
            appearance.externalLyricsX, appearance.externalLyricsY
        )
        val metrics = resources.displayMetrics
        p.x = (x * metrics.widthPixels - cardW / 2f).roundToInt()
            .coerceIn(0, (metrics.widthPixels - cardW).coerceAtLeast(0))
        p.y = (y * metrics.heightPixels - cardH / 2f).roundToInt()
            .coerceIn(0, (metrics.heightPixels - cardH).coerceAtLeast(0))
        try {
            windowManager.updateViewLayout(card, p)
        } catch (_: Exception) {
            // 窗口已被系统移除：忽略，本轮 render / 复查会处理。
        }
    }

    private fun removeOverlay() {
        val card = cardView ?: return
        if (!viewAdded) return
        viewAdded = false
        try {
            windowManager.removeView(card)
        } catch (_: Exception) {
            // 窗口已被系统移除（撤销权限时系统会先行移除）：忽略。
        }
    }
}
