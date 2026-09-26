package com.inklink.host.game

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

/**
 * 3 款内置小游戏核心逻辑与每日防刷限制
 */
class PetMiniGameManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("pet_minigames", Context.MODE_PRIVATE)

    enum class Choice { ROCK, PAPER, SCISSORS }
    enum class Result { WIN, LOSE, DRAW }

    fun playRockPaperScissors(userChoice: Choice): Pair<Choice, Result> {
        val botChoice = Choice.values().random()
        val result = when {
            userChoice == botChoice -> Result.DRAW
            userChoice == Choice.ROCK && botChoice == Choice.SCISSORS -> Result.WIN
            userChoice == Choice.PAPER && botChoice == Choice.ROCK -> Result.WIN
            userChoice == Choice.SCISSORS && botChoice == Choice.PAPER -> Result.WIN
            else -> Result.LOSE
        }
        return Pair(botChoice, result)
    }

    /**
     * 记忆翻牌判定：输入 4x4 矩阵匹配状态，返回是否全部通关
     */
    fun checkMemoryMatch(firstCard: Int, secondCard: Int): Boolean {
        return firstCard == secondCard
    }

    /**
     * 幸运转盘：获得金币与额外经验（2026-08-30 奖励上调修订）
     */
    fun spinWheel(): Pair<Int, Int> { // Pair(coinReward, expReward)
        val roll = Random.nextInt(100)
        return when {
            roll < 50 -> Pair(10, 15)
            roll < 80 -> Pair(30, 40)
            roll < 95 -> Pair(60, 80)
            else -> Pair(200, 250) // 大奖
        }
    }

    /**
     * 每日防刷校验：每日仅前 8 局获得奖励（2026-08-30 放宽修订）
     */
    fun canGainRewardToday(): Boolean {
        val todayStr = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
        val savedDate = prefs.getString("last_play_date", "")
        var count = prefs.getInt("play_count_today", 0)

        if (savedDate != todayStr) {
            count = 0
            prefs.edit().putString("last_play_date", todayStr).putInt("play_count_today", 0).apply()
        }
        return count < 8
    }

    fun recordGamePlayed() {
        val todayStr = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
        val savedDate = prefs.getString("last_play_date", "")
        var count = prefs.getInt("play_count_today", 0)
        if (savedDate != todayStr) {
            count = 0
        }
        prefs.edit().putString("last_play_date", todayStr).putInt("play_count_today", count + 1).apply()
    }
}
