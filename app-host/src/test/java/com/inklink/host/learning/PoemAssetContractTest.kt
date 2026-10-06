package com.inklink.host.learning

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 古诗资产契约测试：poems.json 与 LearningManager.Poem 模型必须始终对齐。
 *
 * 背景：Poem.pinyins 曾误声明为 List<List<String>>（抄了 hanzi 的逐字嵌套形状），
 * 而资产数据是行级字符串数组，Gson 解析即抛 Expected BEGIN_ARRAY，古诗亭 114 首全挂、
 * 页面只剩报错。本测试用真实模型类解析真实资产文件，模型与数据形状漂移立刻红。
 */
class PoemAssetContractTest {

    private fun assetFile(): File = listOf(
        File("src/main/assets/learning/poems.json"),
        File("app-host/src/main/assets/learning/poems.json")
    ).first { it.isFile }

    @Test
    fun `poems_json 能被 Poem 模型完整解析且内容合规`() {
        val type = object : TypeToken<List<LearningManager.Poem>>() {}.type
        val poems: List<LearningManager.Poem> = Gson().fromJson(assetFile().readText(), type)

        // 库规模：修复前 114 首，给出下限防资产被误清空
        assertTrue("古诗库过小: ${poems.size}", poems.size >= 100)

        poems.forEach { p ->
            assertTrue("id 不能为空: ${p.id}", p.id.isNotBlank())
            assertTrue("title 不能为空: ${p.id}", p.title.isNotBlank())
            assertTrue("author 不能为空: ${p.id}", p.author.isNotBlank())
            assertTrue("${p.title} 至少一行诗句", p.lines.isNotEmpty())
            p.lines.forEach { line -> assertTrue("${p.title} 存在空行", line.isNotBlank()) }
            assertTrue("${p.title} level 越界: ${p.level}", p.level in 1..3)
            // 行级拼音与诗句行数必须一一对应（当前资产的 pinyins 形状）
            p.pinyins?.let { py ->
                assertEquals("${p.title} pinyins 行数与诗句不一致", p.lines.size, py.size)
            }
        }

        // 各级都有可学内容：buildPoems 按 level 过滤，空级会静默回退全库
        val byLevel = poems.groupBy { it.level }
        assertTrue("level 1 无古诗", (byLevel[1]?.size ?: 0) > 0)
        assertTrue("level 2 无古诗", (byLevel[2]?.size ?: 0) > 0)
    }
}
