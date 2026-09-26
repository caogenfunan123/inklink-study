package com.inklink.host.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [PetBagRow::class, EventLogEntry::class, LearnProgressRow::class,
        WrongBookRow::class, DailyStatsRow::class],
    version = 2, exportSchema = false
)
abstract class PetDatabase : RoomDatabase() {

    abstract fun petDao(): PetDao

    abstract fun learnDao(): LearnDao

    companion object {
        /** 事件日志环形上限（V1.1 工程纪律：防数据库无限膨胀） */
        const val MAX_LOG = 500

        @Volatile
        private var instance: PetDatabase? = null

        fun get(context: Context): PetDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    PetDatabase::class.java,
                    "inklink_pet.db"
                )
                    // 玩具级应用、单线程访问（PetStateManager 内部 lock 串行化）；
                    // 秒级运算在内存，落盘走 10-30s 节流，故允许主线程查询。
                    .allowMainThreadQueries()
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
    }
}
