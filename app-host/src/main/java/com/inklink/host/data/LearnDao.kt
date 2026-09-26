package com.inklink.host.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface LearnDao {

    // ---------- learn_progress ----------

    @Upsert
    fun upsertProgress(row: LearnProgressRow)

    @Query("SELECT * FROM learn_progress WHERE module = :module AND status != 'MASTERED' AND nextReviewTs <= :now ORDER BY nextReviewTs LIMIT :limit")
    fun dueReviews(module: String, now: Long, limit: Int): List<LearnProgressRow>

    @Query("SELECT * FROM learn_progress WHERE module = :module")
    fun allProgress(module: String): List<LearnProgressRow>

    @Query("SELECT itemId FROM learn_progress WHERE module = :module")
    fun learnedItemIds(module: String): List<String>

    @Query("SELECT COUNT(*) FROM learn_progress WHERE module = :module AND nextReviewTs <= :now AND status != 'MASTERED'")
    fun dueReviewCount(module: String, now: Long): Int

    @Query("SELECT * FROM learn_progress WHERE wrongCount > 0 ORDER BY lastTs DESC LIMIT :limit")
    fun wrongItems(limit: Int): List<LearnProgressRow>

    // ---------- wrong_book ----------

    @Insert
    fun insertWrong(row: WrongBookRow)

    // ---------- daily_stats ----------

    @Upsert
    fun upsertDaily(row: DailyStatsRow)

    @Query("SELECT * FROM daily_stats WHERE date = :date AND module = :module LIMIT 1")
    fun dailyRow(date: String, module: String): DailyStatsRow?

    @Query("SELECT COALESCE(SUM(coinsEarned), 0) FROM daily_stats WHERE date = :date")
    fun todayCoinsSum(date: String): Int

    @Query("SELECT COALESCE(SUM(minutes), 0) FROM daily_stats WHERE date = :date")
    fun todayMinutes(date: String): Int
}
