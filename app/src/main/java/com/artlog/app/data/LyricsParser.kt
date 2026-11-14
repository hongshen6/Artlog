package com.artlog.app.data

import java.io.File
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * 歌词解析与内嵌歌词读取（下一阶段迭代，阶段 5 / 7）。
 *
 * 纯 Kotlin，不依赖 Android 框架（除 [File] 外），可在 JVM 上直接
 * 测试（TestMusic / TestLyrics）。
 *
 * 歌词来源（只来自本地文件或音乐文件自身元数据，不接入任何在线
 * 歌词服务，不抓取、不下载、不上传）：
 * - LRC 文件：[parseLrc]。
 * - MP3 内嵌歌词：ID3v2 的 USLT 帧（[extractEmbeddedLyrics]）。
 * - M4A 内嵌歌词：`udta/meta/ilst` 下的 `©lyr` atom。
 *
 * 不做的事：MP3 的 SYLT 同步歌词本轮不做——遇 SYLT 按 USLT 缺失
 * 处理（返回 null），不报错；ID3v2.2（3 字节帧头）不做。
 *
 * `MediaMetadataRetriever` 经实测 40 项 `METADATA_KEY_*` 中无歌词
 * 项，因此内嵌歌词只能手写读取（见 implementation-plan §5.1）。
 */

/** 一行歌词。[timeMs] 为 null 表示该行无时间标签（整段展示用）。 */
data class LyricLine(
    val timeMs: Long?,
    val text: String
)

/** 一首歌词的解析结果。 */
data class ParsedLyrics(
    /** 全部行（文件顺序；无时间标签的行 [LyricLine.timeMs] 为 null）。 */
    val lines: List<LyricLine>,
    /** `[ti:/ar:/al:/by:]` 等元信息行（展示用，不参与同步）。 */
    val metadata: Map<String, String>,
    /** `[offset:+/-ms]` 的值（毫秒），默认 0。 */
    val offsetMs: Long = 0L
) {
    /** 有时间标签的行，按时间升序（供同步匹配用）。 */
    val timedLines: List<LyricLine>
        get() = lines.filter { it.timeMs != null }.sortedBy { it.timeMs!! }

    /** 是否含时间轴（至少一行有时间标签）。 */
    val hasTimestamps: Boolean
        get() = lines.any { it.timeMs != null }
}

/**
 * LRC 解析器。规则：
 * - 时间标签 `[mm:ss.xx]`：支持 `[mm:ss]` 与毫秒 1–3 位（规范要求
 *   2–3 位，1 位也接受，按十进制补齐）；一行可带多个时间标签，
 *   每个标签各生成一行；毫秒分隔符接受 `.` 或 `:`。
 * - `[offset:+/-ms]`：参与计算，有效时间 = 标签时间 + offset
 *   （正 offset 推迟显示，负 offset 提前显示）。
 * - `[ti:/ar:/al:/au:/by:/length:]` 等元信息行只存 [ParsedLyrics.metadata]，
 *   不生成歌词行。
 * - 无时间标签的行按无时间轴文本处理（[LyricLine.timeMs] 为 null）。
 * - 非法行丢弃、不崩溃：以 `[` 开头、含 `]`、但既不是有效时间标
 *   签也不是已知元信息标签的行直接丢弃；空行丢弃。
 */
object LyricsParser {

    private val TIME_TAG_REGEX =
        Regex("""\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?\]""")
    private val OFFSET_REGEX =
        Regex("""^\s*\[offset:\s*([+-]?\d+)\s*\]\s*$""", RegexOption.IGNORE_CASE)
    private val METADATA_REGEX =
        Regex("""^\s*\[(ti|ar|al|au|by|length|re|ve|#):(.*)\]\s*$""", RegexOption.IGNORE_CASE)

