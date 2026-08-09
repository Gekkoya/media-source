package org.symera.mediasource.es.rojadirecta

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CapoPlaybackResolverTest {
    private val resolver = CapoPlaybackResolver(OkHttpClient(), "test-agent")

    @Test
    fun extractsSignedHlsUrlFromCapoJavascript() {
        val html = """
            <span style='display:none' id='token'> </span>
            <script>
                function build() {
                    return(["h","t","t","p","s",":","\/","\/","cdn.example","/hls/live.m3u8?md5=abc&expires=1786294635"].join("") + document.getElementById("token").innerHTML);
                }
            </script>
        """.trimIndent()

        val result = resolver.extractStreamUrl(html)

        assertEquals("https://cdn.example/hls/live.m3u8?md5=abc&expires=1786294635", result)
    }

    @Test
    fun decodesMustaveWhenJavascriptUrlIsUnavailable() {
        val html = "<script>var mustave = atob('L2hscy9zdHJlYW0ubTN1OD9jaD1lc3Bu');</script>"

        val result = resolver.extractStreamUrl(html, "capo8play.com")

        assertEquals("https://capo8play.com/hls/stream.m3u8?ch=espn", result)
    }

    @Test
    fun returnsNullForPlayerWithoutStream() {
        val result = resolver.extractStreamUrl("<html><body>offline</body></html>")

        assertNull(result)
    }
}
