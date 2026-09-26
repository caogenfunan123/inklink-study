package com.inklink.host.state

import com.inklink.host.R

/**
 * PIXEL_SCENE 云朵 LCD 场景宠资源映射（附录C）。
 *
 * 素材来源：`tools/pet_blob/`（`make_scenes.py` 场景底图 + `make_actions*.py` 角色帧），
 * 由 `deploy_assets.py` 二值化 alpha 后落盘 `res/drawable-nodpi/`：
 * - `cloud_<state>.png`：96×96 云朵角色帧（状态闭集见 [PetSpriteAssetContractTest]）；
 * - `scene_<id>.png`：250×250 整屏室内场景底图（id 与 [PetCatalog.SCENES] 一致）。
 *
 * 与 PIXEL_PNG 同规则：整数倍放大 + 硬边无插值；素材缺失时该帧/该场景回退，
 * 整只回退 [PetRenderMode.PIXEL_PNG]。
 */
object PetSceneMap {

    /**
     * 云朵角色状态 → 循环帧资源序列（动画按固定节拍在序列内循环）。
     * 状态键与 [PetSpriteView] 内部的状态选择一致：IDLE/SLEEP/EAT/READ/CLEAN/
     * ANNOY/HAPPY/HUNGRY/SAD/SICK（SICK 无专属帧，回退 SAD 表情）。
     */
    val CLOUD: Map<String, List<Int>> = mapOf(
        "IDLE" to listOf(R.drawable.cloud_idle_a, R.drawable.cloud_idle_b),
        "BLINK" to listOf(R.drawable.cloud_blink),
        "HUNGRY" to listOf(R.drawable.cloud_hungry),
        "HAPPY" to listOf(R.drawable.cloud_happy_1, R.drawable.cloud_happy_2, R.drawable.cloud_happy_3),
        "SLEEP" to listOf(R.drawable.cloud_sleep_1, R.drawable.cloud_sleep_2),
        "EAT" to listOf(R.drawable.cloud_eat_1, R.drawable.cloud_eat_2, R.drawable.cloud_eat_3),
        "READ" to listOf(R.drawable.cloud_read_1, R.drawable.cloud_read_2),
        "CLEAN" to listOf(R.drawable.cloud_clean_1, R.drawable.cloud_clean_2),
        "ANNOY" to listOf(R.drawable.cloud_annoy_1, R.drawable.cloud_annoy_2),
        "SAD" to listOf(R.drawable.cloud_sad),
        "SICK" to listOf(R.drawable.cloud_sad)
    )

    /** 场景 id → 250×250 底图资源（缺 id 回退 bedroom）。 */
    val SCENES: Map<String, Int> = mapOf(
        "bedroom" to R.drawable.scene_bedroom,
        "living_room" to R.drawable.scene_living_room,
        "windowsill" to R.drawable.scene_windowsill
    )

    /** 场景画布边长（250×250，附录C）。 */
    const val SCENE_SIZE = 250

    /** 角色帧画布边长（96×96，附录C）。 */
    const val FRAME_SIZE = 96

    /** 场景内墙地分界（地面线 y，宠物脚线，= 0.9 * 250 ≈ 226）。 */
    const val FLOOR_Y = 226

    /** 角色帧在场景内的水平锚点（96 宽居中：77..173）。 */
    const val PET_X = (SCENE_SIZE - FRAME_SIZE) / 2

    /** 角色帧内"静息脚底"像素行（idle_a 的不透明底边），对齐地面线用。 */
    const val FEET_ROW = 86
}
