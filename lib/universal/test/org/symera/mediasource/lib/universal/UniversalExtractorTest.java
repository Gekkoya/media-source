package org.symera.mediasource.lib.universal;

import kotlin.coroutines.Continuation;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlinx.coroutines.BuildersKt;
import okhttp3.Headers;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.Test;
import org.symera.source.model.HeaderScope;
import org.symera.source.model.PlayableStream;
import org.symera.source.model.SStream;
import org.symera.source.network.MediaBrowser;
import org.symera.source.network.MediaBrowserFactory;
import org.symera.source.network.MediaBrowserRequest;
import org.symera.source.network.MediaBrowserResult;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;

public class UniversalExtractorTest {
    @Test
    public void propagatesAllowlistAndCapturedHeadersToDirectStream() throws Exception {
        RecordingBrowser browser = new RecordingBrowser(new MediaBrowserResult(
            "https://media.example/video.mp4",
            Collections.singletonMap("Cookie", "session=browser")
        ));
        UniversalExtractor extractor = new UniversalExtractor(
            new OkHttpClient(),
            (MediaBrowserFactory) () -> browser
        );

        List<SStream> streams = resolve(
            extractor,
            "https://hoster.example/embed",
            Collections.singleton("iframe.example")
        );

        assertEquals(Collections.singleton("iframe.example"), browser.request.getAllowedTopLevelHosts());
        PlayableStream stream = (PlayableStream) streams.get(0);
        assertEquals("https://media.example/video.mp4", stream.getRequest().getUri());
        assertEquals(HeaderScope.ALL_DERIVED_REQUESTS, stream.getRequest().getHeaderScope());
        assertEquals("session=browser", stream.getRequest().getHeaders().get(0).getValue());
    }

    @Test(expected = java.util.concurrent.CancellationException.class)
    public void resolverCancellationIsNotConvertedToEmptyStreams() throws Exception {
        UniversalExtractor extractor = new UniversalExtractor(
            new OkHttpClient(),
            (MediaBrowserFactory) () -> new CancellingBrowser()
        );

        resolve(extractor, "https://hoster.example/embed", Collections.emptySet());
    }

    @Test
    public void hlsAndDashInspectionFailuresReturnDirectStreams() throws Exception {
        OkHttpClient invalidPlaylistClient = new OkHttpClient.Builder()
            .addInterceptor(new InvalidPlaylistInterceptor())
            .build();

        PlayableStream hls = (PlayableStream) resolve(
            new UniversalExtractor(invalidPlaylistClient, browser(
                "https://media.example/video.m3u8",
                Collections.singletonMap("Referer", "https://callistanise.com/embed/player")
            )),
            "https://hoster.example/embed",
            Collections.emptySet()
        ).get(0);
        PlayableStream dash = (PlayableStream) resolve(
            new UniversalExtractor(invalidPlaylistClient, browser(
                "https://media.example/video.mpd",
                Collections.singletonMap("Referer", "https://callistanise.com/embed/player")
            )),
            "https://hoster.example/embed",
            Collections.emptySet()
        ).get(0);

        assertEquals("https://media.example/video.m3u8", hls.getRequest().getUri());
        assertEquals(HeaderScope.ALL_DERIVED_REQUESTS, hls.getRequest().getHeaderScope());
        assertEquals("https://callistanise.com/embed/player", headerValue(hls, "Referer"));
        assertEquals("https://callistanise.com", headerValue(hls, "Origin"));
        assertEquals("https://media.example/video.mpd", dash.getRequest().getUri());
        assertEquals(HeaderScope.ALL_DERIVED_REQUESTS, dash.getRequest().getHeaderScope());
        assertEquals("https://callistanise.com/embed/player", headerValue(dash, "Referer"));
        assertEquals("https://callistanise.com", headerValue(dash, "Origin"));
    }

    private static MediaBrowserFactory browser(String mediaUrl) {
        return browser(mediaUrl, Collections.emptyMap());
    }

    private static MediaBrowserFactory browser(String mediaUrl, java.util.Map<String, String> headers) {
        return () -> new RecordingBrowser(new MediaBrowserResult(mediaUrl, headers));
    }

    private static String headerValue(PlayableStream stream, String name) {
        return stream.getRequest().getHeaders().stream()
            .filter(header -> name.equalsIgnoreCase(header.getName()))
            .map(header -> header.getValue())
            .findFirst()
            .orElse(null);
    }

    private static List<SStream> resolve(
        UniversalExtractor extractor,
        String entryUrl,
        Set<String> allowedHosts
    ) throws Exception {
        return (List<SStream>) BuildersKt.runBlocking(
            EmptyCoroutineContext.INSTANCE,
            (kotlin.jvm.functions.Function2) (scope, continuation) -> extractor.streamsFromUrl(
                entryUrl,
                Headers.of("Cookie", "session=jar"),
                null,
                "",
                allowedHosts,
                (Continuation) continuation
            )
        );
    }

    private static final class RecordingBrowser implements MediaBrowser {
        private final MediaBrowserResult result;
        private MediaBrowserRequest request;

        private RecordingBrowser(MediaBrowserResult result) {
            this.result = result;
        }

        @Override
        public Object resolve(MediaBrowserRequest request, Continuation<? super MediaBrowserResult> continuation) {
            this.request = request;
            return result;
        }

        @Override
        public void close() throws IOException {
        }
    }

    private static final class CancellingBrowser implements MediaBrowser {
        @Override
        public Object resolve(MediaBrowserRequest request, Continuation<? super MediaBrowserResult> continuation) {
            throw new java.util.concurrent.CancellationException("cancelled");
        }

        @Override
        public void close() throws IOException {
        }
    }

    private static final class InvalidPlaylistInterceptor implements Interceptor {
        @Override
        public Response intercept(Chain chain) {
            Request request = chain.request();
            return new Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(ResponseBody.create(MediaType.parse("text/plain"), "not a playlist"))
                .build();
        }
    }
}
