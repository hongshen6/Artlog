package com.artlog.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.pm.PackageInfoCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.artlog.app.CrashReporter
import com.artlog.app.data.AppearanceLogic
import com.artlog.app.data.AppearanceSettings
import com.artlog.app.data.AuthorInfo
import com.artlog.app.data.BackgroundMode
import com.artlog.app.data.BackupManager
import com.artlog.app.data.FloatingLyricsLogic
import com.artlog.app.playback.ExternalLyricsService
import com.artlog.app.ui.ArtlogViewModelFactory
import java.io.File
import kotlinx.coroutines.launch

/**
 * Settings (P2 stage 5): reorganised into five groups —
 * 外观与个性化 / 音乐与歌词 / 数据管理 / 关于 Artlog / 作者声明.
 * Every entry from the old seven flat groups is placed in exactly one
 * of the five new groups (see the mapping table in
 * p2-about-author-report.md); nothing was dropped.
 *
 * - 关于 Artlog / 作者声明 are sub-pages inside this overlay (local
 *   page state, no navigation change in ArtlogApp); the top-bar back
 *   button returns to the settings list first.
 * - The version shown here and on the About page is read from
 *   PackageInfo ([appVersion], same call BackupManager.appVersion()
 *   uses) — never hard-coded. The old hard-coded "Artlog 1.2.0" line
 *   is gone.
 * - No lockscreen-lyrics entry exists anywhere on this page: the
 *   `lockscreen_lyrics_enabled` key stays defined-and-backed-up only
 *   (audit §8-G); lockscreen media control (MediaSession) is a
 *   playback fact, not a lyrics feature, and is not presented here.
 */
private enum class SettingsPage { List, About, Author }

/** Real app version, same PackageInfo read as BackupManager.appVersion(). */
private fun appVersion(context: Context): Pair<String, Long> {
    return try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        (info.versionName ?: "未知") to PackageInfoCompat.getLongVersionCode(info)
    } catch (_: PackageManager.NameNotFoundException) {
        "未知" to 0L
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBackClick: () -> Unit,
    viewModelFactory: ArtlogViewModelFactory
) {
    val context = LocalContext.current
    var page by remember { mutableStateOf(SettingsPage.List) }
    val (versionName, versionCode) = remember { appVersion(context) }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Text(
                    text = when (page) {
                        SettingsPage.List -> "设置"
                        SettingsPage.About -> "关于 Artlog"
                        SettingsPage.Author -> "关于作者 · About the Author"
                    },
                    style = MaterialTheme.typography.titleLarge,
                    fontSize = 20.sp
                )
            },
            navigationIcon = {
                IconButton(onClick = {
                    if (page == SettingsPage.List) onBackClick()
                    else page = SettingsPage.List
                }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回"
                    )
                }
            },
            // 2026-11-14 设置页顶栏修复：标题行上移 + 去黑。
            // (1) 容器色改为透明，背景图透出（原来是不透明的
            // colorScheme.background #111214，挡住了背景图）。
            // (2) 只保留水平方向 insets：ArtlogApp 的 Scaffold 内容区
            // 已经给了状态栏高度的 padding，TopAppBar 默认还会再加
            // 一份状态栏 inset（M3 1.3.0 实测），两份叠加使标题行
            // 下沉约一个状态栏高度、黑带增高；去掉重复的那份即可。
            windowInsets = TopAppBarDefaults.windowInsets.only(
                WindowInsetsSides.Horizontal
            ),
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Transparent
            )
        )

        when (page) {
            SettingsPage.List -> SettingsList(
                versionName = versionName,
                onAboutClick = { page = SettingsPage.About },
                onAuthorClick = { page = SettingsPage.Author },
                viewModelFactory = viewModelFactory
            )
            SettingsPage.About -> AboutPage(
                versionName = versionName,
                versionCode = versionCode
            )
            SettingsPage.Author -> AuthorPage(
                versionName = versionName,
                versionCode = versionCode
            )
        }
    }
}