    /**
     * 把 LRC 文件字节解码为文本（P0 编码兜底）。
     *
     * 顺序：去 UTF-8 BOM → 严格 UTF-8 → GBK → 宽松 UTF-8。
     * 严格解码失败、或解出替换字符（U+FFFD，典型 GBK 文本被当成
     * UTF-8 读的症状）时，改试 GBK；GBK 也解不出时回退宽松
     * UTF-8（不丢文本、不抛异常）。空字节返回空串。
     * 纯函数（TestLyricsP0 覆盖）。
     */
    fun decodeLrcBytes(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        var data = bytes
        // UTF-8 BOM（EF BB BF）：去掉，否则首行时间标签前多一个
        // \uFEFF，解析仍能工作但元数据行会带上不可见字符。
        if (data.size >= 3 && data[0] == 0xEF.toByte() &&
            data[1] == 0xBB.toByte() && data[2] == 0xBF.toByte()
        ) {
            data = data.copyOfRange(3, data.size)
        }
        val strictUtf8 = decodeStrict(data, StandardCharsets.UTF_8)
        if (strictUtf8 != null && !strictUtf8.contains('\uFFFD')) return strictUtf8
        val gbk = decodeStrict(data, charset("GBK"))
        if (gbk != null && !gbk.contains('\uFFFD')) return gbk
        // 两路都失败：宽松 UTF-8 兜底（替换字符保留原文轮廓，
        // 不崩溃、不丢整首歌词）。
        return String(data, StandardCharsets.UTF_8)
    }

    /** 严格解码：遇非法字节返回 null（不抛异常）。 */
    private fun decodeStrict(bytes: ByteArray, charset: java.nio.charset.Charset): String? {
        return try {
            val decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        } catch (_: Exception) {
            null
        }
    }

    /** 解析一段 LRC 文本。永不抛异常：非法行只丢该行。 */
    fun parseLrc(text: String): ParsedLyrics {
        val lines = mutableListOf<LyricLine>()
        val metadata = mutableMapOf<String, String>()
        var offsetMs = 0L
        for (rawLine in text.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            val offsetMatch = OFFSET_REGEX.matchEntire(line)
            if (offsetMatch != null) {
                offsetMs = offsetMatch.groupValues[1].toLongOrNull() ?: 0L
                continue
            }
            val metaMatch = METADATA_REGEX.matchEntire(line)
            if (metaMatch != null) {
                metadata[metaMatch.groupValues[1].lowercase()] =
                    metaMatch.groupValues[2].trim()
                continue
            }
            val tags = TIME_TAG_REGEX.findAll(line).toList()
            if (tags.isNotEmpty()) {
                // 标签后的文本：去掉全部时间标签后剩余的部分。
                val lyricText = TIME_TAG_REGEX.replace(line, "").trim()
                if (lyricText.isEmpty()) continue
                for (tag in tags) {
                    val minutes = tag.groupValues[1].toLongOrNull() ?: continue
                    val seconds = tag.groupValues[2].toLongOrNull() ?: continue
                    if (seconds >= 60) continue
                    val fraction = tag.groupValues[3]
                    val millis = when (fraction.length) {
                        0 -> 0L
                        1 -> fraction.toLong() * 100
                        2 -> fraction.toLong() * 10
                        else -> fraction.toLong()
                    }
                    val timeMs = minutes * 60_000 + seconds * 1_000 + millis + offsetMs
                    if (timeMs < 0) continue
                    lines.add(LyricLine(timeMs, lyricText))
                }
                continue
            }
            // 以 "[" 开头且含 "]"、但无有效时间标签：非法行，丢弃。
            if (line.startsWith("[") && line.contains("]")) continue
            lines.add(LyricLine(null, line))
        }
        return ParsedLyrics(lines, metadata, offsetMs)
    }

    /**
     * 按播放进度匹配当前歌词行：返回 [timedLines] 中时间标签
     * ≤ [positionMs] 的最后一行下标；进度在第一行之前、或无时间
     * 轴行时返回 -1。纯函数（TestLyrics 覆盖）。
     */
    fun findCurrentLineIndex(timedLines: List<LyricLine>, positionMs: Long): Int {
        var current = -1
        for ((index, line) in timedLines.withIndex()) {
            val time = line.timeMs ?: continue
            if (time <= positionMs) current = index else break
        }
        return current
    }

