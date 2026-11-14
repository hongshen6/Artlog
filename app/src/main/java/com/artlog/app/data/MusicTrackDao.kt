package com.artlog.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * 音乐曲库的数据访问（下一阶段迭代，数据库 v6 新表
 * `music_tracks`，见 [MusicTrack]）。最小 v6 起注册进
 * [ArtworkDatabase]（MIGRATION_5_6 建表），经
 * [MusicTrackRepository] 与 [BackupManager] 接线。
 */
@Dao
interface MusicTrackDao {

    /** 按曲目 ID 查询一行；不存在返回 null。 */
    @Query("SELECT * FROM music_tracks WHERE id = :id")
    suspend fun getTrack(id: String): MusicTrack?

    /** 全部曲目（按导入时间倒序），供曲库列表与备份计数使用。 */
    @Query("SELECT * FROM music_tracks ORDER BY importedAt DESC")
    fun observeTracks(): Flow<List<MusicTrack>>

    /** 按标题/艺术家模糊搜索（按导入时间倒序）。 */
    @Query(
        "SELECT * FROM music_tracks " +
            "WHERE title LIKE '%' || :query || '%' " +
            "OR artist LIKE '%' || :query || '%' " +
            "ORDER BY importedAt DESC"
    )
    fun searchTracks(query: String): Flow<List<MusicTrack>>

    /** 曲目行数（备份校验用）。 */
    @Query("SELECT COUNT(*) FROM music_tracks")
    suspend fun countTracks(): Int

    /** 插入或覆盖一行曲目。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrack(track: MusicTrack)

    /** 更新一行曲目。 */
    @Update
    suspend fun updateTrack(track: MusicTrack)

    /** 按曲目 ID 删除一行（只删行；音频/LRC 文件由调用方删除）。 */
    @Query("DELETE FROM music_tracks WHERE id = :id")
    suspend fun deleteTrack(id: String)

    /** 删除全部曲目行（仅备份恢复的整表交换用）。 */
    @Query("DELETE FROM music_tracks")
    suspend fun deleteAllTracks()
}
