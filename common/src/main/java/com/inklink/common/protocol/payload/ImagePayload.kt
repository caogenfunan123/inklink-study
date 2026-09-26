package com.inklink.common.protocol.payload

/**
 * 投屏图片载荷（IMAGE, type=2）。
 *
 * 局域网直连使用 base64；公网中转模式（Ably）建议使用 url 避免超限。
 */
data class ImagePayload(
    val mode: String = MODE_BASE64,
    val data: String? = null,
    val url: String? = null
) {
    companion object {
        const val MODE_BASE64 = "base64"
        const val MODE_URL = "url"
    }
}
