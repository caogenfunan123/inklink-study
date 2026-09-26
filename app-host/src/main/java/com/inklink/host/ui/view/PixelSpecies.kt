package com.inklink.host.ui.view

/**
 * V1.1 像素部件化渲染的核心数据结构与静态定义。
 * 物种表 / 调色板 / 装饰外观 / 场景外观 —— 从主视图文件拆出，保持 PetSpriteView 聚焦渲染逻辑。
 */

/** 物种调色板（≤24 色风格约束下的每物种 7 色） */
data class PetPalette(
    val body: Int, val shade: Int, val belly: Int,
    val patch: Int, val line: Int, val cheek: Int, val special: Int
)

/** 物种定义：LUT 调色板 + 三件套插槽开关 */
data class SpeciesDef(
    val code: String,
    /** 头饰槽样式: cat_ears/drop_ears/long_ears/none/round_ears/big_ears/horns/wool_horns/tiny_ears/frog_eyes/tufts */
    val headSlot: String,
    /** 背饰槽样式: none/wings/spikes/frill */
    val backSlot: String,
    /** 尾饰槽样式: curvy/nub/cotton/fan/bushy/longtail/none/coil */
    val tailSlot: String,
    val palette: PetPalette,
    /** 喙（企鹅/猫头鹰）代替嘴 */
    val beak: Boolean = false,
    /** 熊猫眼斑 */
    val eyePatch: Boolean = false,
    /** 猪鼻（吻部圆盘+鼻孔），2026-08-31 附录A追加 */
    val snout: Boolean = false,
    /** 大眼（猫头鹰），2026-08-31 附录A追加 */
    val bigEyes: Boolean = false,
    /** 无四肢（蛇），2026-08-31 附录A追加 */
    val noLimbs: Boolean = false
)

