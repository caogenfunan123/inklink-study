package com.inklink.host.task

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.inklink.common.protocol.payload.RemoteTaskPayload
import java.io.File

data class LocalRemoteTask(
    val taskId: String,
    val content: String,
    val timestamp: Long,
    var isPlayed: Boolean = false
)

/**
 * 远程语音文字任务队列持久化与淘汰机制
 */
class TaskQueueManager(private val context: Context) {

    private val gson = Gson()
    private val file = File(context.filesDir, "task_list.json")
    private val lock = Any()

    fun getTasks(): List<LocalRemoteTask> {
        synchronized(lock) {
            if (!file.exists()) return emptyList()
            val json = runCatching { file.readText() }.getOrNull() ?: return emptyList()
            val type = object : TypeToken<List<LocalRemoteTask>>() {}.type
            return runCatching { gson.fromJson<List<LocalRemoteTask>>(json, type) }.getOrNull() ?: emptyList()
        }
    }

    fun addTask(payload: RemoteTaskPayload): Boolean {
        synchronized(lock) {
            val list = getTasks().toMutableList()
            if (list.any { it.taskId == payload.taskId }) {
                return false // 去重
            }

            if (list.size >= MAX_TASK_CAPACITY) {
                // 优先淘汰已播放的最早任务
                val playedTask = list.filter { it.isPlayed }.minByOrNull { it.timestamp }
                if (playedTask != null) {
                    list.remove(playedTask)
                } else {
                    // FIFO 淘汰最早任务
                    list.minByOrNull { it.timestamp }?.let { list.remove(it) }
                }
            }

            val sanitizedText = payload.content.take(120).replace(Regex("[\\p{Cntrl}&&[^\r\n\t]]"), "")
            list.add(LocalRemoteTask(payload.taskId, sanitizedText, payload.timestamp, isPlayed = false))
            save(list)
            return true
        }
    }

    fun markPlayed(taskId: String) {
        synchronized(lock) {
            val list = getTasks().toMutableList()
            list.find { it.taskId == taskId }?.isPlayed = true
            save(list)
        }
    }

    private fun save(list: List<LocalRemoteTask>) {
        runCatching {
            file.writeText(gson.toJson(list))
        }
    }

    companion object {
        private const val MAX_TASK_CAPACITY = 20
    }
}
