package org.symera.mediasource.lib.playlistutils;

import java.util.List;
import java.util.Collections;
import kotlin.jvm.functions.Function0;
import java.util.concurrent.CancellationException;
import okhttp3.Call;
import okhttp3.Headers;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.junit.Test;
import org.symera.source.model.HeaderScope;
import org.symera.source.model.HttpHeader;
import org.symera.source.model.HttpMethod;
import org.symera.source.model.MediaRequest;
import org.symera.source.model.PlayableStream;
import org.symera.source.model.SStream;
import org.symera.source.model.SubtitleFormat;
import org.symera.source.model.SubtitleTrack;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class PlaylistUtilsTest {
    private final PlaylistUtils playlistUtils = new PlaylistUtils(new OkHttpClient(), Headers.of());

    @Test
    public void inspectionFailureReturnsDirectHlsStream() {
        Headers headers = Headers.of(
                "Referer", "https://host.example/",
                "Origin", "https://host.example"
        );
        List<SStream> result = playlistUtils.withDirectHlsFallback(
                "https://cdn.example/video.m3u8",
                "Host - HLS",
                headers,
                Collections.emptyList(),
                Collections.emptyList(),
                new Function0<List<SStream>>() {
                    @Override
                    public List<SStream> invoke() {
                        throw new IllegalStateException("playlist inspection failed");
                    }
                }
        );

        assertTrue(result.get(0) instanceof PlayableStream);
        PlayableStream stream = (PlayableStream) result.get(0);
        assertEquals("https://cdn.example/video.m3u8", stream.getRequest().getUri());
        assertEquals(HeaderScope.ALL_DERIVED_REQUESTS, stream.getRequest().getHeaderScope());
        assertEquals("https://host.example/", headerValue(stream, "Referer"));
        assertEquals("https://host.example", headerValue(stream, "Origin"));
    }

    private static String headerValue(PlayableStream stream, String name) {
        for (HttpHeader header : stream.getRequest().getHeaders()) {
            if (name.equalsIgnoreCase(header.getName())) return header.getValue();
        }
        return null;
    }

    @Test(expected = CancellationException.class)
    public void inspectionCancellationIsRethrown() {
        playlistUtils.withDirectHlsFallback(
                "https://cdn.example/video.m3u8",
                "Host - HLS",
                Headers.of(),
                Collections.emptyList(),
                Collections.emptyList(),
                new Function0<List<SStream>>() {
                    @Override
                    public List<SStream> invoke() {
                        throw new CancellationException("cancelled");
                    }
                }
        );
    }

    @Test
    public void subtitleFetchCancellationIsRethrown() {
        OkHttpClient cancellingClient = new OkHttpClient() {
            @Override
            public Call newCall(Request request) {
                throw new CancellationException("subtitle fetch cancelled");
            }
        };
        PlaylistUtils cancellingUtils = new PlaylistUtils(cancellingClient, Headers.of());
        SubtitleTrack subtitle = new SubtitleTrack(
                "subtitle",
                new MediaRequest(
                        "https://cdn.example/subtitles.vtt",
                        HttpMethod.GET,
                        null,
                        Collections.emptyList(),
                        HeaderScope.SAME_ORIGIN_DERIVED_REQUESTS
                ),
                "en",
                null,
                SubtitleFormat.UNKNOWN,
                Collections.emptySet(),
                false
        );

        try {
            cancellingUtils.fixSubtitles(Collections.singletonList(subtitle));
            fail("Subtitle cancellation was swallowed");
        } catch (CancellationException cancellation) {
            assertEquals("subtitle fetch cancelled", cancellation.getMessage());
        }
    }
}
