package com.inklink.host.ui.learning

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.inklink.host.R
import com.inklink.host.learning.LearningManager

/** 错题本(P0-4 消费端):列最近答错的知识点,一键去复习。 */
class WrongBookActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_wrong_book)
        val container = findViewById<LinearLayout>(R.id.wrongList)
        val items = LearningManager.get(applicationContext).wrongItems()

        if (items.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "错题本空空的,太棒啦!"
                textSize = 16f
                setPadding(24, 48, 24, 48)
            })
            return
        }

        val inflater = layoutInflater
        items.forEach { item ->
            val label = when (item.module) {
                LearningManager.MODULE_HANZI -> "识字"
                LearningManager.MODULE_PINYIN -> "拼音"
                LearningManager.MODULE_MATH -> "口算"
                LearningManager.MODULE_POEM -> "古诗"
                else -> "学习"
            }
            val card = inflater.inflate(R.layout.item_wrong_entry, container, false)
            card.findViewById<TextView>(R.id.tvWrongItem).text =
                "[$label] ${item.itemId} · 错 ${item.wrongCount} 次"
            card.findViewById<TextView>(R.id.btnWrongReview).setOnClickListener {
                startActivity(
                    Intent(this, LessonActivity::class.java)
                        .putExtra(LessonActivity.EXTRA_MODULE, item.module)
                        .putExtra(LessonActivity.EXTRA_LEVEL, 2)
                        .putExtra(LessonActivity.EXTRA_REVIEW, true)
                )
            }
            container.addView(card)
        }
    }
}
