package com.artlog.app.playback

import kotlin.random.Random

/**
 * 循环模式：顺序播放 / 列表循环 / 单曲循环。
 *
 * P1 之前只有 ORDER / SINGLE 两档（见 MusicViewModel 的旧枚举，
 * 本轮移至此处，以便纯逻辑 [PlaybackModeLogic] 与 JVM 测试直接
 * 引用，不依赖 Android 框架类）。
 */
enum class MusicRepeatMode {
    ORDER, ALL, SINGLE
}

/**
 * 播放模式的纯决策逻辑（P1：列表循环 + 随机播放）。
 *
 * 不依赖 Android 框架与 Media3（数值常量与
 * [androidx.media3.common.Player] 的 repeatMode 常量一致：
 * REPEAT_MODE_OFF=0、REPEAT_MODE_ONE=1、REPEAT_MODE_ALL=2，
 * Media3 公开 API 的稳定取值；调用方 [MusicPlaybackState] 在
 * 与 MediaController 交接时传入系统常量，见
 * [PlaybackModeLogic.toPlayerRepeatMode] 注释）。
 * 所有函数都是其输入的纯函数，可在 JVM 上直接测试
 * （TestPlaybackMode）；[MusicPlaybackState] 只做接线（读写
 * MediaController、持久化、重下发队列），不另写决策。
 *
 * 关键语义（与任务书一致）：
 * - 列表循环用 ExoPlayer 原生 REPEAT_MODE_ALL，不做应用层手动
 *   回绕；三档切换顺序 ORDER → ALL → SINGLE → ORDER。
 * - 随机播放用应用层洗牌序列（Fisher-Yates），不用 ExoPlayer
 *   内置 setShuffleModeEnabled（会与 holder 的下标语义脱节）；
 *   洗牌序列只存内存，不持久化、不进数据库。
 * - 随机开启时当前歌保留为序列起点；一轮内每首恰好一次；
 *   ALL + 随机时每轮结束重洗，新一轮第一首不等于上一轮最后
 *   一首（列表 ≥ 2 首时）；关闭后恢复曲库原顺序，从当前歌
 *   在原顺序中的位置继续。
 */
object PlaybackModeLogic {

    /** 与 Player.REPEAT_MODE_OFF 相同的取值（见文件头注释）。 */
    const val PLAYER_REPEAT_MODE_OFF = 0

    /** 与 Player.REPEAT_MODE_ONE 相同的取值（见文件头注释）。 */
    const val PLAYER_REPEAT_MODE_ONE = 1

    /** 与 Player.REPEAT_MODE_ALL 相同的取值（见文件头注释）。 */
    const val PLAYER_REPEAT_MODE_ALL = 2

    /** 三档循环切换：ORDER → ALL → SINGLE → ORDER。 */
    fun nextMode(current: MusicRepeatMode): MusicRepeatMode = when (current) {
        MusicRepeatMode.ORDER -> MusicRepeatMode.ALL
        MusicRepeatMode.ALL -> MusicRepeatMode.SINGLE
        MusicRepeatMode.SINGLE -> MusicRepeatMode.ORDER
    }

    /** UI 文字：三档各有区分（列表循环不用图标区分，靠文字）。 */
    fun label(mode: MusicRepeatMode): String = when (mode) {
        MusicRepeatMode.ORDER -> "顺序播放"
        MusicRepeatMode.ALL -> "列表循环"
        MusicRepeatMode.SINGLE -> "单曲循环"
    }

    /**
     * 档位 → ExoPlayer repeatMode。返回的 Int 可直接赋给
     * `MediaController.repeatMode`（取值与 Player 常量逐一相同，
     * 见上方常量注释）。
     */
    fun toPlayerRepeatMode(mode: MusicRepeatMode): Int = when (mode) {
        MusicRepeatMode.ORDER -> PLAYER_REPEAT_MODE_OFF
        MusicRepeatMode.ALL -> PLAYER_REPEAT_MODE_ALL
        MusicRepeatMode.SINGLE -> PLAYER_REPEAT_MODE_ONE
    }

    /**
     * ExoPlayer repeatMode → 档位。未知取值（旧版本 / 外部写入）
     * 一律回落到 ORDER，不崩溃。
     */
    fun fromPlayerRepeatMode(value: Int): MusicRepeatMode = when (value) {
        PLAYER_REPEAT_MODE_ONE -> MusicRepeatMode.SINGLE
        PLAYER_REPEAT_MODE_ALL -> MusicRepeatMode.ALL
        else -> MusicRepeatMode.ORDER
    }

    /**
     * 解析持久化的 `repeat_mode` 字符串（存枚举名）。
     * null / 空白 / 未知值一律回落到 ORDER，不崩溃（与
     * AppearanceLogic.parseMode 的风格一致）。
     */
    fun parseRepeatMode(raw: String?): MusicRepeatMode = when (raw?.trim()) {
        MusicRepeatMode.ALL.name -> MusicRepeatMode.ALL
        MusicRepeatMode.SINGLE.name -> MusicRepeatMode.SINGLE
        else -> MusicRepeatMode.ORDER
    }

