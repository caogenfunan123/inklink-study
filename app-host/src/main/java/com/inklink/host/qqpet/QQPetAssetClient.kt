package com.inklink.host.qqpet

import android.content.Context
import android.util.Base64
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap

/**
 * 游戏厅虚拟 origin：页面与素材都从 APK assets Serve 在这里。
 * Ruffle 要加载 SWF/config/wasm，file:// 不允许这些请求，故用 https 虚拟源整链拦截。
 */
const val QQPET_ORIGIN = "https://appassets.androidplatform.net"

/**
 * QQPet 游戏厅的 WebView 资源客户端（移植自 third_party/qqpet/android 的 AssetClient.kt）。
 *
 * 页面与素材统一从 APK assets Serve 在虚拟 origin [QQPET_ORIGIN] 下：
 * Ruffle 要加载 SWF/config/wasm，file:// 不允许这些请求，故用 https 虚拟源整链拦截。
 * assets 目录由 app-host build.gradle.kts 的 sourceSets 注入：
 * - third_party/qqpet/out/android（vite 产物：index/game/swf.html + ruffle/，CI 构建）
 * - third_party/qqpet/resources（上游素材：pet/、icons/，LFS）
 *
 * [hd] 决定带 @2x.png 孪生图的图片是否以 SVG 内嵌形式 Serve（高清档），
 * 与上游 src/main/hidpi.ts 同语义；页面开始加载时读取一次。
 */
open class QQPetAssetClient(
    private val context: Context,
    private val hd: () -> Boolean = { false }
) : WebViewClient() {
    private var hdOn = false

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val url = request.url
        if ("${url.scheme}://${url.host}" != QQPET_ORIGIN) return null
        // SWF configs write paths with backslashes (pet\\fishing\\…), which URLs turn into empty segments; assets.open wants none.
        val path = petsocPath(url.path!!.split('/').filter { it.isNotEmpty() }.joinToString("/"))
        if (path == "index.html") return indexHtml()
        if (hdOn && path.startsWith("pet/") && request.requestHeaders["Accept"]?.contains("image/svg+xml") == true) hidpiSvg(path)?.let { return it }
        return try {
            response(path.substringAfterLast('.').lowercase(), context.assets.open(path))
        } catch (_: FileNotFoundException) {
            WebResourceResponse(null, null, 404, "Not Found", null, null)
        }
    }

    /** Asset folder listings; requests arrive on several threads. */
    private val listings = ConcurrentHashMap<String, Array<String>>()

    /**
     * PetSoc is a Windows dump: its SWFs ask for `data/moduleconfig.xml` while the asset is
     * `Data/moduleconfig.xml`. Maps a path under pet/petsoc/ to the asset's casing.
     */
    private fun petsocPath(path: String): String {
        if (!path.startsWith("pet/petsoc/")) return path
        var dir = ""
        for (part in path.split('/')) {
            val names = listings.getOrPut(dir) { context.assets.list(dir) ?: emptyArray() }
            val hit = if (part in names) part else names.find { it.equals(part, ignoreCase = true) } ?: return path
            dir = if (dir.isEmpty()) hit else "$dir/$hit"
        }
        return dir
    }

    /** The pet page, scaled so the screen's short side is [VIEWPORT] CSS pixels. */
    private fun indexHtml(): WebResourceResponse {
        hdOn = hd()
        val m = context.resources.displayMetrics
        val scale = minOf(m.widthPixels, m.heightPixels) / m.density / VIEWPORT
        val meta = """<meta name="viewport" content="width=device-width, initial-scale=$scale, minimum-scale=$scale, maximum-scale=$scale, user-scalable=no">"""
        val html = context.assets.open("index.html").bufferedReader().readText().replaceFirst("<head>", "<head>$meta")
        return response("html", ByteArrayInputStream(html.toByteArray()))
    }

    private fun hidpiSvg(path: String): WebResourceResponse? {
        val hi = path.replace(Regex("\\.(png|gif|bmp|jpg)$", RegexOption.IGNORE_CASE), "@2x.png")
        if (hi == path) return null
        val png = try {
            context.assets.open(hi).readBytes()
        } catch (_: FileNotFoundException) {
            return null
        }
        val w = ByteBuffer.wrap(png, 16, 4).int / 2
        val h = ByteBuffer.wrap(png, 20, 4).int / 2
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" width="$w" height="$h" viewBox="0 0 $w $h" preserveAspectRatio="none">""" +
            """<image href="data:image/png;base64,${Base64.encodeToString(png, Base64.NO_WRAP)}" width="$w" height="$h" preserveAspectRatio="none"/></svg>"""
        return response("svg", ByteArrayInputStream(svg.toByteArray()))
    }

    private fun response(ext: String, data: java.io.InputStream): WebResourceResponse {
        val type = TYPES[ext] ?: "application/octet-stream"
        val text = type.startsWith("text/") || type == "application/json" || type == "image/svg+xml"
        return WebResourceResponse(type, if (text) "utf-8" else null, data)
    }

    private companion object {
        /** CSS pixels across the screen's short side; wider panels (the shop is 800) scale down to fit. */
        const val VIEWPORT = 480

        val TYPES = mapOf(
            "html" to "text/html", "js" to "text/javascript", "css" to "text/css", "json" to "application/json",
            "xml" to "text/xml", "txt" to "text/plain", "wasm" to "application/wasm", "swf" to "application/x-shockwave-flash",
            "png" to "image/png", "gif" to "image/gif", "jpg" to "image/jpeg", "bmp" to "image/bmp", "svg" to "image/svg+xml",
            "mp3" to "audio/mpeg",
        )
    }
}
