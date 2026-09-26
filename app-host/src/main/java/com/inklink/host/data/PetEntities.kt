package com.inklink.host.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 宠物背包整包持久化行。
 *
 * 设计取舍：`PetBag`（含 petList/性格/道具库存/装饰/里程碑/每日目标）由进程级
 * `PetStateManager` 单例持有并高频内存运算，落盘时整包序列化为一段 JSON 存入本表单行。
 * 相比逐字段建表，这与旧的 pet_bag.json 语义一致但获得事务性与 Room 迁移钩子，
 * 且天然契合"10-30s 节流整包 upsert"的写策略（避免秒级衰减逐字段刷库）。
 * 真正需要按行查询/裁剪的事件日志独立建表 [EventLogEntry]。
 */
@Entity(tableName = "pet_bag")
data class PetBagRow(
    @PrimaryKey val id: Int = SINGLETON_ID,
    /** Gson 序列化的完整 PetBag */
    val bagJson: String,
    /** 写入时间戳，便于诊断 */
    val savedTs: Long = System.currentTimeMillis()
) {
    companion object {
        const val SINGLETON_ID = 1
    }
}

/**
 * 宠物事件日志（V1.1 六.游戏化：随机事件 / 成长里程碑 / 照料记录）。
 * 环形存储，全局上限 [com.inklink.host.data.PetDatabase.MAX_LOG] 条，超出裁剪最旧。
 */
@Entity(tableName = "event_log")
data class EventLogEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val petId: String,
    /** HATCH / LEVEL_UP / FORM / RANDOM_GOOD / RANDOM_BAD / VISIT / GIFT / MILESTONE / DAILY / CARE / REVIVE */
    val type: String,
    val message: String,
    val ts: Long
)
