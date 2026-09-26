package com.inklink.host.state

import com.inklink.common.protocol.payload.PetItem
import kotlin.math.floor

/**
 * 双时钟数值结算引擎（V1.1 裁决 #1 / #6，纯函数无 Android 依赖，便于单测注入时钟）。
 *
 * 衰减速率（前台"佛系"节奏，2026-09-05 用户裁定满值掉光约 16/23/20/32 小时）：
 * 饱食 -1/576s、开心 -1/828s、精力 -1/720s、清洁 -1/1152s；
 * 侵蚀 health -1/90s（hunger<20 || clean<20 时）；睡眠 精力 +1/10s、health +1/30s。
 *
 * 后台/灭屏（foreground=false）：自动进入休眠语义（2026-08-31 "不玩默认休眠、不掉状态"修订，
 * 替代原 ÷20+地板30）——四维完全豁免、精力/健康按睡眠速率缓慢恢复，即"不玩就不掉状态"。
 *
 * 结算模型（分属性独立余量，杜绝跨属性耦合）：
 *   budget = realSec × speed（speed：前台1.0 / 后台0.05）
 *   每属性：owed = rem + rate×budget → earned=floor(owed) → rem = owed-earned
 * 余量以"点"为单位存在 PetItem.remXxx，1s tick 下 -0.7/s 每 tick 累积 0.7 点、
 * 第 2 tick 兑换出 1 点，永不丢帧；地板/暂停/冻结属性直接跳过不累积。
 * lastUpdateTs 恒对齐 now（真实时间窗一次结清，幂等），暂停期 = 豁免不追缴。
 */
object PetDecayEngine {

    // 前台满值掉光时长（小时）。2026-09-05 用户裁定"最佛系"节奏：
    // 饱食 16h / 开心 23h / 精力 20h / 清洁 32h（维持原 1.0 : 0.7 : 0.8 : 0.5 相对速率）
    private const val HOURS_HUNGER = 16.0
    private const val HOURS_MOOD = 23.0
    private const val HOURS_ENERGY = 20.0
    private const val HOURS_CLEAN = 32.0

    private const val RATE_HUNGER = 100.0 / (HOURS_HUNGER * 3600.0)
    private const val RATE_MOOD = 100.0 / (HOURS_MOOD * 3600.0)
    private const val RATE_ENERGY = 100.0 / (HOURS_ENERGY * 3600.0)
    private const val RATE_CLEAN = 100.0 / (HOURS_CLEAN * 3600.0)
    private const val SEC_PER_HEALTH_LOSS = 90.0   // health -1 / 90s（前台）
    private const val SEC_PER_ENERGY_GAIN = 10.0   // 睡眠 精力 +1 / 10s（前台）
    private const val SEC_PER_HEALTH_GAIN = 30.0   // 睡眠 health +1 / 30s（前台）

