package io.github.dixonsolutions.godnotic;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** UpdateRetry: three tries over about a minute, and a 404 that reads as "still publishing". */
public class UpdateRetryTest {

    @Test public void threeTriesAddUpToAboutAMinute() {
        assertEquals(3, UpdateRetry.ATTEMPTS);
        assertEquals(0, UpdateRetry.delayMs(0));
        assertEquals(20_000, UpdateRetry.delayMs(1));
        assertEquals(40_000, UpdateRetry.delayMs(2));
        long total = 0;
        for (int attempt = 0; attempt < UpdateRetry.ATTEMPTS; attempt++) {
            total += UpdateRetry.delayMs(attempt);
        }
        assertTrue("about a minute: " + total, total >= 45_000 && total <= 75_000);
    }

    @Test public void transientFailuresAreRetried() {
        assertTrue(UpdateRetry.retryable("HTTP 404 downloading https://github.com/x/y.apk"));
        assertTrue(UpdateRetry.retryable("HTTP 503 downloading https://github.com/x/y.apk"));
        assertTrue(UpdateRetry.retryable("HTTP 429 downloading https://github.com/x/y.apk"));
        assertTrue(UpdateRetry.retryable("Connection reset"));
        assertTrue(UpdateRetry.retryable("Read timed out"));
        assertTrue(UpdateRetry.retryable("update download incomplete (3 of 40 bytes)"));
        assertTrue(UpdateRetry.retryable(null));
    }

    @Test public void aRefusalIsNotRetried() {
        // Nothing a wait can fix: the URL is not this repo's, or the bytes are not the release's.
        assertFalse(UpdateRetry.retryable("refusing to download an untrusted APK URL"));
        assertFalse(UpdateRetry.retryable("cannot create /data/x"));
        assertFalse(UpdateRetry.retryable("no SHA-256 on this device"));
    }

    @Test public void a404ReadsAsStillBeingPublished() {
        String raw = "HTTP 404 downloading https://github.com/dixonSolutions/Godnotic/releases/download/v1.2.259/x.apk";
        assertTrue(UpdateRetry.stillPublishing(raw));
        assertFalse(UpdateRetry.stillPublishing("HTTP 500"));
        assertEquals(UpdateRetry.publishingMessage(), UpdateRetry.friendly(raw));
        // Everything else keeps its own message: it names the code the player can report.
        assertEquals("HTTP 500 downloading x", UpdateRetry.friendly("HTTP 500 downloading x"));
        assertEquals("", UpdateRetry.friendly(null));
        assertTrue(UpdateRetry.publishingMessage().contains("still being published"));
    }
}
