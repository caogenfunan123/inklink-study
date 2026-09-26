package com.inklink.host.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 管控后台 PIN 码安全与防暴力破解管理器
 */
class PinSecurityManager(private val context: Context) {

    private val prefs: SharedPreferences by lazy {
        runCatching {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                "host_security_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        }.getOrElse {
            // AndroidKeyStore 损坏的部分 OEM 设备上会抛异常，降级为普通存储：
            // 仅存加盐 SHA-256 哈希与锁定状态，无明文敏感信息
            context.getSharedPreferences("host_security_prefs_fallback", Context.MODE_PRIVATE)
        }
    }

    @Volatile
    var isUnlocked: Boolean = false
        private set

    fun hasPin(): Boolean {
        return !prefs.getString(KEY_PIN_HASH, null).isNullOrBlank()
    }

    fun setPin(pin: String): Boolean {
        if (pin.length < 4) return false
        val hash = hashPin(pin)
        prefs.edit()
            .putString(KEY_PIN_HASH, hash)
            .putInt(KEY_FAILED_COUNT, 0)
            .putLong(KEY_LOCKED_UNTIL, 0L)
            .apply()
        isUnlocked = true
        return true
    }

    fun isLocked(): Boolean {
        val lockedUntil = prefs.getLong(KEY_LOCKED_UNTIL, 0L)
        return System.currentTimeMillis() < lockedUntil
    }

    fun getRemainingLockSeconds(): Int {
        val lockedUntil = prefs.getLong(KEY_LOCKED_UNTIL, 0L)
        val diff = (lockedUntil - System.currentTimeMillis()) / 1000L
        return diff.coerceAtLeast(0L).toInt()
    }

    fun verifyPin(pin: String): Boolean {
        if (isLocked()) return false
        val savedHash = prefs.getString(KEY_PIN_HASH, null) ?: return false
        val inputHash = hashPin(pin)

        if (savedHash == inputHash) {
            prefs.edit().putInt(KEY_FAILED_COUNT, 0).apply()
            isUnlocked = true
            return true
        } else {
            val failedCount = prefs.getInt(KEY_FAILED_COUNT, 0) + 1
            val editor = prefs.edit().putInt(KEY_FAILED_COUNT, failedCount)
            if (failedCount >= MAX_FAILED_ATTEMPTS) {
                val lockedUntil = System.currentTimeMillis() + LOCKOUT_DURATION_MS
                editor.putLong(KEY_LOCKED_UNTIL, lockedUntil)
            }
            editor.apply()
            return false
        }
    }

    fun lock() {
        isUnlocked = false
    }

    /**
     * 远程重置 PIN 码 (CMD_RESET_HOST_PIN)
     * 需验证 HMAC-SHA256 与 ±60s 时间戳防重放
     */
    fun resetPinRemote(newPinHash: String, timestamp: Long, signature: String, pairingKey: String): Boolean {
        val now = System.currentTimeMillis()
        if (Math.abs(now - timestamp) > 60_000L) {
            return false // 防重放窗口失效
        }
        val expectedSig = computeHmacSha256(pairingKey, "$newPinHash:$timestamp")
        if (expectedSig != signature) {
            return false // 鉴权失败
        }
        prefs.edit()
            .putString(KEY_PIN_HASH, newPinHash)
            .putInt(KEY_FAILED_COUNT, 0)
            .putLong(KEY_LOCKED_UNTIL, 0L)
            .apply()
        isUnlocked = false
        return true
    }

    private fun hashPin(pin: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest("inklink_salt_$pin".toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun computeHmacSha256(key: String, data: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        val secretKey = SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256")
        mac.init(secretKey)
        val bytes = mac.doFinal(data.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val KEY_PIN_HASH = "pin_hash"
        private const val KEY_FAILED_COUNT = "failed_count"
        private const val KEY_LOCKED_UNTIL = "locked_until"
        private const val MAX_FAILED_ATTEMPTS = 5
        private const val LOCKOUT_DURATION_MS = 120_000L // 锁定 2 分钟
    }
}
