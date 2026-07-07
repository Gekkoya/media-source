package org.symera.mediasource.lib.streamsilk

import java.util.regex.Pattern
import kotlin.math.pow

class JsHunter(private val hunterJS: String) {
    fun detect(): Boolean = Pattern.compile("eval\\(function\\(h,u,n,t,e,r\\)").matcher(hunterJS).find()

    fun dehunt(): String? = try {
        val matcher = Pattern.compile("""\}\("([^"]+)",[^,]+,\s*"([^"]+)",\s*(\d+),\s*(\d+)""", Pattern.DOTALL).matcher(hunterJS)
        if (matcher.find() && matcher.groupCount() == 4) {
            hunter(matcher.group(1).orEmpty(), matcher.group(2).orEmpty(), matcher.group(3)?.toInt() ?: 0, matcher.group(4)?.toInt() ?: 0)
        } else {
            null
        }
    } catch (_: Exception) {
        null
    }

    private fun duf(d: String, e: Int, f: Int = 10): Int {
        val chars = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ+/"
        val h = chars.toList().take(e)
        val i = chars.toList().take(f)
        var j = 0.0
        for ((c, b) in d.reversed().withIndex()) {
            if (b in h) j += h.indexOf(b) * e.toDouble().pow(c)
        }
        var k = ""
        while (j > 0) {
            k = i[(j % f).toInt()] + k
            j = (j - j % f) / f
        }
        return k.toIntOrNull() ?: 0
    }

    private fun hunter(h: String, n: String, t: Int, e: Int): String {
        var result = ""
        var i = 0
        while (i < h.length) {
            var j = 0
            var s = ""
            while (h[i] != n[e]) {
                s += h[i]
                i++
            }
            while (j < n.length) {
                s = s.replace(n[j], j.digitToChar())
                j++
            }
            result += (duf(s, e) - t).toChar()
            i++
        }
        return result
    }
}
