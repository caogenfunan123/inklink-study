package com.inklink.host.state

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.Inflater
import kotlin.math.abs

/**
 * design 附录A：PNG 素材契约 JVM 粗筛单测（阶段4/5 素材入库后的机械保险）。
 *
 * 扫描 app-host/src/main/res/drawable-nodpi/ 的宠物素材，断言：
 * 1) 命名 = 物种code_状态.png，code ∈ 14 物种，状态 ∈ 封闭集合；
 * 2) 画布严格 96×96；
 * 3) 格式 = 8bit 非隔行 RGBA/RGB/Gray PNG-32（拒绝 16bit/调色板/隔行——Aseprite 标准导出即此）；
 * 4) Alpha 仅 0/255（二值化，禁半透明杂边）；
 * 5) 中心胸区 5×5 存在不透明像素（锚点压在身体上，不是飘在空白）；
 * 6) 同物种各核心帧 alpha 质心偏移 ≤2px（身体错位/忘记对齐的漏网图）。
 *
 * 像素解码用手写 PNG Reader（Android 单测 classpath 用 android.jar，剥离了
 * java.awt / javax.imageio，这是平台限制而非偷懒）。
 *
 * 注意：CI 只能粗筛，不能替代人工终检（造型好不好看，机器判不了）。
 * 阶段4 素材已入库，故本测试额外硬断言"文件集合 == 契约闭集"：少一张 = 引擎侧 drawable 缺失
 * （R.drawable 引用会编译失败或被误删），多一张 = 陈旧素材混进 APK。与 sprite_pipeline.py 的
 * 收尾断言同源，两边任一被改动都会立刻暴露。
 */
class PetSpriteAssetContractTest {

    private val speciesCodes = setOf(
        "cat", "dog", "rabbit", "penguin", "hamster", "panda", "fox",
        "dragon", "sheep", "hedgehog", "frog", "pig", "owl", "snake"
    )
    private val stateNames = setOf("idle", "hungry", "happy", "sleep", "idle_a", "idle_b", "blink")

    /** 云朵 LCD 场景宠素材闭集（附录C：PIXEL_SCENE）。 */
    private val cloudStates = setOf(
        "idle_a", "idle_b", "blink", "hungry", "sad",
        "happy_1", "happy_2", "happy_3",
        "sleep_1", "sleep_2",
        "eat_1", "eat_2", "eat_3",
        "read_1", "read_2",
        "clean_1", "clean_2",
        "annoy_1", "annoy_2"
    )
    /** 云朵"静态表情帧"（非故意位移的动作帧）质心必须钉在 idle_a ≤2px；
     *  动作帧（跳起/低头/压扁）是渲染层故意位移，仅校验水平漂移与中心锚点。 */
    private val cloudStaticStates = setOf("idle_b", "blink", "hungry", "sad", "annoy_1", "annoy_2", "clean_1", "clean_2")
    /** 250×250 整屏场景底图（附录C），id 与 PetCatalog.SCENES 一致。 */
    private val sceneIds = setOf("bedroom", "living_room", "windowsill")

    /** 只有这三档在渲染层做呼吸交替/眨眼（PetSpriteView.breathRes），其余物种不产出这两帧。 */
    private val breathSpecies = setOf("cat", "dog", "rabbit")

    private fun resDir(): File {
        // 模块目录 / 仓库根两种工作目录都兼容
        val candidates = listOf(
            File("src/main/res/drawable-nodpi"),
            File("app-host/src/main/res/drawable-nodpi")
        )
        return candidates.firstOrNull { it.isDirectory } ?: candidates[0]
    }

