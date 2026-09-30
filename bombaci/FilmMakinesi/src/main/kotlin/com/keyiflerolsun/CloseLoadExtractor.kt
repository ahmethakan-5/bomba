// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.keyiflerolsun

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

open class CloseLoad : ExtractorApi() {
    override val name            = "CloseLoad"
    override val mainUrl         = "https://closeload.filmmakinesi.to"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String, 
        referer: String?, 
        subtitleCallback: (SubtitleFile) -> Unit, 
        callback: (ExtractorLink) -> Unit
    ) {
        val extRef = referer ?: ""
        Log.d("Kekik_${this.name}", "url » $url")

        val iSource = app.get(url, referer = extRef)

        // Alt yazıları çekme
        iSource.document.select("track").forEach {
            val label   = it.attr("label")
            val srcAttr = it.attr("src")
            if (srcAttr.isNotBlank()) {
                val src = fixUrl(srcAttr)
                subtitleCallback.invoke(
                    SubtitleFile(
                        lang = label.ifBlank { "Turkish" },
                        url  = src
                    )
                )
            }
        }

        // Base64 kodlu m3u8 bağlantısını bulma ve çözme
        val base64Match = Regex("""aHR0[0-9a-zA-Z+/=]*""").find(iSource.text)?.value
            ?: throw ErrorLoadingException("m3u adresi bulunamadı")

        val padding = "=".repeat((4 - base64Match.length % 4) % 4)
        val m3uLink = String(Base64.decode(base64Match + padding, Base64.DEFAULT), Charsets.UTF_8)
        
        Log.d("Kekik_${this.name}", "m3uLink » $m3uLink")

        callback.invoke(
            ExtractorLink(
                source  = this.name,
                name    = this.name,
                url     = m3uLink,
                referer = mainUrl,
                quality = Qualities.Unknown.value,
                isM3u8  = true
            )
        )
    }
}
