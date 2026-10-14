// use an integer for version numbers
version = 1

cloudstream {
    language = "tr"
    description = "NOW TV dizi ve programları"
    authors = listOf("ahmethakan-5")

    /**
     * Status int as one of the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta-only
     **/
    status = 1 // will be 3 if unspecified
    tvTypes = listOf("TvSeries")
    iconUrl = "https://www.google.com/s2/favicons?domain=nowtv.com.tr&sz=%size%"
}
