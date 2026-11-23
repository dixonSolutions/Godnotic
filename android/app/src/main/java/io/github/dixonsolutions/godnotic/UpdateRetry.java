package io.github.dixonsolutions.godnotic;

/**
 * How often an update download is tried and what a failure means: plain JVM, so the unit tests
 * cover it without a network ({@link AppUpdater} does the trying).
 *
 * <p>Three tries over about a minute catch the case this exists for: the release is on GitHub
 * before every asset of it has finished uploading, so the APK URL answers 404 for a moment. The
 * same 404 also means the asset was deleted, so the tries are bounded and the message the player
 * ends up with says what to do instead of {@code HTTP 404 downloading ...}.
 */
final class UpdateRetry {
    private UpdateRetry() {}

    /** Tries in one download: 0, 20 s, 40 s apart, about a minute in all. */
    static final int ATTEMPTS = 3;

    /** Milliseconds to wait before [attempt] (0-based): none, then 20 s, then 40 s. */
    static long delayMs(int attempt) {
        return attempt <= 0 ? 0 : 20_000L * Math.min(attempt, ATTEMPTS - 1);
    }

    /**
     * Whether the same request may succeed a moment later: a 404 (the release is still being
     * published), a 5xx or a 408/429 (GitHub shedding load), and the socket errors a phone's
     * network produces. A refusal of the URL or a checksum is not retried: those need a new
     * release, not a new attempt.
     */
    static boolean retryable(String message) {
        if (message == null) {
            return true;
        }
        String m = message.toLowerCase(java.util.Locale.ROOT);
        if (m.contains("untrusted") || m.contains("refusing")) {
            return false;
        }
        return m.contains("http 404") || m.contains("http 408") || m.contains("http 429")
                || m.contains("http 5") || m.contains("timeout") || m.contains("timed out")
                || m.contains("connection reset") || m.contains("connection refused")
                || m.contains("unexpected end of stream") || m.contains("incomplete")
                || m.contains("checksum") || offline(m);
    }

    /**
     * Whether the phone simply had no usable connection: the name did not resolve, the network
     * was unreachable, or the connection never opened. Java says {@code UnknownHostException:
     * Unable to resolve host ...: No address associated with hostname}, which is not something a
     * player can read; it means "you are offline", and the next check succeeds once the phone is.
     */
    static boolean offline(String message) {
        if (message == null) {
            return false;
        }
        String m = message.toLowerCase(java.util.Locale.ROOT);
        return m.contains("unknownhostexception") || m.contains("unable to resolve host")
                || m.contains("no address associated with hostname")
                || m.contains("network is unreachable") || m.contains("enetunreach")
                || m.contains("failed to connect to") || m.contains("noroutetohost")
                || m.contains("no route to host") || m.contains("software caused connection abort");
    }

    /** "No internet connection" with what to do, for every offline failure. */
    static String offlineMessage() {
        return "No internet connection. Check again once you are online.";
    }

    /** A 404 is the one that means the publisher has not finished, rather than a broken phone. */
    static boolean stillPublishing(String message) {
        return message != null && message.toLowerCase(java.util.Locale.ROOT).contains("http 404");
    }

    /** "The update is still being published — it will download by itself in a minute." */
    static String publishingMessage() {
        return "The update is still being published — it will download by itself in a minute.";
    }

    /**
     * What the player or the Updates screen should read for a failed download: the publishing
     * sentence for a 404, the original message otherwise (it names the HTTP code).
     */
    static String friendly(String message) {
        if (stillPublishing(message)) {
            return publishingMessage();
        }
        if (offline(message)) {
            return offlineMessage();
        }
        return message == null ? "" : message;
    }
}