    @Test
    fun `nodpi 宠物素材符合附录A契约`() {
        val dir = resDir()
        assertTrue("找不到 drawable-nodpi 目录（素材被整目录删除？）: ${dir.path}", dir.isDirectory)
        val files = (dir.listFiles { f -> f.isFile && f.name.endsWith(".png") } ?: emptyArray()).sortedBy { it.name }
        val expectedLegacy = buildSet {
            speciesCodes.forEach { c ->
                listOf("idle", "hungry", "sleep", "happy").forEach { st -> add("${c}_$st") }
                if (c in breathSpecies) {
                    add("${c}_blink"); add("${c}_idle_a"); add("${c}_idle_b")
                }
            }
        }
        val expected = buildSet {
            addAll(expectedLegacy)
            cloudStates.forEach { add("cloud_$it") }
            sceneIds.forEach { add("scene_$it") }
        }
        val actual = files.map { it.name.removeSuffix(".png") }.toSet()
        assertTrue(
            "素材集合 != 契约闭集：缺=${expected - actual} 多=${actual - expected}",
            actual == expected
        )
        val problems = mutableListOf<String>()

        for (f in files) {
            val stem = f.name.removeSuffix(".png")
            val png = try {
                PngAlpha8(f.readBytes())
            } catch (e: Exception) {
                problems += "PNG 解码失败/格式违规(${f.name}): ${e.message}"
                continue
            }
            // ---- 附录C: 场景底图 250×250 ----
            if (stem.startsWith("scene_")) {
                if (stem.removePrefix("scene_") !in sceneIds) {
                    problems += "未知场景id(附录C): ${f.name}"; continue
                }
                if (png.width != 250 || png.height != 250) {
                    problems += "场景画布非250×250(附录C): ${f.name} = ${png.width}x${png.height}"
                }
                var halfAlpha = 0
                for (y in 0 until png.height) for (x in 0 until png.width) {
                    val a = png.alphaAt(x, y)
                    if (a != 0 && a != 255) halfAlpha++
                }
                if (halfAlpha > 0) problems += "场景半透明像素${halfAlpha}个(附录C): ${f.name}"
                continue
            }
            // ---- 附录C: 云朵宠帧 96×96 ----
            if (stem.startsWith("cloud_")) {
                val state = stem.removePrefix("cloud_")
                if (state !in cloudStates) {
                    problems += "未知云朵帧(附录C闭集): ${f.name}"; continue
                }
                if (png.width != 96 || png.height != 96) {
                    problems += "云朵画布非96×96(附录C): ${f.name} = ${png.width}x${png.height}"
                }
                val mask = BooleanArray(png.width * png.height)
                var halfAlpha = 0
                var centerOpaque = false
                for (y in 0 until png.height) for (x in 0 until png.width) {
                    val a = png.alphaAt(x, y)
                    if (a != 0 && a != 255) halfAlpha++
                    if (a > 0) mask[y * png.width + x] = true
                    if (a > 0 && x in 46..50 && y in 46..50) centerOpaque = true
                }
                if (halfAlpha > 0) problems += "云朵半透明像素${halfAlpha}个(附录C): ${f.name}"
                if (!centerOpaque) problems += "云朵锚点空洞: ${f.name} 中心5×5全透明"
                if (state in cloudStaticStates) {
                    png.bodyCentroid(mask)?.let { (cx, cy) ->
                        cloudStaticCentroid[state] = cx to cy
                    }
                }
                continue
            }
            // ---- 附录A: 既有 14 物种 96×96 ----
            val state = stateNames.filter { stem.endsWith("_$it") }.maxByOrNull { it.length }
            val code = if (state == null) null else stem.removeSuffix("_$state")
            if (state == null || code.isNullOrEmpty()) {
                problems += "命名违反附录A.2(状态后缀不在闭集): ${f.name}"; continue
            }
            if (code !in speciesCodes) { problems += "未知物种code(附录A.2): ${f.name}"; continue }

            if (png.width != 96 || png.height != 96) {
                problems += "画布非96×96(附录A.1): ${f.name} = ${png.width}x${png.height}"
            }
            var halfAlpha = 0
            var centerOpaque = false
            val mask = BooleanArray(png.width * png.height)
            for (y in 0 until png.height) {
                for (x in 0 until png.width) {
                    val a = png.alphaAt(x, y)
                    if (a != 0 && a != 255) halfAlpha++
                    if (a > 0) mask[y * png.width + x] = true
                    if (a > 0 && x in 46..50 && y in 46..50) centerOpaque = true
                }
            }
            if (halfAlpha > 0) problems += "半透明像素${halfAlpha}个(附录A.1 alpha二值化): ${f.name}"
            if (!centerOpaque && png.width == 96 && png.height == 96) {
                problems += "锚点空洞: ${f.name} 中心5×5全透明(身体未压住(48,48), 回 Aseprite 修)"
            }
            png.bodyCentroid(mask)?.let { (cx, cy) ->
                frames.getOrPut(code) { mutableMapOf() }[state] = cx to cy
            }
        }

        // 帧间质心一致（以 idle 为基准 ≤2px，粗位置保险，语义重心仍靠人工终检）
        for ((code, byState) in frames) {
            val ref = byState["idle"] ?: continue
            for ((state, c) in byState) {
                if (state == "idle") continue
                if (abs(c.first - ref.first) > 2.0 || abs(c.second - ref.second) > 2.0) {
                    problems += "帧间身体漂移(附录A.1锚点契约): $code/$state 质心=(${fmt(c.first)},${fmt(c.second)}) idle=(${fmt(ref.first)},${fmt(ref.second)}) 超2px"
                }
            }
        }

        // 云朵静态表情帧质心钉在 idle_a（两阶段：先收集后比较，避免受文件字母序影响）
        val cloudRef = cloudStaticCentroid["idle_a"]
        if (cloudRef != null) {
            for ((state, c) in cloudStaticCentroid) {
                if (state == "idle_a") continue
                if (abs(c.first - cloudRef.first) > 2.0 || abs(c.second - cloudRef.second) > 2.0) {
                    problems += "云朵静态帧漂移(附录C): cloud/$state 质心=(${fmt(c.first)},${fmt(c.second)}) idle_a=(${fmt(cloudRef.first)},${fmt(cloudRef.second)}) 超2px"
                }
            }
        }

        if (problems.isNotEmpty()) {
            assertTrue("素材契约违规:\n" + problems.joinToString("\n"), problems.isEmpty())
        }
    }

