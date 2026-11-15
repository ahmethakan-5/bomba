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

        val response = app.get(url, referer = extRef)
        val html     = response.text
        Log.d("Kekik_${this.name}", "http=${response.code} len=${html.length}")

        // Alt yazılar
        response.document.select("track").forEach {
            val srcAttr = it.attr("src")
            if (srcAttr.isNotBlank()) {
                subtitleCallback.invoke(
                    SubtitleFile(
                        lang = it.attr("label").ifBlank { "Turkish" },
                        url  = fixUrl(srcAttr)
                    )
                )
            }
        }

        // Base64 kodlu m3u8 adresi. Hem standart hem URL-safe alfabe denenir.
        val candidates = Regex("""aHR0[0-9a-zA-Z+/=_-]{20,}""").findAll(html).map { it.value }.toList()
        Log.d("Kekik_${this.name}", "base64 adayı sayısı » ${candidates.size}")

        for (raw in candidates) {
            val decoded = decodeBase64Url(raw) ?: continue
            if (!decoded.startsWith("http")) continue
            Log.d("Kekik_${this.name}", "m3u8 » $decoded")

            callback.invoke(
                newExtractorLink(name, name, decoded, ExtractorLinkType.M3U8) {
                    this.referer = mainUrl
                    this.quality = Qualities.Unknown.value
                }
            )
            return
        }

        Log.w("Kekik_${this.name}", "m3u8 bulunamadı, sayfa örneği: ${html.take(300)}")
        throw ErrorLoadingException("m3u adresi bulunamadı")
    }

    private fun decodeBase64Url(input: String): String? {
        return try {
            val normalized = input.replace('-', '+').replace('_', '/').trimEnd('=')
            val padded     = normalized + "=".repeat((4 - normalized.length % 4) % 4)
            String(Base64.decode(padded, Base64.DEFAULT), Charsets.UTF_8)
                .trim()
                .takeIf { it.startsWith("http") }
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}