@Composable
private fun SettingsList(
    versionName: String,
    onAboutClick: () -> Unit,
    onAuthorClick: () -> Unit,
    viewModelFactory: ArtlogViewModelFactory
) {
    Column(
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        // ---- 1. 外观与个性化 ----
        SettingsGroupTitle(text = "外观与个性化")
        Spacer(modifier = Modifier.height(8.dp))
        AppearanceSection()

        Spacer(modifier = Modifier.height(28.dp))

        // ---- 2. 音乐与歌词 ----
        SettingsGroupTitle(text = "音乐与歌词")
        Spacer(modifier = Modifier.height(8.dp))
        MusicLyricsSection()

        Spacer(modifier = Modifier.height(28.dp))

        // ---- 3. 数据管理 ----
        SettingsGroupTitle(text = "数据管理")
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "所有作品与记录默认保存在本机，不登录、不上传作品与记录；图片等功能按需联网。",
            style = MaterialTheme.typography.bodyMedium,
            fontSize = 14.sp
        )
        Spacer(modifier = Modifier.height(8.dp))
        BackupSection(viewModelFactory = viewModelFactory)
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "崩溃记录",
            style = MaterialTheme.typography.titleMedium,
            fontSize = 16.sp
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "每次崩溃都会同时在手机的「文件」→ 文档 → Artlog 里留一份日志，即使打不开 App 也能找到并分享。",
            style = MaterialTheme.typography.bodyMedium,
            fontSize = 14.sp
        )
        Spacer(modifier = Modifier.height(8.dp))
        CrashLogSection()

        Spacer(modifier = Modifier.height(28.dp))

        // ---- 4. 关于 Artlog ----
        SettingsGroupTitle(text = "关于 Artlog")
        Spacer(modifier = Modifier.height(8.dp))
        SettingsEntryRow(
            title = "关于 Artlog",
            subtitle = "Artlog $versionName",
            onClick = onAboutClick
        )

        Spacer(modifier = Modifier.height(28.dp))

        // ---- 5. 作者声明 ----
        SettingsGroupTitle(text = "作者声明")
        Spacer(modifier = Modifier.height(8.dp))
        SettingsEntryRow(
            title = "作者声明",
            subtitle = "作者署名、联系方式",
            onClick = onAuthorClick
        )
        Spacer(modifier = Modifier.height(28.dp))
    }
}

@Composable
private fun SettingsGroupTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontSize = 16.sp
    )
}

@Composable
private fun SettingsEntryRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontSize = 14.sp
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * About Artlog page.
 *
 * - Version is the real PackageInfo version, never hard-coded.
 * - Positioning line is the task-book wording, verbatim:
 *   “Artlog 是一款面向个人创作者的本地优先绘画档案与创作记录工具。”
 * - No GitHub address, no licence, no author information anywhere on
 *   this page: the project has no published open-source release yet,
 *   so nothing is invented here.
 * - Plain scrolling text; no animation, no promotional artwork, no
 *   network request.
 */
@Composable
private fun AboutPage(versionName: String, versionCode: Long) {
    Column(
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Artlog",
            style = MaterialTheme.typography.headlineLarge,
            fontSize = 26.sp
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "版本 $versionName（$versionCode）",
            style = MaterialTheme.typography.bodyMedium,
            fontSize = 14.sp
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Artlog 是一款面向个人创作者的本地优先绘画档案与创作记录工具。",
            style = MaterialTheme.typography.bodyLarge,
            fontSize = 14.sp
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "你可以使用 Artlog 保存自己的绘画作品，记录创作过程，整理创作日记，回顾绘画积累，" +
                "也可以通过本地音乐和视觉灵感网站辅助自己的创作。",
            style = MaterialTheme.typography.bodyMedium,
            fontSize = 14.sp
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "Artlog 重视个人数据的自主性、隐私和长期保存，希望为创作者提供一个安静、自由、" +
                "不受干扰的私人创作空间。",
            style = MaterialTheme.typography.bodyMedium,
            fontSize = 14.sp
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "每一笔，都是来时的路。",
            style = MaterialTheme.typography.titleMedium,
            fontSize = 16.sp
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Artlog — Every stroke has a story.",
            style = MaterialTheme.typography.bodyMedium,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(28.dp))
    }
}

/**
 * Author page （关于作者 · About the Author).
 *
 * This page only renders: every section comes from [AuthorInfo.sections]
 * (data/AuthorInfo.kt), the single static config for author content.
 * Section order follows the task book: 作者署名 /
 * 联系方式 / 作品与版权 / 第三方资源 / 关于开源 / 版本与声明.
 * （开发初衷与创作理念两节自 versionCode 23 起移除，全文见项目
 * README.md 末尾附录。）
 *
 * What is shown (user-confirmed 2026-11-14 01:02, verbatim):
 * - 网名 “飞舞网络卡死了”；联系方式 3569694301@qq.com（明确同意公开）。
 * What is deliberately NOT shown, because it was not provided:
 * - 个人简介（no section; the origin story is never used as a
 *   biography substitute — its full text lives in the README appendix）；
 * - GitHub 仓库地址与许可证（null in AuthorInfo → no block, no "null",
 *   no placeholder, nothing invented）. The 关于开源 section says
 *   plainly that the information will only appear in a later updated
 *   version after release, never automatically.
 * Version is the real PackageInfo version, never hard-coded. This page
 * is a project note, not a professional legal document — it says so in
 * the last section. No lockscreen-lyrics claim anywhere on this page
 * (lockscreen dynamic lyrics are still unsupported). No network
 * request, no upload.
 */
