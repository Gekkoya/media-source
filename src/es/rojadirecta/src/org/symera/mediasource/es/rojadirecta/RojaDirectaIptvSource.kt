package org.symera.mediasource.es.rojadirecta

import org.symera.source.SourceEnvironment
import org.symera.source.SourceIdGenerator
import org.symera.source.iptv.CompositeIptvSession
import org.symera.source.iptv.IptvAuthentication
import org.symera.source.iptv.IptvCapabilities
import org.symera.source.iptv.IptvCapability
import org.symera.source.iptv.IptvCatalogConfiguration
import org.symera.source.iptv.IptvChannel
import org.symera.source.iptv.IptvChannelCatalog
import org.symera.source.iptv.IptvChannelKind
import org.symera.source.iptv.IptvChannelQuery
import org.symera.source.iptv.IptvConfiguration
import org.symera.source.iptv.IptvConfiguredChannel
import org.symera.source.iptv.IptvCredentials
import org.symera.source.iptv.IptvGroup
import org.symera.source.iptv.IptvGroupCatalog
import org.symera.source.iptv.IptvLivePlaybackResolver
import org.symera.source.iptv.IptvPage
import org.symera.source.iptv.IptvPageRequest
import org.symera.source.iptv.IptvPlaybackRequest
import org.symera.source.iptv.IptvPlaybackServices
import org.symera.source.iptv.IptvResult
import org.symera.source.iptv.IptvSession
import org.symera.source.iptv.IptvSessionServices
import org.symera.source.iptv.IptvSource
import org.symera.source.iptv.IptvStreamProtocol
import java.net.URI

private const val SOURCE_NAME = "RojaDirecta"
private const val SOURCE_LANGUAGE = "es"
private const val VERSION_ID = 1
private const val GROUP_ID = "deportes"

class RojaDirectaIptvSource(
    private val environment: SourceEnvironment,
) : IptvSource {
    private val channels = RojaDirectaCatalog.channels
    private val groups = listOf(IptvGroup(GROUP_ID, "Deportes"))

    override val id: Long = SourceIdGenerator.generate(SOURCE_NAME, SOURCE_LANGUAGE, VERSION_ID)
    override val name: String = "RojaDirecta TV"
    override val capabilities: IptvCapabilities = IptvCapabilities(
        setOf(IptvCapability.TV, IptvCapability.GROUPS),
    )
    override val authentication: IptvAuthentication = IptvAuthentication.NONE

    override fun configurations(): List<IptvConfiguration> = listOf(
        IptvConfiguration(
            id = "rojadirecta",
            name = name,
            catalog = IptvCatalogConfiguration.Channels(
                entries = channels.map { channel ->
                    IptvConfiguredChannel(
                        channel = channel,
                        playback = IptvPlaybackRequest(
                            uri = URI(channel.attributes.getValue("pageUrl")),
                            protocol = IptvStreamProtocol.HLS,
                        ),
                    )
                },
                groups = groups,
            ),
        ),
    )

    override suspend fun openSession(
        configuration: IptvConfiguration,
        credentials: IptvCredentials,
    ): IptvResult<IptvSession> {
        val resolver = CapoPlaybackResolver(
            client = environment.httpClient,
            userAgent = environment.userAgent.ifBlank { DEFAULT_USER_AGENT },
        )
        val activeChannels = when (val loaded = resolver.loadChannels()) {
            is IptvResult.Failure -> return loaded
            is IptvResult.Success -> loaded.value
        }
        val catalog = object : IptvChannelCatalog {
            override val channelKinds: Set<IptvChannelKind> = setOf(IptvChannelKind.TV)
            override val supportsSearch: Boolean = true

            override suspend fun getChannels(
                query: IptvChannelQuery,
                page: IptvPageRequest,
            ): IptvResult<IptvPage<IptvChannel>> {
                val search = query.search
                val filtered = activeChannels.filter { channel ->
                    (query.groupId == null || query.groupId in channel.groupIds) &&
                        (query.kind == null || query.kind == channel.kind) &&
                        (search == null || channel.name.contains(search, ignoreCase = true))
                }
                return IptvResult.Success(IptvPage(filtered, totalCount = filtered.size.toLong()))
            }
        }
        val groupCatalog = IptvGroupCatalog { IptvResult.Success(IptvPage(groups, totalCount = groups.size.toLong())) }
        return IptvResult.Success(
            CompositeIptvSession(
                configuration = configuration,
                services = IptvSessionServices(
                    channels = catalog,
                    groups = groupCatalog,
                    playback = IptvPlaybackServices(IptvLivePlaybackResolver(resolver::resolve)),
                ),
            ),
        )
    }

    private companion object {
        const val DEFAULT_USER_AGENT = "Mozilla/5.0 (Linux; Android  TV) AppleWebKit/537.36 Chrome/120 Safari/537.36"
    }
}
