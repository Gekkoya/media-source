package org.symera.mediasource.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UrlUtilsTest {
    @Test
    fun `fixUrl with empty string returns null`() {
        assertNull(UrlUtils.fixUrl(""))
    }

    @Test
    fun `fixUrl with valid http URL returns unchanged`() {
        val url = "http://example.com/path"
        assertEquals(url, UrlUtils.fixUrl(url))
    }

    @Test
    fun `fixUrl with valid https URL returns unchanged`() {
        val url = "https://example.com/path"
        assertEquals(url, UrlUtils.fixUrl(url))
    }

    @Test
    fun `fixUrl with JSON object returns unchanged`() {
        val json = "{\"key\": \"value\", \"url\": \"https://example.com\"}"
        assertEquals(json, UrlUtils.fixUrl(json))
    }

    @Test
    fun `fixUrl with protocol-relative URL adds https`() {
        assertEquals("https://example.com/path", UrlUtils.fixUrl("//example.com/path"))
    }

    @Test
    fun `fixUrl strips prefix before first URL`() {
        assertEquals("https://example.com/path", UrlUtils.fixUrl("prefix text https://example.com/path"))
    }

    @Test
    fun `fixUrl with invalid base URL returns null`() {
        assertNull(UrlUtils.fixUrl("resource.html", "not-a-valid-url"))
    }

    @Test
    fun `fixUrl with relative path only keeps the path`() {
        assertEquals("path/to/resource", UrlUtils.fixUrl("path/to/resource"))
    }

    @Test
    fun `fixUrl with absolute path and base URL`() {
        assertEquals("https://example.com/path/to/resource", UrlUtils.fixUrl("/path/to/resource", "https://example.com/dir/page.html"))
    }

    @Test
    fun `fixUrl with relative path and base URL`() {
        assertEquals("https://example.com/dir/resource.html", UrlUtils.fixUrl("resource.html", "https://example.com/dir/page.html"))
    }

    @Test
    fun `fixUrl with protocol-relative URL and base URL`() {
        assertEquals("https://cdn.example.com/resource", UrlUtils.fixUrl("//cdn.example.com/resource", "https://example.com/dir/page.html"))
    }

    @Test
    fun `fixUrl with absolute URL and base URL`() {
        assertEquals("https://other.com/resource", UrlUtils.fixUrl("https://other.com/resource", "https://example.com/dir/page.html"))
    }

    @Test
    fun `fixUrl with empty URL and base URL returns null`() {
        assertNull(UrlUtils.fixUrl("", "https://example.com/dir/page.html"))
    }

    @Test
    fun `fixUrl drops base query and fragment`() {
        assertEquals("https://example.com/newpath", UrlUtils.fixUrl("/newpath", "https://example.com/dir/page.html?param=value#fragment"))
    }

    @Test
    fun `fixUrl preserves port number in base URL`() {
        assertEquals("https://example.com:8080/path", UrlUtils.fixUrl("/path", "https://example.com:8080/dir/page.html"))
    }
}
