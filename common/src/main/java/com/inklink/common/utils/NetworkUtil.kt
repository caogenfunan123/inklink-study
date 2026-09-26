package com.inklink.common.utils

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * 网络工具。获取本机局域网 IPv4 地址（无需额外权限）。
 */
object NetworkUtil {

    /**
     * 遍历网卡获取本机局域网 IPv4 地址（无需额外权限）。
     *
     * 优先返回 WiFi（wlan*）网卡的站点本地地址，其次以太网（eth*），
     * 排除蜂窝数据网卡（rmnet、ccmni、pdp、cellular、wwan），避免 4G 内网 IP 被当作局域网地址。
     */
    fun getLocalIpv4Address(): String? {
        return runCatching {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return@runCatching null
            var wifi: String? = null
            var ethernet: String? = null
            var fallback: String? = null
            while (interfaces.hasMoreElements()) {
                val ni = interfaces.nextElement()
                if (!ni.isUp || ni.isLoopback) continue
                val name = ni.name.lowercase()
                val isCellular = name.startsWith("rmnet") || name.startsWith("ccmni") ||
                    name.startsWith("pdp") || name.startsWith("cellular") ||
                    name.startsWith("wwan")
                if (isCellular) continue
                val addrs = ni.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr !is Inet4Address || addr.isLoopbackAddress) continue
                    val ip = addr.hostAddress ?: continue
                    if (!addr.isSiteLocalAddress) continue
                    when {
                        name.startsWith("wlan") -> if (wifi == null) wifi = ip
                        name.startsWith("eth") -> if (ethernet == null) ethernet = ip
                        else -> if (fallback == null) fallback = ip
                    }
                }
            }
            wifi ?: ethernet ?: fallback
        }.getOrNull()
    }
}
