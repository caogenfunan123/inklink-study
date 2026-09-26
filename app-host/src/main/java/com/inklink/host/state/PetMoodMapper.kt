package com.inklink.host.state

/**
 * 宠物状态四维 + 健康 -> 情绪映射（参考 hogotchi HogState.calculateMood）。
 * 优先级：衰弱 > 睡眠 > 饥饿 > 病容 > 困倦 > 脏乱 > 开心 > 正常。
 * 结果驱动 PetSpriteView 表情渲染与状态提示气泡。
 */
object PetMoodMapper {

    /**
     * @param weak 虚弱（isAlive=false 或 health==0），TA 睁不开眼
     */
    fun moodOf(hunger: Int, happiness: Int, clean: Int, energy: Int, health: Int, sleeping: Boolean, weak: Boolean): String = when {
        weak -> "WEAK"
        sleeping -> "SLEEPY"
        hunger < 30 -> "HUNGRY"
        health < 40 -> "SICK"
        energy < 25 -> "SLEEPY"
        clean < 30 -> "DIRTY"
        happiness > 75 -> "HAPPY"
        else -> "NORMAL"
    }

    /** 旧签名兼容：无健康/睡眠上下文时按四维映射。 */
    fun moodOf(hunger: Int, happiness: Int, clean: Int, energy: Int): String =
        moodOf(hunger, happiness, clean, energy, 100, false, false)

    /** 情绪对应的提示气泡文案；正常/开心状态无气泡返回 null。 */
    fun bubbleText(mood: String): String? = when (mood) {
        "WEAK" -> "TA虚弱得睁不开眼，快用治疗药剂唤醒！"
        "HUNGRY" -> "肚子饿啦，喂点好吃的吧"
        "SICK" -> "身体不适，需要治疗和照顾"
        "SLEEPY" -> "困了，让TA睡一会儿吧"
        "DIRTY" -> "身上脏脏的，洗个泡泡浴吧"
        else -> null
    }

    /** 生命周期阶段中文名 */
    fun stageLabel(lifeStage: String): String = when (lifeStage) {
        "EGG" -> "🥚 蛋"
        "CUB" -> "🍼 幼年"
        "JUVENILE" -> "🌱 少年"
        "ADOLESCENT" -> "🌿 青年"
        "ADULT" -> "🌟 成年"
        else -> "🌟 成年"
    }

    /** 成年形态中文名（finalForm 为空=未定型） */
    fun formLabel(finalForm: String): String? = when (finalForm) {
        "BALANCED" -> "⚖️ 均衡形态"
        "PLAYFUL" -> "🎉 活泼爱玩形态"
        "STUDIOUS" -> "📚 文静学霸形态"
        "DROOPY" -> "🌧️ 低落萎靡形态"
        else -> null
    }

    /** HUD 游戏化文案（V1.1：数值名从体检表改成游戏腔） */
    fun hudLabel(stat: String): String = when (stat) {
        "hunger" -> "🍖 饱食"
        "happiness" -> "💖 开心"
        "energy" -> "⚡ 精力"
        "clean" -> "🫧 清洁"
        else -> stat
    }
}
