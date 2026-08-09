package org.symera.mediasource.es.rojadirecta

import org.symera.source.iptv.IptvChannel

private const val BASE_URL = "https://rojadirecta.st"
private const val GROUP_ID = "deportes"

internal object RojaDirectaCatalog {
    private data class Entry(val slug: String, val name: String)

    private val entries = listOf(
        Entry("dsports-en-vivo", "DSPORTS (DirecTV Sports)"),
        Entry("dsports-2-en-vivo", "DSPORTS 2 (DirecTV Sports 2)"),
        Entry("dsports-plus-en-vivo", "DSPORTS + (DirecTV Sports +)"),
        Entry("espn-en-vivo", "ESPN"),
        Entry("espn-2-en-vivo", "ESPN 2"),
        Entry("espn-3-en-vivo", "ESPN 3"),
        Entry("espn-4-en-vivo", "ESPN 4"),
        Entry("espn-5-en-vivo", "ESPN 5"),
        Entry("tnt-sports-en-vivo", "TNT Sports"),
        Entry("tyc-sports-en-vivo", "TyC Sports"),
        Entry("win-sports-en-vivo", "Win Sports"),
        Entry("directv-go-en-vivo", "DIRECTV GO"),
        Entry("tyc-play-en-vivo", "TyC Play"),
        Entry("star-plus-en-vivo", "Star Plus"),
        Entry("tigo-sports-en-vivo", "Tigo Sports"),
        Entry("estadio-tnt-sports-en-vivo", "Estadio TNT Sports"),
        Entry("caracol-play-en-vivo", "Caracol Play"),
    )

    val channels: List<IptvChannel> = entries.map { entry ->
        val pageUrl = "$BASE_URL/canales/${entry.slug}/"
        IptvChannel(
            id = entry.slug,
            name = entry.name,
            groupIds = listOf(GROUP_ID),
            attributes = mapOf("pageUrl" to pageUrl),
        )
    }
}
