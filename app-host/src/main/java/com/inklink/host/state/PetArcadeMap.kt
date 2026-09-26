package com.inklink.host.state

/**
 * 街机像素素材（PIXEL_ARCADE_SPRITE）物种映射。
 *
 * 素材来源：`assets/pixel_arcade/{species}/{code}_{state}_{NN}.png`（256×256、RGBA、透明底），
 * 由 `tools/pet_arcade/convert_arcade.py` 从 dongwu 图集白底抠图 + 噪点清除产出，共 20 物种。
 *
 * InkLink 14 物种中 dongwu 覆盖 13 个；sheep（小羊）无对应素材，走三级回退
 * （PIXEL_ARCADE → PIXEL_PNG → PIXEL_RETRO）。其余 7 个扩展物种（bear/bird/fish/mouse/
 * tiger/turtle/wolf）为 dongwu 独有，InkLink 宠物不会触发，仅保留映射说明。
 */
object PetArcadeMap {

    /** 有 256×256 街机素材的 InkLink 物种 code 集合（含 dongwu 扩展物种，商店可购即渲染）。 */
    val SUPPORTED: Set<String> = setOf(
        "cat", "dog", "rabbit", "fox", "frog", "hamster", "pig",
        "panda", "owl", "dragon", "snake", "hedgehog", "penguin",
        "bear", "bird", "fish", "mouse", "tiger", "turtle", "wolf"
    )

    /** 状态 → 文件名中的状态名（dongwu 素材状态与 pixelState 输出一致）。 */
    val STATE_FILE: Map<String, String> = mapOf(
        "IDLE" to "idle",
        "HUNGRY" to "hungry",
        "HAPPY" to "happy",
        "SLEEP" to "sleep"
    )

    /** SAD/ANNOY/SICK 无独立素材 → 复用 idle 本体（与 PIXEL_PNG 二级回退同语义）。 */
    val FALLBACK_STATE: Map<String, String> = mapOf(
        "SAD" to "idle",
        "ANNOY" to "idle",
        "SICK" to "idle"
    )

    /** 每状态动画帧数（01..03 循环）。 */
    const val FRAMES_PER_STATE = 3
}
