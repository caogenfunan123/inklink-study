package com.inklink.common.transport

import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * 仅自用测试环境：信任所有证书。
 *
 * 生产环境默认不使用，应通过正规 CA（Let's Encrypt）证书建立 WSS 连接。
 * 关闭证书校验存在中间人攻击风险，禁止对外分发时启用。
 */
internal object SslTrustAll {

    fun createSocketFactory(): SSLSocketFactory {
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
        return context.socketFactory
    }
}
