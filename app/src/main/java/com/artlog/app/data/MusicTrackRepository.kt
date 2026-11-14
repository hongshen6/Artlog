package com.artlog.app.data

import kotlinx.coroutines.flow.Flow

/**
 * 音乐曲库的 Repository（下一阶段迭代，数据库 v6）。
 *
 * 本阶段只做数据层：曲库 CRUD / 查询。导入流程（SAF 选取 → 复
 * 制到私有目录 → 读元数据/歌词 → 写行）与删除时的文件清理由后
 * 续阶段做；[deleteTrack] 只删数据库行，调用方须同步删音频文件
 * 与 LRC 文件（删文件失败要报错，不静默留孤儿文件）。
 *
 * 本类不依赖 Android 框架（除 Room DAO 接口外），可在 JVM 上直接
 * 用假 DAO 测试（TestMusic 由后续阶段做，本阶段不抢）。
 */
class MusicTrackRepository(private val dao: MusicTrackDao) {

    /** 全部曲目（按导入时间倒序）。 */
    fun observeTracks(): Flow<List<MusicTrack>> = dao.observeTracks()

    /** 按标题/艺术家模糊搜索（按导入时间倒序）。 */
    fun searchTracks(query: String): Flow<List<MusicTrack>> = dao.searchTracks(query)

    /** 按曲目 ID 查询一行；不存在返回 null。 */
    suspend fun getTrack(id: String): MusicTrack? = dao.getTrack(id)

    /** 曲目行数。 */
    suspend fun countTracks(): Int = dao.countTracks()

    /** 插入一行曲目（同一 id 再次插入时覆盖）。 */
    suspend fun insertTrack(track: MusicTrack) {
        dao.insertTrack(track)
    }

    /** 更新一行曲目。 */
    suspend fun updateTrack(track: MusicTrack) {
        dao.updateTrack(track)
    }

    /**
     * 删除一行曲目（只删数据库行）。调用方须同步删音频文件与
     * LRC 文件；删文件失败要报错，不静默留孤儿文件。
     */
    suspend fun deleteTrack(id: String) {
        dao.deleteTrack(id)
    }
}
