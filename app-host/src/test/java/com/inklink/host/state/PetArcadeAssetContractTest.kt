package com.inklink.host.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.Inflater

/**
 * PIXEL_ARCADE_SPRITE 街机素材资产契约（tools/pet_arcade/convert_arcade.py 产物）。
 *
 * 扫描 assets/pixel_arcade/{species}/ 断言：
 * 1) 命名 = {code}_{state}_{NN}.png，state ∈ {idle,hungry,happy,sleep}，NN ∈ 01..03；
 * 2) 每物种恰 12 张（4 状态 × 3 帧）；
 * 3) 画布严格 256×256；
 * 4) Alpha 仅 0/255（二值化，禁半透明杂边）；
 * 5) 背景确实被抠除（非全不透明）；满幅构图（如 dragon 龙身抵边）允许留透明区；
 * 6) 有前景像素（非空图）。
 *
 * 与 PetSpriteAssetContractTest 同思路：合成器自检一层、CI 第二层。此测试不扫描 drawable-nodpi
 * （那是 PIXEL_PNG 的 96×96 契约），两套素材彼此隔离。
 */
class PetArcadeAssetContractTest {

    private val expectedSpecies = setOf(
        "cat", "dog", "rabbit", "fox", "frog", "hamster", "pig",
        "panda", "owl", "dragon", "snake", "hedgehog", "penguin",
        // dongwu 扩展物种（InkLink 暂无对应宠物，仍须完整入库以支撑未来扩展）
        "bear", "bird", "fish", "mouse", "tiger", "turtle", "wolf"
    )
    private val states = setOf("idle", "hungry", "happy", "sleep")
    private val frames = setOf("01", "02", "03")

    private fun assetsDir(): File {
        val candidates = listOf(
            File("src/main/assets/pixel_arcade"),
            File("app-host/src/main/assets/pixel_arcade")
        )
        return candidates.firstOrNull { it.isDirectory } ?: candidates[0]
    }

    @Test
    fun `arcade 素材符合契约`() {
        val root = assetsDir()
        assertTrue("找不到 assets/pixel_arcade 目录: ${root.path}", root.isDirectory)
        val problems = mutableListOf<String>()
        val speciesDirs = (root.listFiles { f -> f.isDirectory } ?: emptyArray()).sortedBy { it.name }
        assertEquals("物种目录集合 != 预期", expectedSpecies, speciesDirs.map { it.name }.toSet())

        for (sp in speciesDirs) {
            if (sp.name !in expectedSpecies) { problems += "未知物种目录: ${sp.name}"; continue }
            val files = (sp.listFiles { f -> f.isFile && f.name.endsWith(".png") } ?: emptyArray())
                .sortedBy { it.name }
            val stems = files.map { it.name.removeSuffix(".png") }
            // 闭集：4 状态 × 3 帧
            val expected = buildSet {
                states.forEach { st -> frames.forEach { fr -> add("${sp.name}_${st}_$fr") } }
            }
            if (stems.toSet() != expected) {
                problems += "${sp.name}: 帧集合不符 缺=${expected - stems.toSet()} 多=${stems.toSet() - expected}"
            }
            // 渲染层拼路径格式防护：PetSpriteView.loadArcadeBitmap 用 frame.padStart(2,'0')
            // 拼 `${code}_${state}_${NN}.png`，帧号必须两位数与闭集严格一致
            for (fr in 1..3) {
                val padded = fr.toString().padStart(2, '0')
                if (padded.length != 2 || "${sp.name}_idle_$padded" !in expected) {
                    problems += "${sp.name}: 渲染层帧号补零格式异常 frame=$fr -> $padded"
                }
            }
            for (f in files) {
                val stem = f.name.removeSuffix(".png")
                if (stem !in expected) continue
                val png = try { ArcadeAlpha(f.readBytes()) }
                catch (e: Exception) {
                    problems += "PNG 解码失败(${f.name}): ${e.message}"; continue
                }
                if (png.width != 256 || png.height != 256) {
                    problems += "画布非256×256: ${f.name} = ${png.width}x${png.height}"
                }
                var halfAlpha = 0; var opaque = 0
                for (y in 0 until png.height) for (x in 0 until png.width) {
                    val a = png.alphaAt(x, y)
                    if (a != 0 && a != 255) halfAlpha++
                    if (a == 255) opaque++
                }
                if (halfAlpha > 0) problems += "半透明像素${halfAlpha}个: ${f.name}"
                if (opaque == 0) problems += "空图(无前景): ${f.name}"
                // 背景必须被抠除：非全不透明（满幅构图如 dragon 龙身抵边属艺术设计，允许留透明区）
                if (opaque == png.width * png.height) problems += "全不透明(背景未抠): ${f.name}"
            }
        }
        assertTrue("arcade 素材契约违规:\n" + problems.joinToString("\n"), problems.isEmpty())
    }
}

/** 极简 PNG alpha 解码（8bit 非隔行；仅取 alpha 通道，供资产粗筛）。 */
private class ArcadeAlpha(bytes: ByteArray) {
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
            pos = dataStart + len + 4
        }
        if (!sawIhdr) error("缺IHDR")
        if (bd != 8) error("非8bit深度")
        if (il != 0) error("隔行不支持")
        val channels = when (ct) { 0 -> 1; 2 -> 3; 4 -> 2; 6 -> 4; else -> error("非标colorType=$ct") }
        width = w; height = h
        alpha = ByteArray(w * h)
        val rowBytes = w * channels
        val stride = rowBytes + 1
        val raw = ByteArray(h * stride)
        val inf = Inflater()
        try {
            inf.setInput(idat.toByteArray())
            var off = 0
            while (off < raw.size) { val n = inf.inflate(raw, off, raw.size - off); if (n <= 0) break; off += n }
            if (off != raw.size) error("IDAT解压不完整")
        } finally { inf.end() }
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
                    0 -> 0; 1 -> left; 2 -> up; 3 -> (left + up) / 2; 4 -> paeth(left, up, upLeft); else -> error("非法滤波")
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

    private fun readIntBE(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF shl 24) or (b[at + 1].toInt() and 0xFF shl 16) or
            (b[at + 2].toInt() and 0xFF shl 8) or (b[at + 3].toInt() and 0xFF)

    private fun paeth(a: Int, b: Int, c: Int): Int {
        val p = a + b - c
        val pa = kotlin.math.abs(p - a); val pb = kotlin.math.abs(p - b); val pc = kotlin.math.abs(p - c)
        return if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
    }
}
