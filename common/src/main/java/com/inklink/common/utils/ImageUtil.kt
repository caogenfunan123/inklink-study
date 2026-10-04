package com.inklink.common.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream

/**
 * 图片处理工具：降采样压缩与 Base64 编解码。
 *
 * 投屏图片走文本帧（payload 为 Base64 的降采样 JPEG）。发送端先按 [MAX_DIMENSION]
 * 目标框降采样（inSampleSize 粗降 + 精确缩放），再循环降 JPEG 质量（70 → 30，步长 10），
 * 仍超 [MAX_BASE64_LENGTH] 时逐级把宽度减半（下限 [MIN_DIMENSION]）重压；
 * 缩到下限仍放不进时返回 null（由调用方提示用户），不会产出必然被 64KB 上限丢弃的脏数据。
 */
object ImageUtil {

    private const val JPEG_QUALITY = 70
    private const val MIN_JPEG_QUALITY = 30
    private const val MAX_DIMENSION = 640
    private const val MIN_DIMENSION = 320
    private const val MAX_BASE64_LENGTH = 60_000

    /** 从 Uri 读取并降采样为 JPEG Base64 字符串。 */
    fun compressToBase64(context: Context, uri: Uri, maxWidth: Int = MAX_DIMENSION, maxHeight: Int = MAX_DIMENSION): String? {
        val bitmap = loadSampledBitmap(context, uri, maxWidth, maxHeight) ?: return null
        return try {
            encodeWithinLimit(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    /** Base64 解码为 Bitmap，供受控端投屏显示。 */
    fun decodeBase64(base64: String): Bitmap? {
        return runCatching {
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()
    }

    /** 循环降质直到 Base64 长度达标；最低质量仍超限时逐级缩小尺寸，缩到下限仍超限返回 null。 */
    private fun encodeWithinLimit(source: Bitmap): String? {
        var bitmap = source
        var quality = JPEG_QUALITY
        while (true) {
            val encoded = encodeJpegBase64(bitmap, quality)
            if (encoded.length <= MAX_BASE64_LENGTH) return encoded
            if (quality > MIN_JPEG_QUALITY) {
                quality -= 10
                continue
            }
            val nextWidth = bitmap.width / 2
            if (nextWidth < MIN_DIMENSION) return null
            val nextHeight = nextWidth * bitmap.height / bitmap.width
            val scaled = Bitmap.createScaledBitmap(bitmap, nextWidth, nextHeight, true)
            if (scaled !== bitmap && bitmap !== source) bitmap.recycle()
            bitmap = scaled
            quality = JPEG_QUALITY
        }
    }

    private fun encodeJpegBase64(bitmap: Bitmap, quality: Int): String {
        val baos = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, baos)
        return Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP)
    }

    private fun loadSampledBitmap(context: Context, uri: Uri, maxWidth: Int, maxHeight: Int): Bitmap? {
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }

            var sampleSize = 1
            while (bounds.outWidth / sampleSize > maxWidth * 2 || bounds.outHeight / sampleSize > maxHeight * 2) {
                sampleSize *= 2
            }

            val opts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val sampled = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                ?: return@runCatching null

            if (sampled.width > maxWidth || sampled.height > maxHeight) {
                val ratio = minOf(maxWidth.toFloat() / sampled.width, maxHeight.toFloat() / sampled.height)
                val targetW = (sampled.width * ratio).toInt().coerceAtLeast(1)
                val targetH = (sampled.height * ratio).toInt().coerceAtLeast(1)
                val scaled = Bitmap.createScaledBitmap(sampled, targetW, targetH, true)
                if (scaled !== sampled) sampled.recycle()
                scaled
            } else {
                sampled
            }
        }.getOrNull()
    }
}
