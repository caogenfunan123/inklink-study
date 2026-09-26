package com.inklink.host.ui

import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.gson.Gson
import com.inklink.common.protocol.InkMessage
import com.inklink.common.protocol.MessageType
import com.inklink.common.protocol.payload.PetBagInteractPayload
import com.inklink.common.protocol.payload.PetGameInvitePayload
import com.inklink.host.InkHostApplication
import com.inklink.host.R
import com.inklink.host.friend.Friend
import com.inklink.host.friend.GameInviteSession
import com.inklink.host.util.HapticUtil
import java.util.UUID

/**
 * 好友圈（阶段六，仅 Ably 模式）：
 * 添加/删除好友、在线状态展示、发起跨设备猜拳对战与串门互动。
 * 受控端互玩仅限游戏（33/34）与串门（43），管理类指令对好友关闭。
 */
class FriendActivity : AppCompatActivity() {

    private val app by lazy { application as InkHostApplication }
    private val repo by lazy { app.friendRepository }
    private val gson = Gson()

    private lateinit var listContainer: LinearLayout
    private lateinit var inputPairingKey: TextInputEditText
    private lateinit var inputNickname: TextInputEditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_friend)
        title = "好友圈"

        listContainer = findViewById(R.id.layoutFriendList)
        inputPairingKey = findViewById(R.id.inputPairingKey)
        inputNickname = findViewById(R.id.inputNickname)

        findViewById<MaterialButton>(R.id.btnAddFriend).setOnClickListener {
            val key = inputPairingKey.text?.toString()?.trim().orEmpty()
            val nickname = inputNickname.text?.toString()?.trim().orEmpty()
            if (key.isBlank()) {
                Toast.makeText(this, "请输入好友配对码", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (key == app.pairingKey) {
                Toast.makeText(this, "不能添加自己", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (repo.addFriend(key, nickname)) {
                HapticUtil.tap(it)
                attachFriendChannel(key)
                inputPairingKey.setText("")
                inputNickname.setText("")
                Toast.makeText(this, "好友已添加，对方上线后可互玩", Toast.LENGTH_SHORT).show()
                renderList()
            } else {
                Toast.makeText(this, "该好友已存在", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 运行时挂载新好友频道（服务启动时已挂载存量好友）。 */
    private fun attachFriendChannel(pairingKey: String) {
        val ably = app.transportManager.currentTransport()
            as? com.inklink.common.transport.AblyRelayTransport ?: return
        val friend = repo.getFriends().find { it.pairingKey == pairingKey } ?: return
        ably.attachChannel(repo.channelNameOf(friend))
    }

    override fun onResume() {
        super.onResume()
        renderList()
        pollOnline.run()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(pollOnline)
    }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    /** 2s 轮询刷新在线徽章（好友量少， Presence 缓存由 Service 实时更新）。 */
    private val pollOnline = object : Runnable {
        override fun run() {
            refreshOnlineBadges()
            handler.postDelayed(this, 2_000L)
        }
    }

    private fun renderList() {
        listContainer.removeAllViews()
        val friends = repo.getFriends()
        if (friends.isEmpty()) {
            val empty = TextView(this).apply {
                text = "还没有好友\n输入对方的配对码添加吧"
                gravity = Gravity.CENTER
                setPadding(0, 60, 0, 60)
                textSize = 14f
                setTextColor(0xFF999999.toInt())
            }
            listContainer.addView(empty)
            return
        }
        friends.forEach { friend -> listContainer.addView(buildFriendCard(friend)) }
    }

    private fun buildFriendCard(friend: Friend): LinearLayout {
        val card = MaterialCardView(this).apply {
            radius = 16f * resources.displayMetrics.density
            cardElevation = 2f * resources.displayMetrics.density
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 12.dp }
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16.dp, 12.dp, 16.dp, 12.dp)
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        header.addView(
            TextView(this@FriendActivity).apply {
                text = friend.nickname
                textSize = 16f
                setTextColor(0xFF333444.toInt())
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
        )
        val badge = TextView(this@FriendActivity).apply {
            textSize = 13f
            tag = friend
            updateBadge(this, friend)
        }
        header.addView(badge)
        box.addView(header)

        val row = LinearLayout(this@FriendActivity).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(
            MaterialButton(this@FriendActivity).apply {
                text = "🎮 对战"
                textSize = 13f
                setOnClickListener { view ->
                    HapticUtil.tap(view)
                    startGameWith(friend)
                }
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
        )
        row.addView(
            MaterialButton(this@FriendActivity).apply {
                text = "🐾 串门"
                textSize = 13f
                setOnClickListener { view ->
                    HapticUtil.tap(view)
                    visitFriend(friend)
                }
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginStart = 8.dp }
            }
        )
        row.addView(
            MaterialButton(this@FriendActivity).apply {
                text = "删除"
                textSize = 13f
                backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFFFE8E8.toInt())
                setTextColor(0xFFD33931.toInt())
                setOnClickListener { view ->
                    HapticUtil.tap(view)
                    MaterialAlertDialogBuilder(this@FriendActivity)
                        .setTitle("删除好友")
                        .setMessage("确定删除 ${friend.nickname} 吗？")
                        .setPositiveButton("删除") { _, _ ->
                            repo.removeFriend(friend.pairingKey)
                            repo.onlineByDeviceId.remove(friend.deviceId)
                            detachFriendChannel(friend)
                            renderList()
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { marginStart = 8.dp }
            }
        )
        box.addView(row)
        card.addView(box)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(card)
        }
    }

    private fun updateBadge(badge: TextView, friend: Friend) {
        val online = repo.isOnline(friend.deviceId)
        badge.text = if (online) "● 在线" else "○ 离线"
        badge.setTextColor(if (online) 0xFF2E9E44.toInt() else 0xFF999999.toInt())
    }

    private fun refreshOnlineBadges() {
        listContainer.forEachDescendant { view ->
            val friend = view.tag as? Friend ?: return@forEachDescendant
            if (view is TextView) updateBadge(view, friend)
        }
    }

    private fun android.view.ViewGroup.forEachDescendant(action: (android.view.View) -> Unit) {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            action(child)
            if (child is android.view.ViewGroup) child.forEachDescendant(action)
        }
    }

    /** 发起跨设备猜拳：先选出招，等待对方出招后由 Service 结算。 */
    private fun startGameWith(friend: Friend) {
        if (!requireReachable(friend)) return
        val choices = arrayOf("石头 ✊", "剪刀 ✌️", "布 ✋")
        MaterialAlertDialogBuilder(this)
            .setTitle("向 ${friend.nickname} 发起对战\n请先选择你的出招")
            .setItems(choices) { _, which ->
                val myChoice = when (which) {
                    0 -> "ROCK"
                    1 -> "SCISSORS"
                    else -> "PAPER"
                }
                val inviteId = UUID.randomUUID().toString()
                GameInviteSession.put(inviteId, myChoice)
                val payload = PetGameInvitePayload(inviteId = inviteId, gameType = "RPS")
                app.transportManager.sendMessage(
                    InkMessage(
                        type = MessageType.PET_GAME_INVITE.code,
                        fromDeviceId = app.deviceId,
                        targetDeviceId = friend.deviceId,
                        payload = gson.toJson(payload)
                    )
                )
                Toast.makeText(this, "对战邀请已发出，等待 ${friend.nickname} 出招", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 向好友发起串门：被访端宠物心情 +3 并收到气泡提示。 */
    private fun visitFriend(friend: Friend) {
        if (!requireReachable(friend)) return
        val payload = PetBagInteractPayload(targetPetId = app.pairingKey)
        app.transportManager.sendMessage(
            InkMessage(
                type = MessageType.PET_BAG_INTERACT.code,
                fromDeviceId = app.deviceId,
                targetDeviceId = friend.deviceId,
                payload = gson.toJson(payload)
            )
        )
        Toast.makeText(this, "去找 ${friend.nickname} 的宠物串门啦", Toast.LENGTH_SHORT).show()
    }

    /** 好友从未上线时 deviceId 仍为占位值，无法定向投递。 */
    private fun requireReachable(friend: Friend): Boolean {
        if (friend.deviceId.startsWith("pending-")) {
            Toast.makeText(this, "${friend.nickname} 还没有上线过，先等对方打开宠物页", Toast.LENGTH_SHORT).show()
            return false
        }
        if (!repo.isOnline(friend.deviceId)) {
            Toast.makeText(this, "${friend.nickname} 当前离线", Toast.LENGTH_SHORT).show()
            return false
        }
        return true
    }

    private fun detachFriendChannel(friend: Friend) {
        val ably = app.transportManager.currentTransport()
            as? com.inklink.common.transport.AblyRelayTransport ?: return
        ably.detachChannel(repo.channelNameOf(friend))
    }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()
}
