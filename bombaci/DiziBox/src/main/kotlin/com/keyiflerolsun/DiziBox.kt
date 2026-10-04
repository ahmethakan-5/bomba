package com.keyiflerolsun

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import okhttp3.Interceptor

class DiziBox : MainAPI() { // MainUrlPlugin yerine MainAPI() olmalı
    override var mainUrl = "https://www.dizibox.tv"
    override var name = "DiziBox"
    override var hasMainPage = true
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.TvSeries)

    private val cloudflareKiller: CloudflareKiller? by lazy {
        try {
            CloudflareKiller()
        } catch (e: Throwable) {
            null
        }
    }
    
    var interceptor: Interceptor? = Interceptor { chain ->
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

    // ... getMainPage, load, search ve extractor fonksiyonlarınız ...
}
