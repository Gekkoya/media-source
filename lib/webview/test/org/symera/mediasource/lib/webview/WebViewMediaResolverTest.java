package org.symera.mediasource.lib.webview;

import kotlin.coroutines.Continuation;
import kotlinx.coroutines.BuildersKt;
import kotlin.coroutines.EmptyCoroutineContext;
import org.junit.Test;
import org.symera.source.model.MediaRequest;
import org.symera.source.network.MediaBrowser;
import org.symera.source.network.MediaBrowserFactory;
import org.symera.source.network.MediaBrowserRequest;
import org.symera.source.network.MediaBrowserResult;

import java.io.IOException;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class WebViewMediaResolverTest {
    @Test
    public void resolverPropagatesHeadersCookiesAndHostAllowlist() throws Exception {
        RecordingBrowser browser = new RecordingBrowser(
            new MediaBrowserResult(
                "https://media.example/video.m3u8",
                Collections.singletonMap("Cookie", "session=browser")
            )
        );
        WebViewMediaResolver resolver = new WebViewMediaResolver((MediaBrowserFactory) () -> browser);

        MediaRequest request = resolve(resolver, "https://embed.example/watch", "Cookie", "session=jar");

        assertEquals("https://embed.example/watch", browser.request.getEntryUrl());
        assertEquals("session=jar", browser.request.getHeaders().get("cookie"));
        assertEquals(Collections.singleton("media.example"), browser.request.getAllowedTopLevelHosts());
        assertEquals("session=browser", request.getHeaders().get(0).getValue());
        assertEquals("https://media.example/video.m3u8", request.getUri());
    }

    @Test
    public void capturedMediaHostMustBeExplicitlyAllowed() {
        assertTrue(WebViewMediaResolver.Companion.isAllowedMediaHost("media.example", Collections.singleton("media.example")));
        assertTrue(!WebViewMediaResolver.Companion.isAllowedMediaHost("ads.example", Collections.singleton("media.example")));
    }

    private static MediaRequest resolve(
        WebViewMediaResolver resolver,
        String entryUrl,
        String headerName,
        String headerValue
    ) throws Exception {
        return (MediaRequest) BuildersKt.runBlocking(
            EmptyCoroutineContext.INSTANCE,
            (kotlin.jvm.functions.Function2) (scope, continuation) -> resolver.resolve(
                entryUrl,
                okhttp3.Headers.of(headerName, headerValue),
                WebViewMediaResolver.DEFAULT_MEDIA_URL_PATTERN,
                Collections.singleton("media.example"),
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
}