@Composable
private fun AuthorPage(versionName: String, versionCode: Long) {
    Column(
        modifier = Modifier
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Spacer(modifier = Modifier.height(16.dp))
        for (section in AuthorInfo.sections("版本 $versionName（$versionCode）")) {
            AuthorSection(
                title = section.title,
                body = section.body
            )
        }
        Spacer(modifier = Modifier.height(28.dp))
    }
}

@Composable
private fun AuthorSection(title: String, body: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontSize = 16.sp
    )
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = body,
        style = MaterialTheme.typography.bodyMedium,
        fontSize = 14.sp
    )
    Spacer(modifier = Modifier.height(20.dp))
}

/**
 * Shows the most recent crash log recorded by [CrashReporter], if any,
 * with view / copy / share actions. When the app has never crashed there
 * is honestly nothing to show.
 */
@Composable
private fun CrashLogSection() {
    val context = LocalContext.current
    var logText by remember { mutableStateOf<String?>(null) }
    var logLoaded by remember { mutableStateOf(false) }
    var showDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        logText = CrashReporter.latestLogText(context)
        logLoaded = true
    }

    if (!logLoaded) {
        Text(
            text = "正在读取…",
            style = MaterialTheme.typography.bodyMedium,
            fontSize = 14.sp
        )
        return
    }

    val text = logText
    if (text == null) {
        Text(
            text = "暂无崩溃记录。",
            style = MaterialTheme.typography.bodyMedium,
            fontSize = 14.sp
        )
        return
    }

    // First line of the report is the exception; show it as a summary.
    val summary = text.lineSequence().firstOrNull { it.startsWith("java.") || it.contains("Exception") }
        ?: "发生过一次崩溃，详情见日志。"
    Text(
        text = "上次崩溃：$summary",
        style = MaterialTheme.typography.bodyMedium,
        fontSize = 14.sp
    )
    Spacer(modifier = Modifier.height(8.dp))
    Row {
        TextButton(onClick = { showDialog = true }) {
            Text(text = "查看日志")
        }
        TextButton(onClick = {
            val clipboard =
                context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(
                ClipData.newPlainText("Artlog 崩溃日志", text)
            )
        }) {
            Text(text = "复制")
        }
        TextButton(onClick = {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Artlog 崩溃日志")
                putExtra(Intent.EXTRA_TEXT, text)
            }
            context.startActivity(Intent.createChooser(send, "分享崩溃日志"))
        }) {
            Text(text = "分享")
        }
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(text = "崩溃日志") },
            text = {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    fontSize = 12.sp,
                    modifier = Modifier.verticalScroll(rememberScrollState())
                )
            },
            confirmButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text(text = "关闭")
                }
            }
        )
    }
}

/**
 * Data safety (round 4): local backup / restore / integrity check.
 *
 * - Backup writes a ZIP (database + images + manifest with a SHA-256
 *   record per file) to Documents/Artlog/backup/ and verifies it by
 *   re-reading it before reporting success.
 * - Restore picks a ZIP, validates it fully in a staging directory, and
 *   only then swaps it in; a snapshot is kept so a failed swap rolls back.
 * - The uninstall note below is permanent text, not a one-time popup:
 *   Android deletes the app-private directory on uninstall, and nothing
 *   can intercept the uninstall itself, so the reminder has to live here.
 */
