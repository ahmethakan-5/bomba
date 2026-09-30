package com.keyiflerolsun

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import com.cizgimax.CizgiMax

@CloudstreamPlugin
class CizgiMaxPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(CizgiMax())
        registerExtractorAPI(SibNet())
        registerExtractorAPI(CizgiDuo())
        registerExtractorAPI(CizgiPass())
        registerExtractorAPI(Drive())
    }
}
