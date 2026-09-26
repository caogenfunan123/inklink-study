package com.inklink.host.queue

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

data class OfflineEvent(
    val eventId: String,
    val eventType: String,
    val payloadJson: String,
    val timestamp: Long
)

/**
 * 受控端离线操作事件队列（上限 50 条，FIFO 淘汰）
 */
class OfflineEventQueue(context: Context) {

    private val gson = Gson()
    private val file = File(context.filesDir, "offline_events.json")
    private val lock = Any()

    fun getEvents(): List<OfflineEvent> {
        synchronized(lock) {
            if (!file.exists()) return emptyList()
            val json = runCatching { file.readText() }.getOrNull() ?: return emptyList()
            val type = object : TypeToken<List<OfflineEvent>>() {}.type
            return runCatching { gson.fromJson<List<OfflineEvent>>(json, type) }.getOrNull() ?: emptyList()
        }
    }

    fun enqueue(event: OfflineEvent) {
        synchronized(lock) {
            val list = getEvents().toMutableList()
            if (list.size >= MAX_QUEUE_SIZE) {
                list.removeAt(0) // FIFO 丢弃最老记录
            }
            list.add(event)
            save(list)
        }
    }

    fun drain(): List<OfflineEvent> {
        synchronized(lock) {
            val list = getEvents()
            file.delete()
            return list
        }
    }

    private fun save(list: List<OfflineEvent>) {
        runCatching {
            file.writeText(gson.toJson(list))
        }
    }

    companion object {
        private const val MAX_QUEUE_SIZE = 50
    }
}
