package org.symera.mediasource.multisrc.pelisplus;

import kotlin.coroutines.EmptyCoroutineContext;
import kotlin.coroutines.Continuation;
import kotlin.jvm.functions.Function1;
import kotlin.jvm.functions.Function2;
import kotlinx.coroutines.BuildersKt;
import org.junit.Test;
import org.symera.mediasource.lib.streamwish.StreamWishExtractor;
import org.symera.source.model.HeaderScope;
import org.symera.source.model.HttpMethod;
import org.symera.source.model.MediaRequest;
import org.symera.source.model.PlayableStream;
import org.symera.source.model.SStream;
import org.symera.source.model.StreamHints;
import org.symera.source.model.StreamProtocol;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CancellationException;

import static org.junit.Assert.assertEquals;

public class PelisPlusRoutingTest {
    @Test
    public void vudeoHostFamilyUsesDedicatedHttpExtractorRoute() {
        assertEquals("vudeo", PelisPlus.Companion.routeKey("https://vudeo.co/video/abc"));
        assertEquals("vudeo", PelisPlus.Companion.routeKey("https://vudea.example/embed/abc"));
    }

    @Test
    public void browserBackedHostFamiliesUseUniversalRoute() {
        assertEquals("universal", PelisPlus.Companion.routeKey("https://pelisplus-cdn.example/embed/abc"));
        assertEquals("universal", PelisPlus.Companion.routeKey("https://waaw.example/embed/abc"));
        assertEquals("universal", PelisPlus.Companion.routeKey("https://primeload.example/embed/abc"));
    }

    @Test
    public void doramasflixFamiliesUseExistingDedicatedOrBrowserRoutes() {
        assertEquals("uqload", PelisPlus.Companion.routeKey("https://uqload.com/embed-abc.html"));
        assertEquals("streamsb", PelisPlus.Companion.routeKey("https://playersb.com/e/abc.html"));
        assertEquals("emturbo", PelisPlus.Companion.routeKey("https://lvturbo.com/e/abc.html"));
        assertEquals("okru", PelisPlus.Companion.routeKey("https://ok.ru/videoembed/123"));
        assertEquals("universal", PelisPlus.Companion.routeKey("https://primeload.co/embed/abc"));
        assertEquals("vudeo", PelisPlus.Companion.routeKey("https://vudeo.co/e/abc"));
        assertEquals("streamwish", PelisPlus.Companion.routeKey("https://flaswish.com/e/abc"));
        assertEquals("streamtape", PelisPlus.Companion.routeKey("https://streamtape.com/e/abc"));
        assertEquals("vidhide", PelisPlus.Companion.routeKey("https://vidhidepre.com/embed/abc"));
    }

    @Test
    public void doramasflixUnconfirmedFamiliesRemainUnclassified() {
        assertEquals(null, PelisPlus.Companion.routeKey("https://likessb.com/e/abc.html"));
        assertEquals(null, PelisPlus.Companion.routeKey("https://repro3.estrenosdoramas.us/repro/abc"));
        assertEquals(null, PelisPlus.Companion.routeKey("https://sprintcdn.example/e/abc"));
    }

    @Test
    public void amazonStreamIdUsesStableEpIdInsteadOfTemporaryMediaUrl() {
        assertEquals(
            "amazon-cc7297c71828ab208411654e48e65cd77aac1cdf564e6b1cf078d2a9fc71bccc",
            PelisPlus.Companion.amazonStreamId("amazon-ep-42")
        );
    }

    @Test
    public void okruHttpFailureFallsThroughToBrowserResolver() throws InterruptedException {
        List<SStream> streams = resolveHttpThenBrowser(
            ignored -> { throw new IllegalStateException("HTTP failed"); },
            ignored -> Collections.singletonList(new PlayableStream(
                "https://cdn.example/okru.mp4",
                "browser",
                new MediaRequest("https://cdn.example/okru.mp4", HttpMethod.GET, null, Collections.emptyList(), HeaderScope.ALL_DERIVED_REQUESTS),
                StreamProtocol.AUTO,
                new StreamHints(),
                null,
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                false
            ))
        );

        assertEquals(1, streams.size());
        assertEquals("https://cdn.example/okru.mp4", ((PlayableStream) streams.get(0)).getRequest().getUri());
    }

    @Test
    public void streamWishAliasKeepsOriginalEmbedHostFirst() {
        assertEquals("streamwish", PelisPlus.Companion.routeKey("https://flaswish.com/f/abc"));
        assertEquals("flaswish.com", StreamWishExtractor.Companion.hostCandidates("https://flaswish.com/f/abc").get(0));
        assertEquals("streamwish.com", StreamWishExtractor.Companion.hostCandidates("https://flaswish.com/f/abc").get(1));
    }

    @Test
    public void vudeoHttpFailureFallsThroughToBrowserResolver() throws InterruptedException {
        List<SStream> streams = resolveVudeo(
            ignored -> { throw new IllegalStateException("HTTP failed"); },
            ignored -> Collections.singletonList(new PlayableStream(
                "https://cdn.example/browser.mp4",
                "browser",
                new MediaRequest("https://cdn.example/browser.mp4", HttpMethod.GET, null, Collections.emptyList(), HeaderScope.ALL_DERIVED_REQUESTS),
                StreamProtocol.AUTO,
                new StreamHints(),
                null,
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                false
            ))
        );

        assertEquals(1, streams.size());
        assertEquals("https://cdn.example/browser.mp4", ((PlayableStream) streams.get(0)).getRequest().getUri());
    }

    @Test(expected = CancellationException.class)
    public void vudeoCancellationIsNotConvertedToBrowserFallback() throws InterruptedException {
        resolveVudeo(
            ignored -> { throw new CancellationException("cancelled"); },
            ignored -> Collections.singletonList(new PlayableStream(
                "https://cdn.example/browser.mp4",
                "browser",
                new MediaRequest("https://cdn.example/browser.mp4", HttpMethod.GET, null, Collections.emptyList(), HeaderScope.ALL_DERIVED_REQUESTS),
                StreamProtocol.AUTO,
                new StreamHints(),
                null,
                Collections.emptyList(),
                Collections.emptyList(),
                Collections.emptyList(),
                false
            ))
        );
    }

    private static List<SStream> resolveVudeo(
        Function1 http,
        Function1 browser
    ) throws InterruptedException {
        return (List<SStream>) BuildersKt.runBlocking(
            EmptyCoroutineContext.INSTANCE,
            (Function2) (scope, continuation) -> PelisPlus.Companion.resolveVudeoStreams$pelisplus(http, browser, (Continuation) continuation)
        );
    }

    private static List<SStream> resolveHttpThenBrowser(
        Function1 http,
        Function1 browser
    ) throws InterruptedException {
        return (List<SStream>) BuildersKt.runBlocking(
            EmptyCoroutineContext.INSTANCE,
            (Function2) (scope, continuation) -> PelisPlus.Companion.resolveHttpThenBrowser$pelisplus(http, browser, (Continuation) continuation)
        );
    }
}
