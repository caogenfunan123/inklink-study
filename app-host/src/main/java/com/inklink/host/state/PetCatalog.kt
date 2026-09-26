package com.inklink.host.state

/**
 * 游戏化静态目录（V1.1）：道具 / 物种 / 装饰 / 场景 / Buff 唯一数据源。
 * UI(商店/背包/赠送面板) 与业务(消耗/解锁/入账) 全部引用本表，禁止散落硬编码。
 */
object PetCatalog {

    data class ItemDef(
        val id: String,
        val name: String,
        val emoji: String,
        val price: Int,
        val desc: String
    )

    /** 一次性消耗道具（裁决：行为全部消耗化；2026-08-30 降价修订） */
    val ITEMS: Map<String, ItemDef> = listOf(
        ItemDef("food_normal", "普通食物", "🍙", 5, "饥饿+25"),
        ItemDef("food_premium", "高级大餐", "🍱", 15, "饥饿+50 开心+10"),
        ItemDef("toy", "玩具球", "🎾", 10, "玩耍:开心+30 精力-15"),
        ItemDef("book", "故事书", "📖", 10, "学习:成长+35 精力-20 开心-8"),
        ItemDef("shower_gel", "沐浴露", "🧴", 8, "清洁+45"),
        ItemDef("sleep_potion", "睡眠药水", "🧪", 12, "精力+60"),
        ItemDef("potion_heal", "治疗药剂", "💊", 25, "健康恢复满并唤醒")
    ).associateBy { it.id }

    /** 蛋/物种（14 种，共骨架+三件套贴片+LUT；2026-08-31 附录A追加 frog/pig/owl/snake）。
     *  2026-08-31 补 7 个 dongwu 素材扩展物种（bear/bird/fish/mouse/tiger/turtle/wolf），
     *  商店可购买；矢量/复古/PNG 档回退 cat 兜底，街机素材档(PIXEL_ARCADE_SPRITE)直接渲染。 */
    data class SpeciesDef(val code: String, val name: String, val emoji: String, val eggPrice: Int)

    val SPECIES: List<SpeciesDef> = listOf(
        SpeciesDef("cat", "小猫", "🐱", 0),        // 初始伙伴，不出售
        SpeciesDef("dog", "小狗", "🐶", 50),
        SpeciesDef("rabbit", "小兔", "🐰", 60),
        SpeciesDef("penguin", "企鹅", "🐧", 75),
        SpeciesDef("frog", "小青蛙", "🐸", 70),
        SpeciesDef("hamster", "仓鼠", "🐹", 90),
        SpeciesDef("pig", "小猪", "🐷", 100),
        SpeciesDef("panda", "熊猫", "🐼", 110),
        SpeciesDef("fox", "狐狸", "🦊", 130),
        SpeciesDef("owl", "猫头鹰", "🦉", 150),
        SpeciesDef("dragon", "小龙", "🐉", 160),
        SpeciesDef("sheep", "小羊", "🐑", 180),
        SpeciesDef("snake", "小蛇", "🐍", 190),
        SpeciesDef("hedgehog", "刺猬", "🦔", 200),
        SpeciesDef("bear", "小熊", "🐻", 220),
        SpeciesDef("tiger", "小老虎", "🐯", 230),
        SpeciesDef("wolf", "小狼", "🐺", 240),
        SpeciesDef("turtle", "小乌龟", "🐢", 250),
        SpeciesDef("bird", "小鸟", "🐦", 260),
        SpeciesDef("mouse", "小老鼠", "🐭", 270),
        SpeciesDef("fish", "小鱼", "🐟", 280)
    )

    fun speciesOf(code: String): SpeciesDef =
        SPECIES.firstOrNull { it.code == code } ?: SPECIES.first()

    /** 外观装饰（头饰槽） */
    data class DecoDef(val id: String, val name: String, val emoji: String, val price: Int)

    val DECOS: List<DecoDef> = listOf(
        DecoDef("deco_bandana", "小头巾", "🧣", 40),
        DecoDef("deco_glasses", "圆眼镜", "👓", 40),
        DecoDef("deco_bow", "蝴蝶结", "🎀", 50),
        DecoDef("deco_crown", "小皇冠", "👑", 75)
    )

    fun decoOf(id: String): DecoDef? = DECOS.firstOrNull { it.id == id }

    /** 像素房间场景（等级解锁） */
    data class SceneDef(val id: String, val name: String, val emoji: String, val unlockLevel: Int)

    val SCENES: List<SceneDef> = listOf(
        SceneDef("bedroom", "温馨小卧室", "🛏️", 1),
        SceneDef("living_room", "阳光小客厅", "🛋️", 3),
        SceneDef("windowsill", "观景窗台", "🪟", 5)
    )

    fun sceneOf(id: String): SceneDef? = SCENES.firstOrNull { it.id == id }

    /** 远程 Buff 增益（不进背包，即时生效 + 10min 该属性暂停衰减，裁决 #2） */
    const val BUFF_ENERGY = "buff_energy"   // 精力 +40
    const val BUFF_MOOD = "buff_mood"       // 开心 +30

    /** 每日目标阈值 */
    const val DAILY_FEED_GOAL = 2
    const val DAILY_PLAY_GOAL = 1
    const val DAILY_CLEAN_GOAL = 1
    const val DAILY_REWARD_COIN = 60

    /** 新背包初始赠礼（保证首次体验闭环） */
    val STARTER_STOCK = mapOf(
        "food_normal" to 5,
        "toy" to 2,
        "book" to 2,
        "shower_gel" to 2,
        "sleep_potion" to 1,
        "potion_heal" to 1
    )
}
