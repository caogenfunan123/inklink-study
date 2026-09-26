package com.inklink.host.friend

/**
 * 受控端互玩对战会话（进程内单例）。
 *
 * 发起方受控端选出招后记录 inviteId -> myChoice，
 * InkForegroundService 收到 PET_GAME_ACTION(34) 时消费记录并判定胜负。
 */
object GameInviteSession {

    data class PendingInvite(val inviteId: String, val myChoice: String, val ts: Long)

    private val pending = LinkedHashMap<String, PendingInvite>()

    fun put(inviteId: String, myChoice: String) {
        synchronized(pending) {
            // 防累积：仅保留最近 5 条未决对局
            while (pending.size >= 5) {
                pending.remove(pending.keys.first())
            }
            pending[inviteId] = PendingInvite(inviteId, myChoice, System.currentTimeMillis())
        }
    }

    fun consume(inviteId: String): PendingInvite? {
        synchronized(pending) { return pending.remove(inviteId) }
    }
}
