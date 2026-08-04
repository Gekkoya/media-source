package org.symera.mediasource.lib.uqload;

import org.junit.Test;
import org.symera.source.model.PlayableStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class UqloadExtractorTest {
    @Test
    public void parsesSourceAndUsesCanonicalMirrorAndReferer() {
        PlayableStream source = UqloadExtractor.Companion.parseSource("sources: [\"http://cdn.example/video.mp4\"]", "ES ");
        assertEquals("http://cdn.example/video.mp4", source.getRequest().getUri());
        assertEquals("ES Uqload", source.getTitle());
        assertEquals("https://uqload.is/", source.getRequest().getHeaders().get(0).getValue());
        assertTrue(source.getRequest().getHeaderScope().name().equals("ALL_DERIVED_REQUESTS"));
    }

    @Test
    public void noMediaSourceReturnsNull() {
        assertEquals(null, UqloadExtractor.Companion.parseSource("<script>sources: []</script>", ""));
    }
}
