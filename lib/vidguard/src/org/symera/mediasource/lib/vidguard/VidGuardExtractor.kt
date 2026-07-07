package org.symera.mediasource.lib.vidguard

import android.util.Base64
import android.util.Log
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import org.mozilla.javascript.Context
import org.mozilla.javascript.NativeJSON
import org.mozilla.javascript.NativeObject
import org.mozilla.javascript.Scriptable
import org.symera.mediasource.core.parseAs
import org.symera.mediasource.core.useAsJsoup
import org.symera.mediasource.lib.playlistutils.PlaylistUtils
import org.symera.source.model.SStream
import org.symera.source.online.GET

class VidGuardExtractor(private val client: OkHttpClient) {
    private val playlistUtils by lazy { PlaylistUtils(client) }

    fun streamsFromUrl(url: String, prefix: String) = streamsFromUrl(url) { "${prefix}VidGuard:$it" }

    fun streamsFromUrl(url: String, videoNameGen: (String) -> String = { quality -> "VidGuard:$quality" }): List<SStream> = try {
        val res = client.newCall(GET(url)).execute().useAsJsoup()
        val scriptData = res.selectFirst("script:containsData(eval)")?.data() ?: return emptyList()
        val jsonStr = runJS(scriptData).parseAs<SvgObject>()
        playlistUtils.extractFromHls(sigDecode(jsonStr.stream), videoNameGen = videoNameGen)
    } catch (e: Exception) {
        Log.e("VidGuardExtractor", "Error extracting videos: ${e.message}", e)
        emptyList()
    }

    fun videosFromUrl(url: String, prefix: String): List<SStream> = streamsFromUrl(url, prefix)

    fun videosFromUrl(url: String, videoNameGen: (String) -> String = { quality -> "VidGuard:$quality" }): List<SStream> = streamsFromUrl(url, videoNameGen)

    private fun sigDecode(url: String): String {
        val sig = url.split("sig=")[1].split("&")[0]
        val t = sig.chunked(2)
            .joinToString("") { (Integer.parseInt(it, 16) xor 2).toChar().toString() }
            .let {
                val padding = when (it.length % 4) {
                    2 -> "=="
                    3 -> "="
                    else -> ""
                }
                String(Base64.decode((it + padding).toByteArray(Charsets.UTF_8), Base64.DEFAULT))
            }
            .dropLast(5)
            .reversed()
            .toCharArray()
            .apply {
                for (i in indices step 2) if (i + 1 < size) this[i] = this[i + 1].also { this[i + 1] = this[i] }
            }
            .concatToString()
            .dropLast(5)
        return url.replace(sig, t)
    }

    private fun runJS(script: String): String {
        var result = ""
        val t = Thread(ThreadGroup("A"), {
            val rhino = Context.enter()
            rhino.optimizationLevel = -1
            val scope: Scriptable = rhino.initSafeStandardObjects()
            scope.put("window", scope, scope)
            try {
                rhino.evaluateString(scope, script, "JavaScript", 1, null)
                val svgObject = scope.get("svg", scope)
                result = if (svgObject is NativeObject) {
                    NativeJSON.stringify(Context.getCurrentContext(), scope, svgObject, null, null).toString()
                } else {
                    Context.toString(svgObject)
                }
            } catch (e: Exception) {
                Log.e("VidGuardExtractor", "JavaScript execution error: ${e.message}")
            } finally {
                Context.exit()
            }
        }, "thread_rhino", 8 * 1024 * 1024)
        t.start()
        t.join()
        t.interrupt()
        return result
    }
}

@Serializable
data class SvgObject(val stream: String, val hash: String)
