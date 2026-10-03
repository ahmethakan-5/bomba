// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.extractors.helper.AesHelper

open class CizgiDuo : ExtractorApi() {
    override var name            = "CizgiDuo"
    override var mainUrl         = "https://cizgiduo.online"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val extRef  = referer ?: ""
        val iSource = app.get(url, referer = extRef).text

        val bePlayer = Regex("""bePlayer\('([^']+)',\s*'(\{[^}]+\})'\);""").find(iSource)?.groupValues
            ?: throw ErrorLoadingException("bePlayer fonksiyonu bulunamadı ($url)")

        val bePlayerPass = bePlayer[1]
        val bePlayerData = bePlayer[2]

        val encrypted = AesHelper.cryptoAESHandler(bePlayerData, bePlayerPass.toByteArray(), false)?.replace("\\", "")
            ?: throw ErrorLoadingException("AES verisi çözülemedi")

        Log.d("Kekik_${this.name}", "Çözülen Veri » $encrypted")

        val m3uLink = Regex("""video_location":"([^"]+)""").find(encrypted)?.groupValues?.get(1)
            ?: throw ErrorLoadingException("m3u8 bağlantısı bulunamadı")

        callback.invoke(
            ExtractorLink(
                source  = this.name,
                name    = this.name,
                url     = m3uLink,
                referer = url,
                quality = Qualities.Unknown.value,
                isM3u8  = true
            )
        )
    }
}
