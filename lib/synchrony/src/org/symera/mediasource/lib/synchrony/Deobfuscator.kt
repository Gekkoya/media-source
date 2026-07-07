package org.symera.mediasource.lib.synchrony

import app.cash.quickjs.QuickJs
import kotlinx.serialization.builtins.serializer
import org.symera.mediasource.core.jsonInstance

object Deobfuscator {
    fun deobfuscateScript(source: String): String? {
        val originalScript = javaClass.getResource("/assets/$SCRIPT_NAME")?.readText() ?: return source
        val regex = """export\{(.*) as Deobfuscator,(.*) as Transformer\};""".toRegex()
        val synchronyScript = regex.find(originalScript)?.let { match ->
            val (deob, trans) = match.destructured
            originalScript.replace(match.value, "const Deobfuscator = $deob, Transformer = $trans;")
        } ?: return source

        val sourceLiteral = jsonInstance.encodeToString(String.serializer(), source)
        return runCatching {
            QuickJs.create().use { engine ->
                engine.evaluate("globalThis.console = { log: () => {}, warn: () => {}, error: () => {}, trace: () => {} };")
                engine.evaluate(synchronyScript)
                engine.evaluate("new Deobfuscator().deobfuscateSource($sourceLiteral)") as? String
            }
        }.getOrNull() ?: source
    }
}

private const val SCRIPT_NAME = "synchrony-v2.4.5.1.js"