    /**
     * 建洗牌序列（Fisher-Yates）：[startId] 为第一首（当前歌），
     * 其余 id 打乱排在其后。
     *
     * - 空列表 → 空；单首 → 只有该首（“避免连续同首”不适用，
     *   不卡住、不报错）。
     * - [startId] 不在 [ids] 中时（例如当前歌已被删），用 [ids]
     *   的第一首作起点，其余打乱。
     * - [random] 由调用方传入（生产用默认随机；测试用固定种子）。
     */
    fun buildShuffleOrder(
        ids: List<String>,
        startId: String?,
        random: Random = Random.Default
    ): List<String> {
        if (ids.isEmpty()) return emptyList()
        if (ids.size == 1) return ids.toList()
        val start = if (startId != null && startId in ids) startId else ids.first()
        val rest = ids.filter { it != start }.toMutableList()
        // Fisher-Yates：从尾部向前交换，保证每种排列等概率。
        for (i in rest.size - 1 downTo 1) {
            val j = random.nextInt(i + 1)
            val tmp = rest[i]
            rest[i] = rest[j]
            rest[j] = tmp
        }
        return listOf(start) + rest
    }

    /**
     * 为下一轮重洗（ALL + 随机在原生回绕点调用）。
     *
     * - 从 [allIds]（当前曲库 id）重洗，不沿用上一轮顺序；
     * - 列表 ≥ 2 首时，新一轮第一首不等于上一轮最后一首
     *   （[previousOrder].lastOrNull()）；最多重试 10 次仍撞上
     *   时接受该结果（不死循环——只有上一轮恰好只剩两种排列且
     *   反复撞上时才可能发生，概率极低且行为仍正确：只是新一轮
     *   第一首等于上一轮最后一首，会多播一次同一首的开头，
     *   随后即进入新顺序）。
     * - 空列表 → 空；单首 → 只有该首。
     */
    fun reshuffleForNextRound(
        previousOrder: List<String>,
        allIds: List<String>,
        random: Random = Random.Default
    ): List<String> {
        if (allIds.isEmpty()) return emptyList()
        if (allIds.size == 1) return allIds.toList()
        val previousLast = previousOrder.lastOrNull()
        var attempt = 0
        while (true) {
            val shuffled = allIds.shuffled(random)
            if (shuffled.first() != previousLast || attempt >= 10) return shuffled
            attempt++
        }
    }

    /** 删当前歌后的续播决策结果。 */
    sealed interface DeleteNext {
        /** 停播（列表已空、删的是 ORDER 末首、SINGLE 模式等）。 */
        data object Stop : DeleteNext

        /** 继续播 [nextId]；续播队列为 [newOrder]（已不含已删的歌）。 */
        data class Play(val nextId: String, val newOrder: List<String>) : DeleteNext
    }

    /**
     * 删正在播放的歌时，按当前模式算出下一首（调用方须在清播放
     * 状态前调用，否则队列已空、无从得知）。
     *
     * @param order 当前有效队列顺序：随机开时为洗牌序列，否则为
     *   曲库原顺序。
     * @param deletedId 被删的当前歌 id。
     * @param repeatMode 当前循环档位；[shuffleEnabled] 当前随机开关。
     * @return [DeleteNext.Stop] 或 [DeleteNext.Play]（含续播队列）。
     *
     * 语义表：
     * - 删后列表为空 → Stop。
     * - 删的不是队尾 → 下一首是原顺序中被删歌的后一首（随机模式
     *   下是洗牌序列的下一首），队列为原顺序去掉已删的歌。
     * - 删的是队尾：ORDER → Stop（播完一轮即停）；SINGLE → Stop
     *   （单曲循环的重复对象已被删，无定义的下一首）；ALL +
     *   非随机 → 回绕到剩余列表第一首；ALL + 随机 → 重洗一轮，
     *   下一首为新一轮第一首。
     * - [deletedId] 不在 [order] 中 → Stop（防御分支，不应发生）。
     */
    fun nextAfterDeletion(
        order: List<String>,
        deletedId: String,
        repeatMode: MusicRepeatMode,
        shuffleEnabled: Boolean,
        random: Random = Random.Default
    ): DeleteNext {
        val remaining = order.filter { it != deletedId }
        if (remaining.isEmpty()) return DeleteNext.Stop
        val index = order.indexOf(deletedId)
        if (index < 0) return DeleteNext.Stop
        if (index < order.lastIndex) {
            // 被删歌的后一首仍在剩余列表中同一位置。
            return DeleteNext.Play(remaining[index], remaining)
        }
        // 删的是队尾。
        return when (repeatMode) {
            MusicRepeatMode.ORDER -> DeleteNext.Stop
            MusicRepeatMode.SINGLE -> DeleteNext.Stop
            MusicRepeatMode.ALL -> {
                if (shuffleEnabled) {
                    val newRound = reshuffleForNextRound(order, remaining, random)
                    DeleteNext.Play(newRound.first(), newRound)
                } else {
                    DeleteNext.Play(remaining.first(), remaining)
                }
            }
        }
    }

    /**
     * 当前有效队列顺序（id 列表）。
     *
     * 随机开且洗牌序列非空时用洗牌序列（播放中新增的曲目补到末尾、
     * 已删的丢弃——序列本身由 holder 在切换 / 删歌时维护，此处只
     * 做容错对齐，不写回）；否则用曲库原顺序。
     */
    fun effectiveOrderIds(
        libraryIds: List<String>,
        shuffleEnabled: Boolean,
        shuffledOrder: List<String>
    ): List<String> {
        if (!shuffleEnabled || shuffledOrder.isEmpty()) return libraryIds.toList()
        val known = shuffledOrder.filter { it in libraryIds }
        val fresh = libraryIds.filter { it !in known }
        return known + fresh
    }
}