    /** [findCurrentLineIndex] 的行对象版；无匹配返回 null。 */
    fun currentLineAt(parsed: ParsedLyrics, positionMs: Long): LyricLine? {
        val timed = parsed.timedLines
        val index = findCurrentLineIndex(timed, positionMs)
        return if (index >= 0) timed[index] else null
    }

    /**
     * 从音频文件字节中读内嵌歌词：MP3（ID3v2 USLT）或 M4A
     * （`©lyr` atom）。读不到返回 null，不抛异常、不阻塞导入。
     * SYLT 帧按缺失处理。
     */
    fun extractEmbeddedLyrics(bytes: ByteArray): String? {
        if (bytes.size < 10) return null
        return extractUslt(bytes) ?: extractMp4Lyrics(bytes)
    }

    /** 从音频文件读内嵌歌词（读文件失败返回 null）。 */
    fun readEmbeddedLyrics(file: File): String? {
        return try {
            if (!file.isFile) null else extractEmbeddedLyrics(file.readBytes())
        } catch (_: Exception) {
            null
        }
    }

    // ---- MP3 / ID3v2 USLT ----

    private fun extractUslt(bytes: ByteArray): String? {
        if (!(bytes[0] == 'I'.code.toByte() && bytes[1] == 'D'.code.toByte() &&
                bytes[2] == '3'.code.toByte())
        ) return null
        val version = bytes[3].toInt() and 0xFF
        if (version !in 3..4) return null
        val flags = bytes[5].toInt() and 0xFF
        val tagSize = synchsafeInt(bytes, 6)
        if (tagSize <= 0 || tagSize > bytes.size - 10) return null
        var pos = 10
        val tagEnd = 10 + tagSize
        // 扩展头（flag 0x40）：v2.3 为 4 字节大端长度，v2.4 为 synchsafe。
        if (flags and 0x40 != 0) {
            if (pos + 4 > tagEnd) return null
            val extSize = if (version == 4) synchsafeInt(bytes, pos)
                else int32BE(bytes, pos)
            pos += 4 + extSize
        }
        while (pos + 10 <= tagEnd) {
            val frameId = String(bytes, pos, 4, Charsets.US_ASCII)
            if (frameId.all { it == '\u0000' }) break
            if (!frameId.all { it in 'A'..'Z' || it in '0'..'9' }) break
            val frameSize = if (version == 4) synchsafeInt(bytes, pos + 4)
                else int32BE(bytes, pos + 4)
            if (frameSize <= 0 || pos + 10 + frameSize > tagEnd) break
            if (frameId == "USLT") {
                val text = decodeUsltFrame(bytes, pos + 10, frameSize)
                if (!text.isNullOrBlank()) return text
            }
            // SYLT 及其他帧：跳过，不报错。
            pos += 10 + frameSize
        }
        return null
    }

    /**
     * USLT 帧体：编码字节（0=ISO-8859-1，1=UTF-16 含 BOM，
     * 2=UTF-16BE，3=UTF-8）+ 3 字节 language + 描述（按编码结尾的
     * 空字符终止）+ 歌词正文。
     */
    private fun decodeUsltFrame(bytes: ByteArray, start: Int, size: Int): String? {
        if (size < 4) return null
        val encoding = bytes[start].toInt() and 0xFF
        if (encoding !in 0..3) return null
        // 跳过 language 字段（3 字节），再跳过描述的终止符。
        var textStart = start + 4
        val end = start + size
        textStart = skipTerminated(bytes, textStart, end, encoding)
        if (textStart >= end) return null
        val textBytes = bytes.copyOfRange(textStart, end)
        val text = decodeText(textBytes, encoding)
            ?.trim('\u0000', ' ', '\n', '\r', '\t')
        return text?.takeIf { it.isNotEmpty() }
    }

    /** 跳过一个按 [encoding] 终止的描述字段，返回正文起始下标。 */
    private fun skipTerminated(
        bytes: ByteArray,
        start: Int,
        end: Int,
        encoding: Int
    ): Int {
        var i = start
        if (encoding == 1 || encoding == 2) {
            while (i + 1 < end) {
                if (bytes[i] == 0.toByte() && bytes[i + 1] == 0.toByte()) return i + 2
                i += 2
            }
            return end
        }
        while (i < end) {
            if (bytes[i] == 0.toByte()) return i + 1
            i++
        }
        return end
    }

