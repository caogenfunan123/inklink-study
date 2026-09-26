package com.inklink.controller.service

import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.inklink.common.utils.CoordinateConverter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import kotlin.concurrent.thread

/**
 * 腾讯地图 WebService 方向 API（驾车路线规划）封装，含 SK 签名校验与坐标转换。
 *
 * 签名规则（GET）：请求路径 + "?" + 按参数名升序的原始参数字符串 + SK，
 * 拼接后计算 MD5（小写），作为 sig 参数附带到最终请求。
 */
object RouteApi {

    private const val BASE = "https://apis.map.qq.com"
    private const val PATH = "/ws/direction/v1/driving/"

    /** 路线规划结果：坐标点串（lat,lng，已为 GCJ02）+ 距离 + 时长。 */
    data class RouteInfo(
        val points: List<Pair<Double, Double>>,
        val distanceMeters: Int,
        val durationSeconds: Int
    )

    /**
     * 异步驾车路线规划；[onDone] 在子线程回调，RouteInfo 为空表示失败并给出原因。
     * [fromWgs84] 若为 true，会将起点/终点从 WGS-84 转换为腾讯需要的 GCJ-02。
     */
    fun driving(
        key: String,
        sk: String,
        fromLat: Double,
        fromLng: Double,
        toLat: Double,
        toLng: Double,
        fromWgs84: Boolean = true,
        onDone: (RouteInfo?, String?) -> Unit
    ) {
        thread(name = "route-api", isDaemon = true) {
            runCatching {
                val (actualFromLat, actualFromLng) = if (fromWgs84) {
                    CoordinateConverter.wgs84ToGcj02(fromLat, fromLng)
                } else {
                    fromLat to fromLng
                }
                val (actualToLat, actualToLng) = if (fromWgs84) {
                    CoordinateConverter.wgs84ToGcj02(toLat, toLng)
                } else {
                    toLat to toLng
                }

                val params = sortedMapOf(
                    "from" to "$actualFromLat,$actualFromLng",
                    "key" to key,
                    "to" to "$actualToLat,$actualToLng"
                )
                val rawQuery = params.entries.joinToString("&") { "${it.key}=${it.value}" }
                val sig = md5("$PATH?$rawQuery$sk")
                val encoded = params.entries.joinToString("&") {
                    "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}"
                }
                val url = "$BASE$PATH?$encoded&sig=$sig"

                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000
                conn.requestMethod = "GET"
                val code = conn.responseCode
                val stream = if (code == HttpURLConnection.HTTP_OK) conn.inputStream else conn.errorStream
                val body = stream?.use { it.readBytes() }?.toString(Charsets.UTF_8) ?: ""
                conn.disconnect()
                if (code != HttpURLConnection.HTTP_OK) {
                    onDone(null, "HTTP $code: $body")
                    return@runCatching
                }

                val root = JsonParser.parseString(body).asJsonObject
                val status = root.get("status").asInt
                if (status != 0) {
                    val msg = root.get("message")?.asString ?: "状态码=$status"
                    onDone(null, "路线规划失败 [$status]: $msg")
                    return@runCatching
                }
                val route = root.getAsJsonObject("result")
                    .getAsJsonArray("routes").get(0).asJsonObject
                val distance = route.get("distance").asInt
                val duration = route.get("duration").asInt
                onDone(RouteInfo(parsePolyline(route.get("polyline")), distance, duration), null)
            }.onFailure { e ->
                onDone(null, e.message ?: e.toString())
            }
        }
    }

    /**
     * 解析方向 API 的 polyline。
     *
     * 数组格式为腾讯压缩坐标：首两个元素是第一个点的绝对 lat/lng，之后每两个元素为
     * 相对前一点的偏移量（单位 1e-5 度，需除以 100000 累加还原）。
     */
    private fun parsePolyline(node: JsonElement): List<Pair<Double, Double>> {
        val result = mutableListOf<Pair<Double, Double>>()
        when {
            node.isJsonArray -> {
                val arr = node.asJsonArray
                if (arr.size() < 2) return result
                var lat = arr.get(0).asDouble
                var lng = arr.get(1).asDouble
                result.add(lat to lng)
                var i = 2
                while (i + 1 < arr.size()) {
                    lat += arr.get(i).asDouble / 100_000.0
                    lng += arr.get(i + 1).asDouble / 100_000.0
                    result.add(lat to lng)
                    i += 2
                }
            }
            node.isJsonPrimitive && node.asJsonPrimitive.isString -> {
                node.asString.split(";").forEach { pair ->
                    val parts = pair.split(",")
                    if (parts.size >= 2) {
                        result.add(parts[0].trim().toDouble() to parts[1].trim().toDouble())
                    }
                }
            }
        }
        return result
    }

    private fun md5(input: String): String {
        val digest = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }
}
