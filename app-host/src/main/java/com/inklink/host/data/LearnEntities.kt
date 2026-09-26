package com.inklink.host.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 学习进度表:按 (module, itemId) 记录每个知识点(如单个汉字)的掌握状态。
 * 复习调度:答对 reviewStage+1 → nextReviewTs = now + 间隔[stage] 天(1/2/4/7/15);
 * 答错回退 stage 0。reviewStage 走满间隔表即 MASTERED。
 */
@Entity(tableName = "learn_progress", primaryKeys = ["module", "itemId"])
data class LearnProgressRow(
    val module: String,
    val itemId: String,
    var status: String = "LEARNING", // LEARNING | MASTERED
    var correctCount: Int = 0,
    var wrongCount: Int = 0,
    var lastTs: Long = 0,
    var reviewStage: Int = 0,
    var nextReviewTs: Long = 0,
    var level: Int = 2
)

/** 错题本(M1 只记不展示,复习谷 P0-4 在 M2 消费)。 */
@Entity(
    tableName = "wrong_book",
    indices = [Index("module"), Index("resolvedTs")]
)
data class WrongBookRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val module: String,
    val itemId: String,
    val payloadJson: String = "",
    val wrongTs: Long = System.currentTimeMillis(),
    var resolvedTs: Long = 0
)

/** 每日学习统计:按 (date, module) 累计;金币日上限用 SUM(coinsEarned) 跨模块核算。 */
@Entity(tableName = "daily_stats", primaryKeys = ["date", "module"])
data class DailyStatsRow(
    val date: String,
    val module: String,
    var minutes: Int = 0,
    var itemsDone: Int = 0,
    var correctSum: Int = 0,
    var coinsEarned: Int = 0,
    var expEarned: Int = 0
)