@Composable
private fun BackupSection(viewModelFactory: ArtlogViewModelFactory) {
    val viewModel: BackupViewModel = viewModel(factory = viewModelFactory)
    val isWorking by viewModel.isWorking.collectAsState()
    val status by viewModel.status.collectAsState()
    val report by viewModel.integrityReport.collectAsState()

    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) viewModel.restore(uri)
    }

    Text(
        text = "作品、图片、手记和标签默认保存在 App 私有目录里，" +
            "卸载 App 时会被系统一起删除；备份文件保存在手机「文件」→ " +
            "文档 → Artlog → backup，卸载后仍保留。卸载 App 前请先备份。",
        style = MaterialTheme.typography.bodyMedium,
        fontSize = 14.sp
    )
    Spacer(modifier = Modifier.height(8.dp))
    Row {
        TextButton(
            onClick = { viewModel.backup() },
            enabled = !isWorking
        ) {
            Text(text = "备份")
        }
        TextButton(
            onClick = { restoreLauncher.launch(arrayOf("application/zip")) },
            enabled = !isWorking
        ) {
            Text(text = "恢复")
        }
        TextButton(
            onClick = { viewModel.checkIntegrity() },
            enabled = !isWorking
        ) {
            Text(text = "检查图片完整性")
        }
    }
    if (isWorking) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "正在处理…",
            style = MaterialTheme.typography.bodyMedium,
            fontSize = 14.sp
        )
    }
    val currentStatus = status
    if (currentStatus != null) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = currentStatus.text,
            style = MaterialTheme.typography.bodyMedium,
            fontSize = 14.sp,
            color = if (currentStatus.isError) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurface
            }
        )
    }

    val currentReport = report
    if (currentReport != null) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissIntegrityReport() },
            title = { Text(text = "图片完整性检查") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        text = currentReport.summary(),
                        style = MaterialTheme.typography.bodyMedium,
                        fontSize = 14.sp
                    )
                    val problems = currentReport.problems
                    if (problems.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "有问题的图片（只报告，不自动删除或修复）：",
                            style = MaterialTheme.typography.bodyMedium,
                            fontSize = 14.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        val shown = problems.take(20)
                        shown.forEach { problem ->
                            val kind = when (problem.kind) {
                                BackupManager.ProblemKind.MISSING -> "文件缺失"
                                BackupManager.ProblemKind.EMPTY -> "空文件"
                                BackupManager.ProblemKind.UNDECODABLE -> "无法解码"
                            }
                            Text(
                                text = "「${problem.artworkTitle}」${problem.role}" +
                                    " ${problem.fileName}：$kind",
                                style = MaterialTheme.typography.bodySmall,
                                fontSize = 12.sp
                            )
                        }
                        if (problems.size > shown.size) {
                            Text(
                                text = "等 ${problems.size} 项（仅显示前 20 项）",
                                style = MaterialTheme.typography.bodySmall,
                                fontSize = 12.sp
                            )
                        }
                    }
                    if (currentReport.orphanFiles.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "没有对应数据库记录的文件：",
                            style = MaterialTheme.typography.bodyMedium,
                            fontSize = 14.sp
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        val shown = currentReport.orphanFiles.take(20)
                        shown.forEach { path ->
                            Text(
                                text = path,
                                style = MaterialTheme.typography.bodySmall,
                                fontSize = 12.sp
                            )
                        }
                        if (currentReport.orphanFiles.size > shown.size) {
                            Text(
                                text = "等 ${currentReport.orphanFiles.size} 个（仅显示前 20 个）",
                                style = MaterialTheme.typography.bodySmall,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissIntegrityReport() }) {
                    Text(text = "关闭")
                }
            }
        )
    }
}

/**
 * 音乐与歌词（P2 阶段五重组）。
 *
 * - 歌词总开关 `lyrics_enabled`：以前只在音乐页有入口，现在收进
 *   设置页本组；两处是同一个开关，改一处另一处跟着变。
 * - App 内悬浮歌词：开关 + 现状说明。位置与大小不在设置页调：
 *   悬浮歌词出现时直接拖动它移动位置，点它上面的 − / ＋ 调字号，
 *   点锁形按钮锁定位置（P2 阶段四已接线，本页只说明、不重复造入口）。
 * - 页内歌词大小 `lyrics_font_size`：新 Slider，只管音乐页内瀑布
 *   歌词，不跟随全局文字大小。
 * - 系统悬浮歌词：沿用已有的 [LyricsSection]（开关 / 权限 / 字号 /
 *   锁定 / 恢复默认位置），原样保留。
 * - 本组没有任何锁屏歌词开关或文案（见文件头注释）。
 */
@Composable
private fun MusicLyricsSection() {
    val context = LocalContext.current
    val appearanceSettings = remember { AppearanceSettings(context) }
    val state by appearanceSettings.state.collectAsState()

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "歌词总开关：" + if (state.lyricsEnabled) "开" else "关",
            style = MaterialTheme.typography.bodyMedium,
            fontSize = 14.sp,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = {
            appearanceSettings.setLyricsEnabled(!state.lyricsEnabled)
        }) {
            Text(text = if (state.lyricsEnabled) "关闭" else "开启")
        }
    }
    Text(
        text = "关闭后，页内歌词与两处悬浮歌词都不显示；音乐页的歌词开关与这里是同一个开关。",
        style = MaterialTheme.typography.bodyMedium,
        fontSize = 14.sp
    )
    Spacer(modifier = Modifier.height(16.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "App 内悬浮歌词：" + if (state.floatingLyricsEnabled) "开" else "关",
            style = MaterialTheme.typography.bodyMedium,
            fontSize = 14.sp,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = {
            appearanceSettings.setFloatingLyricsEnabled(!state.floatingLyricsEnabled)
        }) {
            Text(text = if (state.floatingLyricsEnabled) "关闭" else "开启")
        }
    }
    Text(
        text = "打开后，播放时歌词浮在 App 各页面上方（当前字号 " +
            "${"%.0f".format(state.floatingLyricsFontSize)}sp）。位置：在悬浮歌词出现时直接拖动它；" +
            "大小：点悬浮歌词上的 − / ＋；锁定：点悬浮歌词上的锁形按钮。",
        style = MaterialTheme.typography.bodyMedium,
        fontSize = 14.sp
    )
    Spacer(modifier = Modifier.height(16.dp))
    Text(
        text = "页内歌词大小：${"%.0f".format(state.lyricsFontSize)}sp",
        style = MaterialTheme.typography.bodyMedium,
        fontSize = 14.sp
    )
    Slider(
        value = state.lyricsFontSize,
        onValueChange = { appearanceSettings.setLyricsFontSize(it) },
        valueRange = AppearanceLogic.LYRICS_FONT_SIZE_MIN..AppearanceLogic.LYRICS_FONT_SIZE_MAX,
        modifier = Modifier.fillMaxWidth()
    )
    Text(
        text = "只管音乐页内歌词当前行的大小，相邻行按比例缩小；不跟随全局文字大小；" +
            "内外悬浮歌词各有自己的字号设置。",
        style = MaterialTheme.typography.bodyMedium,
        fontSize = 14.sp
    )
    Spacer(modifier = Modifier.height(16.dp))
    LyricsSection()
}

