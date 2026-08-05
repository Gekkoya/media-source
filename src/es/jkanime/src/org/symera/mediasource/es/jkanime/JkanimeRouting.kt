package org.symera.mediasource.es.jkanime

import java.net.URI

internal object JkanimeRouting {
    fun match(url: String, fallback: String): String {
        val parsed = runCatching { URI(url.lowercase()) }.getOrNull() ?: return fallback
        val host = parsed.host ?: return fallback
        val path = parsed.path.trimEnd('/')
        return serverMatching.firstOrNull { (_, names) ->
            names.any { aliasMatches(it, host, path) }
        }?.first ?: fallback
    }

    private fun aliasMatches(alias: String, host: String, path: String): Boolean {
        val normalized = alias.trim('.', '/').lowercase()
        if ('/' in normalized || normalized.endsWith(".php")) {
            val aliasPath = "/$normalized"
            return path == aliasPath || path.endsWith(aliasPath)
        }
        return host == normalized || host.endsWith(".$normalized")
    }

    private val serverMatching = listOf(
        "voe" to listOf("voe", "tubelessceliolymph", "simpulumlamerop", "urochsunloath", "nathanfromsubject", "yip.", "metagnathtuggers", "donaldlineelse"),
        "okru" to listOf("ok.ru", "okru"),
        "filemoon" to listOf("filemoon", "moonplayer", "moviesm4u", "files.im"),
        "streamtape" to listOf("streamtape", "stp", "stape", "shavetape"),
        "mixdrop" to listOf("mixdrop", "mxdrop", "mdbekjwqa"),
        "streamwish" to listOf("sfastwish", "wishembed", "streamwish", "strwish", "wish", "kswplayer", "swhoi", "multimovies", "uqloads", "neko-stream", "swdyu", "iplayerhls", "streamgg"),
        "doostream" to listOf("d-s.io", "dsvplay"),
        "desuka" to listOf("stream/jkmedia"),
        "nozomi" to listOf("jkplayer/um2", "um2.php", "nozomi"),
        "desu" to listOf("jkplayer/um", "um.php"),
        "magi" to listOf("jkplayer/umv"),
        "byse" to listOf("bysekoze.com", "bysefujedu.com"),
        "mega" to listOf("mega.nz"),
    )
}