object PixelSpecies {
    // ARGB 硬编码调色板，复古掌机亮色系
    val ALL: Map<String, SpeciesDef> = mapOf(
        "cat" to SpeciesDef(
            "cat", "cat_ears", "none", "curvy",
            PetPalette(0xFFFF8A65.toInt(), 0xFFE64A19.toInt(), 0xFFFFE0D6.toInt(),
                0xFFBF360C.toInt(), 0xFF4E342E.toInt(), 0xFFF48FB1.toInt(), 0xFFFFD54F.toInt())
        ),
        "dog" to SpeciesDef(
            "dog", "drop_ears", "none", "nub",
            PetPalette(0xFFA1887F.toInt(), 0xFF6D4C41.toInt(), 0xFFEFEBE9.toInt(),
                0xFF5D4037.toInt(), 0xFF3E2723.toInt(), 0xFFBCAAA4.toInt(), 0xFF8D6E63.toInt())
        ),
        "rabbit" to SpeciesDef(
            "rabbit", "long_ears", "none", "cotton",
            PetPalette(0xFFECEFF1.toInt(), 0xFFB0BEC5.toInt(), 0xFFFFFFFF.toInt(),
                0xFFF8BBD0.toInt(), 0xFF546E7A.toInt(), 0xFFF48FB1.toInt(), 0xFFFFD54F.toInt())
        ),
        "penguin" to SpeciesDef(
            "penguin", "none", "none", "fan",
            PetPalette(0xFF455A64.toInt(), 0xFF263238.toInt(), 0xFFFFFFFF.toInt(),
                0xFFFFB300.toInt(), 0xFF212121.toInt(), 0xFF90A4AE.toInt(), 0xFFFFB300.toInt()),
            beak = true
        ),
        "hamster" to SpeciesDef(
            "hamster", "round_ears", "none", "none",
            PetPalette(0xFFFFCA28.toInt(), 0xFFF9A825.toInt(), 0xFFFFF8E1.toInt(),
                0xFFFF8A65.toInt(), 0xFF5D4037.toInt(), 0xFFFFAB91.toInt(), 0xFFFF7043.toInt())
        ),
        "panda" to SpeciesDef(
            "panda", "round_ears", "none", "nub",
            PetPalette(0xFFFFFFFF.toInt(), 0xFFB0BEC5.toInt(), 0xFFFFFFFF.toInt(),
                0xFF212121.toInt(), 0xFF212121.toInt(), 0xFFFFCDD2.toInt(), 0xFF4CAF50.toInt()),
            eyePatch = true
        ),
        "fox" to SpeciesDef(
            "fox", "big_ears", "none", "bushy",
            PetPalette(0xFFFF7043.toInt(), 0xFFD84315.toInt(), 0xFFFFF3E0.toInt(),
                0xFFBF360C.toInt(), 0xFF4E342E.toInt(), 0xFFFFAB91.toInt(), 0xFFFFE082.toInt())
        ),
        "dragon" to SpeciesDef(
            "dragon", "horns", "wings", "longtail",
            PetPalette(0xFF66BB6A.toInt(), 0xFF2E7D32.toInt(), 0xFFFFF9C4.toInt(),
                0xFFFFA726.toInt(), 0xFF1B5E20.toInt(), 0xFFEF9A9A.toInt(), 0xFFFFD54F.toInt())
        ),
        "sheep" to SpeciesDef(
            "sheep", "wool_horns", "frill", "nub",
            PetPalette(0xFFCFD8DC.toInt(), 0xFF90A4AE.toInt(), 0xFFFFFFFF.toInt(),
                0xFF795548.toInt(), 0xFF455A64.toInt(), 0xFFFFCDD2.toInt(), 0xFFFFB300.toInt())
        ),
        "hedgehog" to SpeciesDef(
            "hedgehog", "tiny_ears", "spikes", "none",
            PetPalette(0xFFBCAAA4.toInt(), 0xFF6D4C41.toInt(), 0xFFFFE0B2.toInt(),
                0xFF4E342E.toInt(), 0xFF3E2723.toInt(), 0xFFFFAB91.toInt(), 0xFF8D6E63.toInt())
        ),
        // ---- 2026-08-31 附录A 追加 4 物种（仅渲染层形态，业务零改动） ----
        "frog" to SpeciesDef(
            "frog", "frog_eyes", "none", "none",
            PetPalette(0xFF66BB6A.toInt(), 0xFF2E7D32.toInt(), 0xFFDCEDC8.toInt(),
                0xFF43A047.toInt(), 0xFF1B5E20.toInt(), 0xFFA5D6A7.toInt(), 0xFFFFEE58.toInt())
        ),
        "pig" to SpeciesDef(
            "pig", "tiny_ears", "none", "curvy",
            PetPalette(0xFFF48FB1.toInt(), 0xFFD81B60.toInt(), 0xFFFFFDE7.toInt(),
                0xFFF06292.toInt(), 0xFF880E4F.toInt(), 0xFFF8BBD0.toInt(), 0xFFFFD54F.toInt()),
            snout = true
        ),
        "owl" to SpeciesDef(
            "owl", "tufts", "wings", "none",
            PetPalette(0xFF8D6E63.toInt(), 0xFF4E342E.toInt(), 0xFFFFF8E1.toInt(),
                0xFFA1887F.toInt(), 0xFF3E2723.toInt(), 0xFFD7CCC8.toInt(), 0xFFFFB300.toInt()),
            beak = true, bigEyes = true
        ),
        "snake" to SpeciesDef(
            "snake", "none", "none", "coil",
            PetPalette(0xFF26A69A.toInt(), 0xFF00695C.toInt(), 0xFFE0F2F1.toInt(),
                0xFF4DB6AC.toInt(), 0xFF004D40.toInt(), 0xFF80CBC4.toInt(), 0xFFFF7043.toInt()),
            noLimbs = true
        )
    )

    fun of(code: String?): SpeciesDef = ALL[code] ?: ALL.getValue("cat")
}

/** 装饰（头饰槽佩戴外观） */
object PixelDecor {
    const val NONE = ""
    const val BANDANA = "deco_bandana"
    const val GLASSES = "deco_glasses"
    const val BOW = "deco_bow"
    const val CROWN = "deco_crown"
}
