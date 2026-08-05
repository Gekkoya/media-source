package org.symera.mediasource.lib.vudeo;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import kotlinx.coroutines.BuildersKt;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlin.jvm.functions.Function2;
import org.junit.Test;
import org.symera.source.model.PlayableStream;

import java.lang.reflect.Proxy;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class VudeoExtractorTest {
    @Test
    public void parsesHttpsSourcesWithHostRootReferer() {
        List<PlayableStream> streams = VudeoExtractor.Companion.parseSources(
            "sources: [\"https://cdn.example/720.m3u8\", \"relative\", \"https://cdn.example/480.m3u8\"]",
            "https://player.example/embed/abc",
            "LAT - "
        );
        assertEquals(2, streams.size());
        assertEquals("https://cdn.example/720.m3u8", streams.get(0).getRequest().getUri());
        assertEquals("LAT - Vudeo", streams.get(0).getTitle());
        assertEquals("https://player.example/", streams.get(0).getRequest().getHeaders().get(0).getValue());
        assertTrue(streams.get(0).getRequest().getHeaderScope().name().equals("ALL_DERIVED_REQUESTS"));
    }

    @Test
    public void noSourcesReturnsEmptyList() {
        assertTrue(VudeoExtractor.Companion.parseSources("<script>var player = true;</script>", "https://vudeo.co/embed/abc", "").isEmpty());
    }

    @Test
    public void propagatesSourceHeadersToPageAndMediaRequests() throws InterruptedException {
        Headers headers = new Headers.Builder().add("X-Source", "source-value").build();
        VudeoExtractor extractor = new VudeoExtractor(new FakeHttpClient(), headers);

        List<PlayableStream> streams = (List<PlayableStream>) (List<?>) BuildersKt.runBlocking(
            EmptyCoroutineContext.INSTANCE,
            (Function2) (scope, continuation) -> extractor.streamsFromUrl(
                "https://vudeo.co/embed/abc",
                "LAT - ",
                (kotlin.coroutines.Continuation) continuation
            )
        );

        assertEquals("source-value", FakeHttpClient.lastRequest.header("X-Source"));
        assertEquals("source-value", streams.get(0).getRequest().getHeaders().stream()
            .filter(it -> it.getName().equalsIgnoreCase("X-Source"))
            .findFirst().get().getValue());
        assertEquals("https://vudeo.co/", streams.get(0).getRequest().getHeaders().stream()
            .filter(it -> it.getName().equalsIgnoreCase("Referer"))
            .findFirst().get().getValue());
    }

    private static final class FakeHttpClient extends OkHttpClient {
        private static Request lastRequest;

        @Override public Call newCall(Request request) {
            lastRequest = request;
            String page = "<script>sources: [\"https://cdn.example/video.m3u8\"]</script>";
            Response response = new Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(ResponseBody.create(MediaType.parse("text/html"), page))
                .build();
            return (Call) Proxy.newProxyInstance(
                Call.class.getClassLoader(),
                new Class<?>[]{Call.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("request")) return request;
                    if (method.getName().equals("execute")) return response;
                    if (method.getName().equals("enqueue")) {
                        ((Callback) args[0]).onResponse((Call) proxy, response);
                        return null;
                    }
                    if (method.getName().equals("clone")) return proxy;
                    if (method.getReturnType() == boolean.class) return false;
                    if (method.getReturnType() == int.class) return 0;
                    return null;
                }
            );
        }
    }
}
