package org.symera.mediasource.es.rojadirecta

import org.symera.source.SourceEnvironment
import org.symera.source.SymeraExtensionFactory
import org.symera.source.iptv.IptvSource

class RojaDirectaFactory : SymeraExtensionFactory {
    override fun createIptvSources(environment: SourceEnvironment): List<IptvSource> = listOf(RojaDirectaIptvSource(environment))
}