/**
 * 歌词（P0 验收修复轮：系统级悬浮歌词）。
 *
 * - 系统级开关 external_lyrics_enabled：与 App 内悬浮开关各自独立；
 *   同样受歌词总开关 lyrics_enabled 约束（总开关关则系统悬浮也不显示）。
 * - 悬浮窗权限是特殊权限：不能用常规运行时弹窗申请，只能由用户
 *   主动点击后跳系统设置页（Settings.ACTION_MANAGE_OVERLAY_PERMISSION，
 *   带 package: URI）；返回后用 Settings.canDrawOverlays() 复查。
 *   本页绝不在打开时自动弹窗索要权限；拒绝授权不反复弹窗，不影响
 *   App 内悬浮歌词与后台音乐播放。
 * - 回到前台时（ON_RESUME）复查权限并同步服务状态：用户在系统设置
 *   里撤销权限后回到 App，开关会被同步关掉、悬浮窗移除、服务停掉。
 * - 大小 / 锁定 / 位置走独立的 external_* 键，不碰内部悬浮的
 *   floating_* 键。切歌按钮是 P1 需求，本轮不加。
 */
@Composable
private fun LyricsSection() {
    val context = LocalContext.current
    val appearanceSettings = remember { AppearanceSettings(context) }
    val state by appearanceSettings.state.collectAsState()

    var overlayGranted by remember {
        mutableStateOf(ExternalLyricsService.isOverlayGranted(context))
    }
    // 复查权限并同步服务状态（设置页回到前台、从系统设置页返回时都走这里）。
    val recheck: () -> Unit = {
        overlayGranted = ExternalLyricsService.isOverlayGranted(context)
        ExternalLyricsService.sync(context)
    }
    val overlayLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { recheck() }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) recheck()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Text(
        text = "系统悬浮歌词把当前歌词显示在桌面和其他 App 上方，" +
            "与 App 内悬浮歌词互相独立：开关、位置、大小各走各的设置。",
        style = MaterialTheme.typography.bodyMedium,
        fontSize = 14.sp
    )
    if (!state.lyricsEnabled) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "歌词总开关目前是关闭的（在音乐页打开歌词后这里才会生效）；" +
                "总开关关闭时，系统悬浮歌词也不会显示。",
            style = MaterialTheme.typography.bodyMedium,
            fontSize = 14.sp
        )
    }
    Spacer(modifier = Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "在其他 App 上方显示歌词：" + if (state.externalLyricsEnabled) "开" else "关",
            style = MaterialTheme.typography.bodyMedium,
            fontSize = 14.sp,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = {
            val next = !state.externalLyricsEnabled
            appearanceSettings.setExternalLyricsEnabled(next)
            if (!next) {
                ExternalLyricsService.sync(context)
            } else if (ExternalLyricsService.isOverlayGranted(context)) {
                ExternalLyricsService.sync(context)
            } else {
                // 用户主动点击开启：跳系统设置页申请权限（不自动弹窗，
                // 只响应这次点击）。返回后由 launcher 回调复查。
                overlayLauncher.launch(
                    ExternalLyricsService.overlaySettingsIntent(context)
                )
            }
        }) {
            Text(text = if (state.externalLyricsEnabled) "关闭" else "开启")
        }
    }
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        text = "悬浮窗权限：" + if (overlayGranted) "已获得" else "未获得",
        style = MaterialTheme.typography.bodyMedium,
        fontSize = 14.sp
    )
    if (!overlayGranted) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "需要在系统设置里为 Artlog 开启“在其他 App 上层显示”权限，" +
                "系统悬浮歌词才会出现。点“去系统设置开启”后会打开系统页面；" +
                "如果打开的是悬浮窗应用列表而不是 Artlog 专属页面，请在列表中找到 " +
                "Artlog 再开启。拒绝授权不会反复弹窗，也不影响 App 内悬浮歌词和后台音乐播放。",
            style = MaterialTheme.typography.bodyMedium,
            fontSize = 14.sp
        )
        Spacer(modifier = Modifier.height(4.dp))
        TextButton(onClick = {
            overlayLauncher.launch(
                ExternalLyricsService.overlaySettingsIntent(context)
            )
        }) {
            Text(text = "去系统设置开启")
        }
    } else if (state.externalLyricsEnabled) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "有歌词的歌曲播放时，系统悬浮歌词会显示在其他 App 上方。" +
                "在系统设置里关闭权限后，这里会自动关掉并移除悬浮窗。",
            style = MaterialTheme.typography.bodyMedium,
            fontSize = 14.sp
        )
    }
    if (overlayGranted) {
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "系统悬浮歌词大小：${"%.0f".format(state.externalLyricsFontSize)}sp",
            style = MaterialTheme.typography.bodyMedium,
            fontSize = 14.sp
        )
        Slider(
            value = state.externalLyricsFontSize,
            onValueChange = { appearanceSettings.setExternalLyricsFontSize(it) },
            valueRange = 10f..32f,
            modifier = Modifier.fillMaxWidth()
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "锁定位置：" + if (state.externalLyricsLocked) "开" else "关",
                style = MaterialTheme.typography.bodyMedium,
                fontSize = 14.sp,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = {
                appearanceSettings.setExternalLyricsLocked(!state.externalLyricsLocked)
            }) {
                Text(text = if (state.externalLyricsLocked) "解锁" else "锁定")
            }
        }
        Text(
            text = "未锁定时，在其他 App 上方拖动歌词文字区域即可移动位置；" +
                "点歌词右侧的 × 可关闭系统悬浮歌词。",
            style = MaterialTheme.typography.bodyMedium,
            fontSize = 14.sp
        )
        Spacer(modifier = Modifier.height(4.dp))
        TextButton(onClick = {
            appearanceSettings.setExternalLyricsPosition(
                FloatingLyricsLogic.DEFAULT_X, FloatingLyricsLogic.DEFAULT_Y
            )
        }) {
            Text(text = "恢复默认位置")
        }
    }
}

