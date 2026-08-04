package org.symera.mediasource.lib.lulu;

import kotlin.jvm.functions.Function1;
import org.junit.Test;
import org.symera.source.model.SStream;
import kotlinx.coroutines.BuildersKt;
import kotlin.coroutines.EmptyCoroutineContext;
import kotlin.jvm.functions.Function2;

import java.net.ProtocolException;
import java.util.List;
import java.util.concurrent.CancellationException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class LuluExtractorTest {
    @Test
    public void packedPageSourceIsExtractedAndQueryTokensAreNormalized() {
        String source = LuluExtractor.Companion.extractSource(
            "<script>eval(function(p,a,c,k,e,d){})</script>",
            new Function1<String, String>() {
                @Override public String invoke(String ignored) {
                    return "sources:[{file:\"https://cdn.example/video.m3u8?=token&=signature&=expiry&=format&i=old&sp=old\"}]";
                }
            }
        );
        assertEquals("https://cdn.example/video.m3u8?t=token&s=signature&e=expiry&f=format&i=0.3&sp=0", source);
    }

    @Test
    public void invalidSourceReturnsNull() {
        assertNull(LuluExtractor.Companion.extractSource("sources:[{file:\"not-a-url\"}]", value -> value));
    }

    @Test(expected = CancellationException.class)
    public void streamsFromUrlRethrowsCancellation() throws InterruptedException {
        LuluExtractor extractor = new LuluExtractor(new okhttp3.OkHttpClient() {
            @Override public okhttp3.Call newCall(okhttp3.Request request) {
                throw new CancellationException("cancelled");
            }
        }, okhttp3.Headers.of());
        BuildersKt.runBlocking(EmptyCoroutineContext.INSTANCE, (Function2) (scope, continuation) ->
            extractor.streamsFromUrl("https://luluvdo.com/e/abc", "", (kotlin.coroutines.Continuation) continuation));
    }
}
