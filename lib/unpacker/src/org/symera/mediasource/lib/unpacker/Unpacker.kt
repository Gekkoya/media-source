package org.symera.mediasource.lib.unpacker

object Unpacker {
    fun unpack(script: String, left: String? = null, right: String? = null): String = unpack(SubstringExtractor(script), left, right)

    fun unpack(script: SubstringExtractor, left: String? = null, right: String? = null): String {
        val packed = script
            .substringBetween("}('", ".split('|'),0,{}))")
            .replace("\\'", "\"")

        val parser = SubstringExtractor(packed)
        val data: String
        if (left != null && right != null) {
            data = parser.substringBetween(left, right)
            parser.skipOver("',")
        } else {
            data = parser.substringBefore("',")
        }
        if (data.isEmpty()) return ""

        val dictionary = parser.substringBetween("'", "'").split("|")
        val size = dictionary.size

        return wordRegex.replace(data) {
            val key = it.value
            val index = parseRadix62(key)
            if (index >= size) return@replace key
            dictionary.getOrNull(index)?.ifEmpty { key } ?: key
        }
    }

    private val wordRegex by lazy { Regex("""\w+""") }

    private fun parseRadix62(str: String): Int {
        var result = 0
        for (ch in str.toCharArray()) {
            result = result * 62 + when {
                ch.code <= '9'.code -> ch.code - '0'.code
                ch.code >= 'a'.code -> ch.code - ('a'.code - 10)
                else -> ch.code - ('A'.code - 36)
            }
        }
        return result
    }
}