    /**
     * 按时间差推进一个宠物的数值。原地修改 [pet]。
     * @param now 当前时间戳
     * @param foreground 是否前台全速（false=后台自动休眠：四维豁免 + 精力/健康恢复）
     */
    fun settle(pet: PetItem, now: Long, foreground: Boolean): Boolean {
        val realSec = (now - pet.lastUpdateTs) / 1000.0
        if (realSec <= 0.0) {
            clearExpiredPause(pet, now)
            return false
        }
        val speed = if (foreground) 1.0 else 1.0 / PetClock.IDLE_FACTOR
        val budget = realSec * speed
        val floorVal = if (foreground) 0 else PetClock.FLOOR
        // 后台自动休眠（2026-08-31）：非前台一律按睡眠语义结算，四维不掉、精力/健康恢复
        val asleep = pet.isSleeping || !foreground
        // 暂停判定 = 截止时间还在未来（clearExpiredPause 负责把已过期时间戳归零）
        val energyPaused = pet.energyPauseUntilTs > now
        val moodPaused = pet.moodPauseUntilTs > now

        // 把 rate×budget 灌入余量并兑现整数点，返回 (整数点, 剩余余量)
        fun dispense(rem: Double, rate: Double): Pair<Int, Double> {
            val owed = rem + rate * budget
            val pts = floor(owed).toInt()
            return pts to (owed - pts)
        }

        // ---- 饥饿（休眠豁免） ----
        if (!asleep && pet.hunger > floorVal) {
            val (pts, rem) = dispense(pet.remHunger, RATE_HUNGER)
            pet.remHunger = rem
            if (pts > 0) {
                pet.hunger = maxOf(pet.hunger - pts, floorVal).coerceIn(0, 100)
                if (pet.hunger == floorVal && floorVal > 0) pet.remHunger = 0.0
            }
        } else pet.remHunger = 0.0

        // ---- 开心（buff_mood 暂停期豁免；休眠整体豁免） ----
        if (!asleep && !moodPaused && pet.happiness > floorVal) {
            val (pts, rem) = dispense(pet.remMood, RATE_MOOD)
            pet.remMood = rem
            if (pts > 0) {
                pet.happiness = maxOf(pet.happiness - pts, floorVal).coerceIn(0, 100)
                if (pet.happiness == floorVal && floorVal > 0) pet.remMood = 0.0
            }
        } else if (!asleep && !moodPaused && pet.happiness <= floorVal) {
            pet.remMood = 0.0
        }

        // ---- 精力：睡眠/后台自动休眠恢复 / 清醒衰减（buff_energy 暂停只挡衰减，不挡恢复） ----
        if (asleep) {
            val (pts, rem) = dispense(pet.remEnergy, 1.0 / SEC_PER_ENERGY_GAIN)
            pet.remEnergy = rem
            if (pts > 0) {
                pet.energy = (pet.energy + pts).coerceIn(0, 100)
                if (pet.energy >= 100) pet.remEnergy = 0.0
            }
        } else if (!energyPaused && pet.energy > floorVal) {
            val (pts, rem) = dispense(pet.remEnergy, RATE_ENERGY)
            pet.remEnergy = rem
            if (pts > 0) {
                pet.energy = maxOf(pet.energy - pts, floorVal).coerceIn(0, 100)
                if (pet.energy == floorVal && floorVal > 0) pet.remEnergy = 0.0
            }
        } else {
            pet.remEnergy = 0.0
        }

        // ---- 清洁（休眠豁免） ----
        if (!asleep && pet.clean > floorVal) {
            val (pts, rem) = dispense(pet.remClean, RATE_CLEAN)
            pet.remClean = rem
            if (pts > 0) {
                pet.clean = maxOf(pet.clean - pts, floorVal).coerceIn(0, 100)
                if (pet.clean == floorVal && floorVal > 0) pet.remClean = 0.0
            }
        } else pet.remClean = 0.0

        // ---- 健康：虚弱沉睡冻结；睡眠/后台自动休眠恢复 / 恶劣状态侵蚀 ----
        if (pet.isAlive) {
            if (asleep) {
                val (pts, rem) = dispense(pet.remHealth, 1.0 / SEC_PER_HEALTH_GAIN)
                pet.remHealth = rem
                if (pts > 0) {
                    pet.health = (pet.health + pts).coerceIn(0, 100)
                    if (pet.health >= 100) pet.remHealth = 0.0
                }
            } else if (pet.hunger < 20 || pet.clean < 20) {
                val (pts, rem) = dispense(pet.remHealth, 1.0 / SEC_PER_HEALTH_LOSS)
                pet.remHealth = rem
                if (pts > 0) {
                    pet.health = (pet.health - pts).coerceIn(0, 100)
                    if (pet.health <= 0) {
                        pet.health = 0
                        pet.isAlive = false
                        pet.remHealth = 0.0
                    }
                }
            } else pet.remHealth = 0.0
        }

        pet.lastUpdateTs = now
        clearExpiredPause(pet, now)
        return true
    }

    private fun clearExpiredPause(pet: PetItem, now: Long) {
        if (pet.energyPauseUntilTs in 1 until now) pet.energyPauseUntilTs = 0
        if (pet.moodPauseUntilTs in 1 until now) pet.moodPauseUntilTs = 0
    }
}
