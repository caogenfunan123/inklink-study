package com.inklink.host.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface PetDao {

    @Upsert
    fun upsertBag(row: PetBagRow)

    @Query("SELECT bagJson FROM pet_bag WHERE id = :id LIMIT 1")
    fun loadBagJson(id: Int = PetBagRow.SINGLETON_ID): String?

    // ---------------- 事件日志（环形裁剪） ----------------

    @Insert
    fun insertLog(entry: EventLogEntry): Long

    @Query("SELECT * FROM event_log ORDER BY ts DESC LIMIT :limit")
    fun recentLogs(limit: Int = 200): List<EventLogEntry>

    @Query("SELECT COUNT(*) FROM event_log")
    fun logCount(): Int

    // ---------------- 测试专用 ----------------

    @Query("DELETE FROM pet_bag")
    fun clearBag()

    @Query("DELETE FROM event_log")
    fun clearLogs()

    /** 删除超出环形上限的最旧记录，保留最近 keep 条。 */
    @Query(
        "DELETE FROM event_log WHERE id NOT IN " +
            "(SELECT id FROM event_log ORDER BY ts DESC LIMIT :keep)"
    )
    fun trimLogTo(keep: Int)
}
