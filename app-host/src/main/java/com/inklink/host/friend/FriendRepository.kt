package com.inklink.host.friend

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.inklink.common.transport.AblyRelayTransport
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class Friend(
    val deviceId: String,
    val nickname: String,
    val pairingKey: String,
    val addedTs: Long = System.currentTimeMillis()
)

/**
 * 受控端好友仓库（阶段六，仅 Ably 模式）。
 *
 * pairingKey 即好友钥匙：双方各自录入对方 pairingKey 添加好友，各自订阅对方频道实现互见。
 * 负责好友落盘（friends.json）、在线状态缓存（Presence 驱动）与频道挂载/卸载。
 */
class FriendRepository(private val context: Context) {

    private val gson = Gson()
    private val file = File(context.filesDir, "friends.json")
    private val lock = Any()

    /** 好友在线状态缓存（Presence 实时驱动，deviceId -> online） */
    val onlineByDeviceId = ConcurrentHashMap<String, Boolean>()

    fun getFriends(): List<Friend> {
        synchronized(lock) {
            if (!file.exists()) return emptyList()
            val json = runCatching { file.readText() }.getOrNull() ?: return emptyList()
            val type = object : TypeToken<List<Friend>>() {}.type
            return runCatching { gson.fromJson<List<Friend>>(json, type) }.getOrNull() ?: emptyList()
        }
    }

    fun findByDeviceId(deviceId: String?): Friend? =
        deviceId?.let { id -> getFriends().find { it.deviceId == id } }

    fun findByChannel(channelName: String): Friend? =
        getFriends().find { channelNameOf(it) == channelName }

    fun channelNameOf(friend: Friend): String =
        "${AblyRelayTransport.CHANNEL_PREFIX}${friend.pairingKey}"

    /** 添加好友（按 pairingKey 去重）；返回 true 表示新增成功。 */
    fun addFriend(pairingKey: String, nickname: String): Boolean {
        if (pairingKey.isBlank()) return false
        synchronized(lock) {
            val list = getFriends().toMutableList()
            if (list.any { it.pairingKey == pairingKey }) return false
            list.add(
                Friend(
                    deviceId = "pending-${UUID.randomUUID()}",
                    nickname = nickname.ifBlank { "好友" },
                    pairingKey = pairingKey
                )
            )
            save(list)
            return true
        }
    }

    /** Presence 上线时用真实 deviceId 回填好友记录。 */
    fun bindDeviceId(pairingKey: String, deviceId: String) {
        synchronized(lock) {
            val list = getFriends().toMutableList()
            val idx = list.indexOfFirst { it.pairingKey == pairingKey }
            if (idx >= 0 && list[idx].deviceId != deviceId) {
                list[idx] = list[idx].copy(deviceId = deviceId)
                save(list)
            }
        }
    }

    fun removeFriend(pairingKey: String) {
        synchronized(lock) {
            val list = getFriends().toMutableList()
            list.removeAll { it.pairingKey == pairingKey }
            save(list)
        }
    }

    fun isOnline(deviceId: String?): Boolean =
        deviceId != null && onlineByDeviceId[deviceId] == true

    private fun save(list: List<Friend>) {
        runCatching { file.writeText(gson.toJson(list)) }
    }
}