    private fun decodeText(bytes: ByteArray, encoding: Int): String? {
        return try {
            when (encoding) {
                0 -> String(bytes, Charsets.ISO_8859_1)
                1 -> {
                    // UTF-16 含 BOM：FF FE 为小端、FE FF 为大端；
                    // 解码前跳过 BOM 本身，否则会留下一个 \uFEFF 字符。
                    when {
                        bytes.size >= 2 && bytes[0] == 0xFF.toByte() &&
                            bytes[1] == 0xFE.toByte() ->
                            String(bytes.copyOfRange(2, bytes.size), Charsets.UTF_16LE)
                        bytes.size >= 2 && bytes[0] == 0xFE.toByte() &&
                            bytes[1] == 0xFF.toByte() ->
                            String(bytes.copyOfRange(2, bytes.size), Charsets.UTF_16BE)
                        else -> String(bytes, Charsets.UTF_16LE)
                    }
                }
                2 -> String(bytes, Charsets.UTF_16BE)
                3 -> String(bytes, Charsets.UTF_8)
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    // ---- M4A / MP4 ©lyr ----

    private fun extractMp4Lyrics(bytes: ByteArray): String? {
        // atom 类型 "©lyr" = 0xA9 'l' 'y' 'r'。直接在全文件扫描：
        // M4A 的 udta/meta/ilst 嵌套层次各家写法不一，逐层走 atom
        // 树容易因未知变体漏掉；按类型字节扫描更稳健。
        val lyrMarker = byteArrayOf(0xA9.toByte(), 'l'.code.toByte(), 'y'.code.toByte(), 'r'.code.toByte())
        val lyrIndex = indexOf(bytes, lyrMarker, 0) ?: return null
        val dataMarker = "data".toByteArray(Charsets.US_ASCII)
        val dataIndex = indexOf(bytes, dataMarker, lyrIndex + 4) ?: return null
        // data atom：size(4) 在 "data" 前 4 字节；体 = 类型(4) + locale(4) + 正文。
        if (dataIndex + 12 > bytes.size) return null
        val atomSize = int32BE(bytes, dataIndex - 4)
        if (atomSize < 16) return null
        val payloadStart = dataIndex + 12
        val payloadEnd = minOf(dataIndex - 4 + atomSize, bytes.size)
        if (payloadEnd <= payloadStart) return null
        val payload = bytes.copyOfRange(payloadStart, payloadEnd)
        val typeIndicator = int32BE(bytes, dataIndex + 4)
        val text = when (typeIndicator) {
            2 -> runCatching { String(payload, Charsets.UTF_16) }.getOrNull()
            else -> runCatching { String(payload, Charsets.UTF_8) }.getOrNull()
                ?: runCatching { String(payload, Charsets.UTF_16) }.getOrNull()
        }?.trim('\u0000', ' ', '\n', '\r', '\t')
        return text?.takeIf { it.isNotEmpty() }
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int): Int? {
        if (needle.isEmpty() || haystack.size < needle.size) return null
        outer@ for (i in from..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return null
    }

    /** 4 字节 synchsafe 整数（ID3v2 tag/帧长度，v2.4）。 */
    private fun synchsafeInt(bytes: ByteArray, offset: Int): Int {
        if (offset + 4 > bytes.size) return 0
        return ((bytes[offset].toInt() and 0x7F) shl 21) or
            ((bytes[offset + 1].toInt() and 0x7F) shl 14) or
            ((bytes[offset + 2].toInt() and 0x7F) shl 7) or
            (bytes[offset + 3].toInt() and 0x7F)
    }

    /** 4 字节大端整数（ID3v2.3 帧长度、MP4 atom 长度）。 */
    private fun int32BE(bytes: ByteArray, offset: Int): Int {
        if (offset + 4 > bytes.size) return 0
        return ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
    }
}
