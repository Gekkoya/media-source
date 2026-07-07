package org.symera.mediasource.multisrc.pelisplus

import org.symera.source.model.Filter

object Filters {
    open class UriPartFilter(
        displayName: String,
        private val vals: List<Pair<String, String>>,
    ) : Filter.Select<String>(displayName, vals.map { it.first }) {
        fun toUriPart(): String = vals[state].second
    }
}
