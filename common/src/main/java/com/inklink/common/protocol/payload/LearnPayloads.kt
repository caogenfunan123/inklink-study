package com.inklink.common.protocol.payload

import com.google.gson.annotations.SerializedName

/**
 * 学习进度上报 (LEARN_PROGRESS / 47):受控端 → 主控端。
 * 关卡结束发单关粒度;每日汇总由 [dailyTotals] 承载(同码位双粒度,按 [scope] 区分)。
 */
data class LearnProgressPayload(
    @SerializedName("scope") val scope: String = "LESSON", // LESSON | DAILY
    @SerializedName("module") val module: String,          // HANZI | PINYIN | MATH | POEM | ENGLISH | REVIEW
    @SerializedName("level") val level: Int = 2,           // 内容分级 L1-L4
    @SerializedName("itemsDone") val itemsDone: Int = 0,
    @SerializedName("correctRate") val correctRate: Double = 0.0,
    @SerializedName("minutesToday") val minutesToday: Int = 0,
    @SerializedName("coinsToday") val coinsToday: Int = 0,
    @SerializedName("weakTop5") val weakTop5: List<String> = emptyList(),
    @SerializedName("timestamp") val timestamp: Long = System.currentTimeMillis()
)

/**
 * 作业下发 (HOMEWORK_ASSIGN / 48):主控端 → 受控端。
 * 复用 TaskQueueManager 队列与 TTS 播报;[module]/[count]/[params] 描述练习内容。
 */
data class HomeworkAssignPayload(
    @SerializedName("taskId") val taskId: String,
    @SerializedName("module") val module: String,          // 同 LearnProgressPayload.module
    @SerializedName("title") val title: String,            // TTS 播报用,≤120 字
    @SerializedName("count") val count: Int = 10,          // 题量/字数
    @SerializedName("params") val params: Map<String, String> = emptyMap(), // 例:{"level":"2"}
    @SerializedName("deadline") val deadline: Long = 0,    // 0 = 不限
    @SerializedName("timestamp") val timestamp: Long = System.currentTimeMillis()
)

/**
 * 作业回执 (HOMEWORK_ACK / 49):受控端 → 主控端。
 * RECEIVED 收到 / DONE 完成(score 为正确率 0-100)。
 */
data class HomeworkAckPayload(
    @SerializedName("taskId") val taskId: String,
    @SerializedName("state") val state: String,            // RECEIVED | DONE
    @SerializedName("score") val score: Int = -1,
    @SerializedName("timestamp") val timestamp: Long = System.currentTimeMillis()
)