/**
 * Appearance & background (stage 8, P2 stage 5 extended with global
 * text size / colour).
 *
 * - Mode: preset colour / custom colour / background image.
 * - Presets: 8 low-saturation dark colours, hard-coded in
 *   [AppearanceLogic.PRESETS] to match the Color.kt palette.
 * - Custom colour: hex input, stored as an ARGB Long.
 * - Background image: system picker (ImageOnly) -> copied into the
 *   app-private `Artlog/background/` directory, one file only. The
 *   original is never moved or deleted.
 * - Scrim slider: 0–0.6, default 0.45; only affects image backgrounds.
 * - Text size (P2 stage 5): `text_size_scale` Slider, 0.85–1.3,
 *   default 1.0; applies app-wide through ArtlogTheme's density.
 * - Text colour (P2 stage 5): `text_color`, preset light swatches +
 *   hex input (same parse/format helpers as the background custom
 *   colour); absent key = theme default. Only main/secondary text
 *   follows it; semantic colours (error/success/accent) do not.
 * - “恢复默认外观” calls [AppearanceSettings.resetToDefault]: it
 *   resets every appearance key (background image included) but never
 *   touches artworks / journals / tags / statistics / music, and never
 *   resets the master lyrics switch `lyrics_enabled` (pre-existing
 *   rule, unchanged).
 *
 * Changes apply immediately and survive restarts (SharedPreferences).
 * The background colour never alters artwork images themselves.
 */