    private val frames = mutableMapOf<String, MutableMap<String, Pair<Double, Double>>>()
    private val cloudStaticCentroid = mutableMapOf<String, Pair<Double, Double>>()

    private fun fmt(v: Double) = String.format("%.1f", v)
}

/**
 * 极简 PNG 解码器：只取 alpha 通道，支持 8bit、colorType 0/2/4/6、非隔行。
 * 其余格式一律抛异常（对附录A 的 PNG-32 导出契约来说，抛异常 = 违规 = 测试失败，正是我们要的保险）。
 */
private class PngAlpha8(bytes: ByteArray) {

    val width: Int
    val height: Int
    private val alpha: ByteArray

    fun alphaAt(x: Int, y: Int): Int = alpha[y * width + x].toInt() and 0xFF

    init {
        val sig = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10)
        if (bytes.size <= 8 || !bytes.copyOfRange(0, 8).contentEquals(sig)) error("非PNG签名")
        var pos = 8
        var w = 0; var h = 0; var bd = -1; var ct = -1; var il = -1
        var sawIhdr = false; var sawEnd = false
        val idat = ByteArrayOutputStream()
        while (pos + 12 <= bytes.size && !sawEnd) {
            val len = readIntBE(bytes, pos)
            val type = String(bytes, pos + 4, 4, Charsets.ISO_8859_1)
            val dataStart = pos + 8
            if (len < 0 || dataStart + len + 4 > bytes.size) error("chunk越界: $type")
            when (type) {
                "IHDR" -> {
                    if (sawIhdr || len < 13) error("IHDR异常")
                    w = readIntBE(bytes, dataStart); h = readIntBE(bytes, dataStart + 4)
                    bd = bytes[dataStart + 8].toInt() and 0xFF
                    ct = bytes[dataStart + 9].toInt() and 0xFF
                    il = bytes[dataStart + 12].toInt() and 0xFF
                    sawIhdr = true
                }
                "IDAT" -> idat.write(bytes, dataStart, len)
                "IEND" -> sawEnd = true
            }
            pos = dataStart + len + 4 // 跳过 data + CRC
        }
        if (!sawIhdr) error("缺IHDR")
        if (bd != 8) error("非8bit深度(bd=$bd), 附录A要求PNG-32")
        if (il != 0) error("隔行扫描不支持(il=$il)")
        val channels = when (ct) {
            0 -> 1; 2 -> 3; 4 -> 2; 6 -> 4
            else -> error("调色板/非标colorType=$ct, 请导出RGBA PNG-32")
        }
        width = w; height = h
        alpha = ByteArray(w * h)
        val rowBytes = w * channels
        val stride = rowBytes + 1
        val raw = ByteArray(h * stride)
        val inf = Inflater()
        try {
            inf.setInput(idat.toByteArray())
            var off = 0
            while (off < raw.size) {
                val n = inf.inflate(raw, off, raw.size - off)
                if (n <= 0) break
                off += n
            }
            if (off != raw.size) error("IDAT解压不完整($off/${raw.size})")
        } finally {
            inf.end()
        }
        var prevRow = ByteArray(rowBytes)
        for (y in 0 until h) {
            val ft = raw[y * stride].toInt() and 0xFF
            val cur = ByteArray(rowBytes)
            val base = y * stride + 1
            for (i in 0 until rowBytes) {
                val v = raw[base + i].toInt() and 0xFF
                val left = if (i >= channels) cur[i - channels].toInt() and 0xFF else 0
                val up = prevRow[i].toInt() and 0xFF
                val upLeft = if (i >= channels) prevRow[i - channels].toInt() and 0xFF else 0
                val pred = when (ft) {
                    0 -> 0
                    1 -> left
                    2 -> up
                    3 -> (left + up) / 2
                    4 -> paeth(left, up, upLeft)
                    else -> error("非法滤波器类型$ft")
                }
                cur[i] = ((v + pred) and 0xFF).toByte()
            }
            for (x in 0 until w) {
                val a = when (ct) {
                    6 -> cur[x * 4 + 3].toInt() and 0xFF
                    4 -> cur[x * 2 + 1].toInt() and 0xFF
                    else -> 255
                }
                alpha[y * w + x] = a.toByte()
            }
            prevRow = cur
        }
    }

    /**
     * 身体质心 = 4-邻域最大连通域的质心。
     * 道具（饭碗/音符/星星）是独立连通块，不参与锚点判定——否则投喂帧的碗会把"身体"判成漂移。
     * 与 tools/pet_sprite/sprite_pipeline.py 的 largest_cc 同规则。
     */
    fun bodyCentroid(mask: BooleanArray): Pair<Double, Double>? {
        val w = width; val h = height
        val seen = BooleanArray(w * h)
        val queue = IntArray(w * h)
        var bestN = 0; var bestSx = 0L; var bestSy = 0L
        for (start in 0 until w * h) {
            if (!mask[start] || seen[start]) continue
            seen[start] = true
            var head = 0; var tail = 0
            queue[tail++] = start
            var n = 0; var sx = 0L; var sy = 0L
            while (head < tail) {
                val i = queue[head++]
                val x = i % w; val y = i / w
                n++; sx += x; sy += y
                if (x + 1 < w) { val j = i + 1; if (mask[j] && !seen[j]) { seen[j] = true; queue[tail++] = j } }
                if (x > 0) { val j = i - 1; if (mask[j] && !seen[j]) { seen[j] = true; queue[tail++] = j } }
                if (y + 1 < h) { val j = i + w; if (mask[j] && !seen[j]) { seen[j] = true; queue[tail++] = j } }
                if (y > 0) { val j = i - w; if (mask[j] && !seen[j]) { seen[j] = true; queue[tail++] = j } }
            }
            if (n > bestN) { bestN = n; bestSx = sx; bestSy = sy }
        }
        if (bestN == 0) return null
        return (bestSx.toDouble() / bestN) to (bestSy.toDouble() / bestN)
    }

    private fun readIntBE(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF shl 24) or (b[at + 1].toInt() and 0xFF shl 16) or
            (b[at + 2].toInt() and 0xFF shl 8) or (b[at + 3].toInt() and 0xFF)

    private fun paeth(a: Int, b: Int, c: Int): Int {
        val p = a + b - c
        val pa = abs(p - a); val pb = abs(p - b); val pc = abs(p - c)
        return if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
    }
}
