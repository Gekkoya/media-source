package org.symera.mediasource.lib.okru;

import org.junit.Test;
import org.symera.source.model.PlayableStream;
import org.symera.source.model.HttpHeader;
import org.symera.source.model.SStream;
import okhttp3.Call;
import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class OkruExtractorTest {
    @Test
    public void parsesEscapedVideoArrayAndNormalizesQuality() {
        List<PlayableStream> streams = OkruExtractor.Companion.parseVideos(
            "{\\\"videos\\\":[{\\\"name\\\":\\\"full\\\",\\\"url\\\":\\\"https://cdn.example/full.mp4\\\"},{\\\"name\\\":\\\"sd\\\",\\\"url\\\":\\\"https://cdn.example/sd.mp4\\\"}]}",
            "LAT",
            true,
            "https://ok.ru/video/123"
        );
        assertEquals("https://cdn.example/full.mp4", streams.get(0).getRequest().getUri());
        assertEquals("LAT Okru:1080p", streams.get(0).getTitle());
        assertEquals("https://cdn.example/sd.mp4", streams.get(1).getRequest().getUri());
        assertEquals("https://ok.ru/video/123", streams.get(0).getRequest().getHeaders().get(0).getValue());
        assertTrue(streams.stream().allMatch(it -> it.getRequest().getHeaderScope().name().equals("ALL_DERIVED_REQUESTS")));
    }

    @Test
    public void parsesEscapedHlsAndDashOptions() {
        OkruExtractor.ParsedOptions options = OkruExtractor.Companion.parseOptions(
            "{\\\"ondemandHls\\\":\\\"https://cdn.example/master.m3u8\\\",\\\"ondemandDash\\\":\\\"https://cdn.example/master.mpd\\\"}"
        );
        assertEquals("https://cdn.example/master.m3u8", options.getHlsUrl());
        assertEquals("https://cdn.example/master.mpd", options.getDashUrl());
    }

    @Test
    public void noDirectVideosReturnsEmptyList() {
        assertTrue(OkruExtractor.Companion.parseVideos("{\\\"videos\\\":[]}", "", true, "https://ok.ru/video/123").isEmpty());
    }

    @Test
    public void streamsFromUrlReturnsDirectMpdWhenDashExpansionThrows() {
        Headers headers = new Headers.Builder().add("X-Test", "value").build();
        FakeHttpClient client = new FakeHttpClient();
        OkruExtractor extractor = new OkruExtractor(client, headers);
        List<SStream> streams = extractor.streamsFromUrl(
            "http://ok.ru/video/123",
            "LAT",
            true
        );
        PlayableStream fallback = (PlayableStream) streams.get(0);

        assertEquals("https://cdn.example/master.mpd", fallback.getRequest().getUri());
        assertEquals("https://ok.ru/video/123", fallback.getRequest().getHeaders().stream()
            .filter(it -> it.getName().equalsIgnoreCase("Referer"))
            .findFirst().get().getValue());
        assertEquals("value", fallback.getRequest().getHeaders().stream()
            .filter(it -> it.getName().equalsIgnoreCase("X-Test"))
            .findFirst().get().getValue());
        assertEquals("value", client.pageRequest.header("X-Test"));
        assertEquals("ALL_DERIVED_REQUESTS", fallback.getRequest().getHeaderScope().name());
    }

    private static final class FakeHttpClient extends OkHttpClient {
        private Request pageRequest;

        @Override
        public Call newCall(Request request) {
            if (request.url().toString().contains("master.mpd")) {
                return fakeCall(request, null, new IOException("MPD expansion failed"));
            }
            pageRequest = request;
            String page = "<div data-options=\"{&quot;ondemandDash&quot;:&quot;https://cdn.example/master.mpd&quot;}\"></div>";
            Response response = new Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(ResponseBody.create(MediaType.parse("text/html"), page))
                .build();
            return fakeCall(request, response, null);
        }
    }

    private static Call fakeCall(Request request, Response response, IOException failure) {
        return (Call) Proxy.newProxyInstance(
            Call.class.getClassLoader(),
            new Class<?>[]{Call.class},
            (proxy, method, args) -> {
                if (method.getName().equals("request")) return request;
                if (method.getName().equals("execute")) {
                    if (failure != null) throw failure;
                    return response;
                }
                if (method.getName().equals("clone")) return proxy;
                if (method.getReturnType() == boolean.class) return false;
                if (method.getReturnType() == int.class) return 0;
                return null;
            }
        );
    }
}
