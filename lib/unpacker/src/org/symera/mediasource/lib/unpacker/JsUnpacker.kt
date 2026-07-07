package org.symera.mediasource.lib.unpacker

object JsUnpacker {
    private val packedRegex by lazy { Regex("""eval\(function\(p,a,c,k,e,[rd]?""", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)) }
    private val packedExtractRegex by lazy { Regex("""\}\s*\('(.*)',\s*(\d+),\s*(\d+),\s*'(.*?)'\.split\('\|'\)""", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)) }
    private val unpackReplaceRegex by lazy { Regex("""\b\w+\b""", setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)) }

    fun detect(scriptBlock: String): Boolean = scriptBlock.contains(packedRegex)

    fun detect(vararg scriptBlock: String): List<String> = scriptBlock.mapNotNull { if (it.contains(packedRegex)) it else null }

    fun detect(scriptBlocks: Collection<String>): List<String> = detect(*scriptBlocks.toTypedArray())

    fun unpack(scriptBlock: String): Sequence<String> = if (!detect(scriptBlock)) emptySequence() else unpacking(scriptBlock)

    fun unpackAndCombine(scriptBlock: String): String? = unpack(scriptBlock).joinToString(" ").takeIf(String::isNotBlank)

    fun unpack(vararg scriptBlock: String): List<String> {
        val packedScripts = detect(*scriptBlock)
        return packedScripts.flatMap { unpacking(it) }
    }

    fun unpack(scriptBlocks: Collection<String>): List<String> = unpack(*scriptBlocks.toTypedArray())

    private fun unpacking(scriptBlock: String): Sequence<String> = packedExtractRegex.findAll(scriptBlock).mapNotNull { result ->
        val payload = result.groups[1]?.value
        val symtab = result.groups[4]?.value?.split('|')
        val radix = result.groups[2]?.value?.toIntOrNull() ?: 10
        val count = result.groups[3]?.value?.toIntOrNull()
        val unbaser = Unbaser(radix)

        if (payload == null || symtab == null || count == null || symtab.size != count) {
            null
        } else {
            payload.replace(unpackReplaceRegex) { match ->
                val word = match.value
                val unbased = symtab.getOrNull(unbaser.unbase(word)) ?: ""
                unbased.ifEmpty { word }
            }
        }
    }
}
