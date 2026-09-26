package com.inklink.host.state

import com.inklink.host.R

/**
 * PNG 素材物种资源映射（design 附录A.2/A.3）。
 *
 * 渲染决策（PIXEL_PNG 模式）：精确状态 PNG → idle+头顶角标(sad/annoy/sick) → 整只回退程序化。
 * 本表资源位使用 Android drawable 资源 ID（可空；null = 缺帧）。
 *
 * 素材来源：`tools/pet_sprite/sprite_author.py` 直接把 `PixelSpecies` 的矢量 LUT 光栅化成
 * 24×24 逻辑格 × 4px 的 96×96 草稿，再由 `sprite_pipeline.py` 无损直通（scale=1.0、身体整数
 * 平移回中、道具分层原位贴回）产出 65 张入本目录。风格与 VECTOR_OLD/PIXEL_RETRO 同源，
 * 锚点与帧间零漂移由构造保证，不依赖任何运行时 offset 补丁（附录A.6）。
 *
 * 换风格/修造型只需改画师常量 → 重跑两级脚本 → 覆盖本目录，业务层/状态机/协议/数据库零改动。
 * 素材缺失时期望行为：该项为 null → 该帧回退 idle → 整只回退程序化。
 */
data class PetSpriteResource(
    val idle: Int? = null,
    val hungry: Int? = null,
    val happy: Int? = null,
    val sleep: Int? = null,
    /** 呼吸样板帧 a（仅狗/猫/兔；缺则回退 idle） */
    val idleA: Int? = null,
    /** 呼吸样板帧 b（仅狗/猫/兔；缺则回退 idle） */
    val idleB: Int? = null,
    /** 眨眼样板帧（仅狗/猫/兔；缺则不眨眼） */
    val blink: Int? = null
) {
    /** 该物种是否至少具备可上屏的核心帧。 */
    val hasCoreFrames: Boolean get() = idle != null
}

object PetSpriteResMap {

    /** 14 物种全量键位（code 与 PetCatalog.SPECIES 严格一致）。 */
    val ALL: Map<String, PetSpriteResource> = mapOf(
        "cat" to PetSpriteResource(idle=R.drawable.cat_idle, hungry=R.drawable.cat_hungry, happy=R.drawable.cat_happy, sleep=R.drawable.cat_sleep, idleA=R.drawable.cat_idle_a, idleB=R.drawable.cat_idle_b, blink=R.drawable.cat_blink),
        "dog" to PetSpriteResource(idle=R.drawable.dog_idle, hungry=R.drawable.dog_hungry, happy=R.drawable.dog_happy, sleep=R.drawable.dog_sleep, idleA=R.drawable.dog_idle_a, idleB=R.drawable.dog_idle_b, blink=R.drawable.dog_blink),
        "rabbit" to PetSpriteResource(idle=R.drawable.rabbit_idle, hungry=R.drawable.rabbit_hungry, happy=R.drawable.rabbit_happy, sleep=R.drawable.rabbit_sleep, idleA=R.drawable.rabbit_idle_a, idleB=R.drawable.rabbit_idle_b, blink=R.drawable.rabbit_blink),
        "penguin" to PetSpriteResource(idle=R.drawable.penguin_idle, hungry=R.drawable.penguin_hungry, happy=R.drawable.penguin_happy, sleep=R.drawable.penguin_sleep),
        "hamster" to PetSpriteResource(idle=R.drawable.hamster_idle, hungry=R.drawable.hamster_hungry, happy=R.drawable.hamster_happy, sleep=R.drawable.hamster_sleep),
        "panda" to PetSpriteResource(idle=R.drawable.panda_idle, hungry=R.drawable.panda_hungry, happy=R.drawable.panda_happy, sleep=R.drawable.panda_sleep),
        "fox" to PetSpriteResource(idle=R.drawable.fox_idle, hungry=R.drawable.fox_hungry, happy=R.drawable.fox_happy, sleep=R.drawable.fox_sleep),
        "dragon" to PetSpriteResource(idle=R.drawable.dragon_idle, hungry=R.drawable.dragon_hungry, happy=R.drawable.dragon_happy, sleep=R.drawable.dragon_sleep),
        "sheep" to PetSpriteResource(idle=R.drawable.sheep_idle, hungry=R.drawable.sheep_hungry, happy=R.drawable.sheep_happy, sleep=R.drawable.sheep_sleep),
        "hedgehog" to PetSpriteResource(idle=R.drawable.hedgehog_idle, hungry=R.drawable.hedgehog_hungry, happy=R.drawable.hedgehog_happy, sleep=R.drawable.hedgehog_sleep),
        "frog" to PetSpriteResource(idle=R.drawable.frog_idle, hungry=R.drawable.frog_hungry, happy=R.drawable.frog_happy, sleep=R.drawable.frog_sleep),
        "pig" to PetSpriteResource(idle=R.drawable.pig_idle, hungry=R.drawable.pig_hungry, happy=R.drawable.pig_happy, sleep=R.drawable.pig_sleep),
        "owl" to PetSpriteResource(idle=R.drawable.owl_idle, hungry=R.drawable.owl_hungry, happy=R.drawable.owl_happy, sleep=R.drawable.owl_sleep),
        "snake" to PetSpriteResource(idle=R.drawable.snake_idle, hungry=R.drawable.snake_hungry, happy=R.drawable.snake_happy, sleep=R.drawable.snake_sleep)
    )

    /** 物种查资源；未收录/无核心帧 → null（渲染层据此整只回退程序化）。 */
    fun of(code: String?): PetSpriteResource? {
        val res = ALL[code] ?: return null
        return if (res.hasCoreFrames) res else null
    }
}
