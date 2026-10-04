package com.keyiflerolsun

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import okhttp3.Interceptor
import okhttp3.Response

class DiziBox : MainUrlPlugin() {
    override var mainUrl = "https://www.dizibox.tv"
    override var name = "DiziBox"
    override var hasMainPage = true
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.TvSeries)

    // 1. DÜZELTME: WebView/Chrome eksik olan cihazlarda (TV/Emülatör) çökmesini engellemek için
    // CloudflareKiller'ı try-catch ile güvenli başlatıyoruz.
    private val cloudflareKiller: CloudflareKiller? by lazy {
        try {
            CloudflareKiller()
        } catch (e: Throwable) {
            null // Cihazda WebView yoksa çökmek yerine güvenli şekilde pas geçer
        }
    }

    override var interceptor: Interceptor? = Interceptor { chain ->
        val killer = cloudflareKiller
        if (killer != null) {
            try {
                killer.intercept(chain)
            } catch (e: Throwable) {
                chain.proceed(chain.request())
            }
        } else {
            chain.proceed(chain.request())
        }
    }

    // ... getMainPage, search, load fonksiyonlarınız aynı kalabilir ...

    // 2. DÜZELTME: 220. satırdaki m3u8 link çıkarma kısmı (generateManifestUrl yerine generateM3u8)
    private suspend fun invokeIframe(
        vidUrl: String,
        callback: (ExtractorLink) -> Unit
    ) {
        if (vidUrl.contains(".m3u8")) {
            // Unresolved reference: generateManifestUrl hatası düzeltildi
            M3u8Helper.generateM3u8(
                source    = this.name,
                streamUrl = vidUrl,
                referer   = "$mainUrl/"
            ).forEach(callback)
        } else {
            callback.invoke(
                ExtractorLink(
                    source  = this.name,
                    name    = this.name,
                    url     = vidUrl,
                    referer = "$mainUrl/",
                    quality = Qualities.Unknown.value
                )
            )
        }
    }
}