@Composable
private fun AppearanceSection() {
    val context = LocalContext.current
    val appearanceSettings = remember { AppearanceSettings(context) }
    val state by appearanceSettings.state.collectAsState()
    val scope = rememberCoroutineScope()

    var importError by remember { mutableStateOf(false) }
    var customColorText by remember(state.customColor) {
        mutableStateOf(AppearanceLogic.formatColorHex(state.customColor))
    }
    var customColorError by remember { mutableStateOf(false) }
    var textColorText by remember(state.textColor) {
        mutableStateOf(
            state.textColor?.let { AppearanceLogic.formatColorHex(it) } ?: ""
        )
    }
    var textColorError by remember { mutableStateOf(false) }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                importError = !appearanceSettings.importBackgroundImage(uri)
            }
        }
    }

    Text(
        text = "背景只改变页面底色，不影响作品图片本身。修改后立即生效，重启后保留。",
        style = MaterialTheme.typography.bodyMedium,
        fontSize = 14.sp
    )
    Spacer(modifier = Modifier.height(12.dp))

    // --- Mode: preset / custom / image ---
    AppearanceModeRow(
        label = "预设颜色",
        description = "低饱和深色，与现有配色一致",
        selected = state.mode == BackgroundMode.COLOR_PRESET,
        onClick = { appearanceSettings.setMode(BackgroundMode.COLOR_PRESET) }
    )
    AppearanceModeRow(
        label = "自定义颜色",
        description = "输入色值，用作页面底色",
        selected = state.mode == BackgroundMode.COLOR_CUSTOM,
        onClick = { appearanceSettings.setMode(BackgroundMode.COLOR_CUSTOM) }
    )
    AppearanceModeRow(
        label = "背景图片",
        description = "从相册选一张图，复制到 App 私有目录使用",
        selected = state.mode == BackgroundMode.IMAGE,
        onClick = { appearanceSettings.setMode(BackgroundMode.IMAGE) }
    )

    // --- Preset swatches ---
    Spacer(modifier = Modifier.height(12.dp))
    Text(
        text = "预设颜色",
        style = MaterialTheme.typography.bodyMedium,
        fontSize = 14.sp
    )
    Spacer(modifier = Modifier.height(8.dp))
    AppearanceLogic.PRESETS.chunked(4).forEach { row ->
        Row {
            row.forEach { preset ->
                val selected = state.presetId == preset.id
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color(preset.argb))
                        .border(
                            width = if (selected) 2.dp else 1.dp,
                            color = if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outline,
                            shape = CircleShape
                        )
                        .clickable {
                            appearanceSettings.setPreset(preset.id)
                            appearanceSettings.setMode(BackgroundMode.COLOR_PRESET)
                        }
                )
                Spacer(modifier = Modifier.width(12.dp))
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
    }

    // --- Custom colour ---
    Text(
        text = "自定义颜色",
        style = MaterialTheme.typography.bodyMedium,
        fontSize = 14.sp
    )
    Spacer(modifier = Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextField(
            value = customColorText,
            onValueChange = {
                customColorText = it
                customColorError = false
            },
            label = { Text(text = "色值，如 #1A1E26") },
            singleLine = true,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = {
            val parsed = AppearanceLogic.parseColorHex(customColorText)
            if (parsed == null) {
                customColorError = true
            } else {
                customColorError = false
                appearanceSettings.setCustomColor(parsed)
                appearanceSettings.setMode(BackgroundMode.COLOR_CUSTOM)
            }
        }) {
            Text(text = "应用")
        }
    }
    if (customColorError) {
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "色值格式不对，用 #RRGGBB 或 #AARRGGBB。",
            style = MaterialTheme.typography.bodyMedium,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.error
        )
    }

    // --- Background image ---
    Spacer(modifier = Modifier.height(16.dp))
    Text(
        text = "背景图片",
        style = MaterialTheme.typography.bodyMedium,
        fontSize = 14.sp
    )
    Spacer(modifier = Modifier.height(8.dp))
    val imageFile = remember(state.imagePath) {
        state.imagePath?.let { File(it) }
    }
    val imageExists = imageFile?.isFile == true
    if (state.mode == BackgroundMode.IMAGE && state.imagePath != null && !imageExists) {
        Text(
            text = "背景图片文件已缺失，已自动回退为纯色背景。",
            style = MaterialTheme.typography.bodyMedium,
            fontSize = 14.sp
        )
        Spacer(modifier = Modifier.height(8.dp))
    }
    if (imageExists) {
        AsyncImage(
            model = imageFile,
            contentDescription = "当前背景图片预览",
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp)
                .clip(RoundedCornerShape(8.dp))
        )
        Spacer(modifier = Modifier.height(8.dp))
    }
    Row {
        TextButton(onClick = {
            imagePicker.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        }) {
            Text(text = if (imageExists) "更换背景图片" else "选择背景图片")
        }
        if (imageExists || state.imagePath != null) {
            TextButton(onClick = {
                appearanceSettings.removeBackgroundImage()
                importError = false
            }) {
                Text(text = "移除背景")
            }
        }
        TextButton(onClick = {
            appearanceSettings.resetToDefault()
            importError = false
            customColorError = false
            textColorError = false
        }) {
            Text(text = "恢复默认外观")
        }
    }
    if (importError) {
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "背景图片导入失败，当前背景未变。",
            style = MaterialTheme.typography.bodyMedium,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.error
        )
    }

    // --- Scrim ---
    Spacer(modifier = Modifier.height(16.dp))
    Text(
        text = "图片遮罩不透明度：${"%.2f".format(state.scrimAlpha)}（仅背景图片时生效）",
        style = MaterialTheme.typography.bodyMedium,
        fontSize = 14.sp
    )
    Slider(
        value = state.scrimAlpha,
        onValueChange = { appearanceSettings.setScrimAlpha(it) },
        valueRange = AppearanceLogic.SCRIM_ALPHA_MIN..AppearanceLogic.SCRIM_ALPHA_MAX,
        modifier = Modifier.fillMaxWidth()
    )

    // --- Text size (P2 stage 5) ---
    Spacer(modifier = Modifier.height(16.dp))
    Text(
        text = "文字大小：×${"%.2f".format(state.textSizeScale)}",
        style = MaterialTheme.typography.bodyMedium,
        fontSize = 14.sp
    )
    Slider(
        value = state.textSizeScale,
        onValueChange = { appearanceSettings.setTextSizeScale(it) },
        valueRange = AppearanceLogic.TEXT_SIZE_SCALE_MIN..AppearanceLogic.TEXT_SIZE_SCALE_MAX,
        modifier = Modifier.fillMaxWidth()
    )
    Text(
        text = "作用于全应用界面文字；页内歌词与悬浮歌词各有自己的字号设置，不跟随这里。",
        style = MaterialTheme.typography.bodyMedium,
        fontSize = 14.sp
    )

    // --- Text colour (P2 stage 5) ---
    Spacer(modifier = Modifier.height(16.dp))
    Text(
        text = "文字颜色",
        style = MaterialTheme.typography.bodyMedium,
        fontSize = 14.sp
    )
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = "当前：" + (
            state.textColor?.let { AppearanceLogic.formatColorHex(it) } ?: "主题默认"
        ),
        style = MaterialTheme.typography.bodyMedium,
        fontSize = 14.sp
    )
    Spacer(modifier = Modifier.height(8.dp))
    // Light swatches only: text sits on a dark background, so the
    // dark background presets are NOT reused here — they would be
    // unreadable as text. The default swatch is the theme default.
    TEXT_COLOR_PRESETS.chunked(5).forEach { row ->
        Row {
            row.forEach { preset ->
                val selected = if (preset.argb == null) {
                    state.textColor == null
                } else {
                    state.textColor == preset.argb
                }
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(
                            Color(
                                preset.argb
                                    ?: AppearanceLogic.DEFAULT_TEXT_COLOR_ARGB
                            )
                        )
                        .border(
                            width = if (selected) 2.dp else 1.dp,
                            color = if (selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outline,
                            shape = CircleShape
                        )
                        .clickable {
                            if (preset.argb == null) {
                                appearanceSettings.clearTextColor()
                            } else {
                                appearanceSettings.setTextColor(preset.argb)
                            }
                        }
                )
                Spacer(modifier = Modifier.width(12.dp))
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        TextField(
            value = textColorText,
            onValueChange = {
                textColorText = it
                textColorError = false
            },
            label = { Text(text = "色值，如 #F0F0EF") },
            singleLine = true,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = {
            val parsed = AppearanceLogic.parseColorHex(textColorText)
            if (parsed == null) {
                textColorError = true
            } else {
                textColorError = false
                appearanceSettings.setTextColor(parsed)
            }
        }) {
            Text(text = "应用")
        }
    }
    if (textColorError) {
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "色值格式不对，用 #RRGGBB 或 #AARRGGBB。",
            style = MaterialTheme.typography.bodyMedium,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.error
        )
    }
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = "文字颜色作用于界面主文字与次文字；错误提示等语义颜色不变。" +
            "选深色在深色背景上会看不清，请用浅色。",
        style = MaterialTheme.typography.bodyMedium,
        fontSize = 14.sp
    )
    if (state.textColor != null) {
        TextButton(onClick = {
            appearanceSettings.clearTextColor()
            textColorError = false
        }) {
            Text(text = "恢复默认文字颜色")
        }
    }
}

/** Light text-colour swatches for `text_color` (null = theme default). */
private data class TextColorPreset(val name: String, val argb: Long?)

private val TEXT_COLOR_PRESETS = listOf(
    TextColorPreset("默认", null),
    TextColorPreset("暖白", 0xFFE8DFD2L),
    TextColorPreset("冷白", 0xFFD8E2EAL),
    TextColorPreset("淡粉", 0xFFEAD9DEL),
    TextColorPreset("淡绿", 0xFFD9E6D8L)
)

@Composable
private fun AppearanceModeRow(
    label: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(modifier = Modifier.padding(start = 4.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                fontSize = 14.sp
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
