package com.inklink.host.state

import android.content.Context

/**
 * 宠物渲染外观模式（2026-08-30 新增，业务层零改动）：
 * - [VECTOR_OLD]：原有 Canvas 矢量部件渲染（默认，保持既有形象）；
 * - [PIXEL_RETRO]：复古像素块渲染（WristPet 式 drawRect 像素精灵，同一状态驱动）；
 * - [PIXEL_PNG]：PNG 素材渲染（design 附录A），缺帧自动回退——
 *   精确状态PNG → idle+程序化头顶角标(sad/annoy/sick) → 整只回退 [PIXEL_RETRO]。
 * - [PIXEL_ARCADE_SPRITE]：街机像素 PNG 素材（dongwu 图集，256×256，每状态3帧循环），
 *   缺物种/缺帧回退 [PIXEL_PNG] → [PIXEL_RETRO]。
 * - [PIXEL_SCENE]：云朵 LCD 场景宠（附录C，250×250 整屏场景底 + 96×96 迷你角色帧 +
 *   程序道具叠加）。当前为模式驱动：选中本模式即以云朵角色渲染，缺素材回退 [PIXEL_PNG]。
 *   （规划中：引入「云朵」物种后，云朵物种自动走本路径，其它物种选本模式回退 [PIXEL_PNG]。）
 *
 * 选择持久化在 SharedPreferences，家长侧设置界面和受控端宠物主界面均可切换。
 * 枚举值仅可追加，VECTOR_OLD/PIXEL_RETRO 名称不变以兼容老存档。
 */
enum class PetRenderMode {
    VECTOR_OLD,
    PIXEL_RETRO,
    PIXEL_PNG,
    PIXEL_ARCADE_SPRITE,
    PIXEL_SCENE;

    companion object {
        private const val PREFS = "inklink_host"
        private const val KEY_RENDER_MODE = "pet_render_mode"

        fun get(context: Context): PetRenderMode {
            val name = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_RENDER_MODE, null) ?: return PIXEL_PNG
            return runCatching { valueOf(name) }.getOrDefault(PIXEL_PNG)
        }

        fun set(context: Context, mode: PetRenderMode) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_RENDER_MODE, mode.name).apply()
        }
    }
}
