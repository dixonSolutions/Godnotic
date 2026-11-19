package io.github.dixonsolutions.godnotic;

/**
 * One iteration of a long-running update poll.
 *
 * <p>An uncaught exception on any thread reaches Android's default handler, which kills the whole
 * process: the next crash report says {@code reason: crash (4)} and the player sees "Godnotic has a
 * bug" although only one poll failed. The update thread is the one place where that must never
 * happen — a bad status file, a resource that no longer loads or a transient failure in the
 * installer path is not a reason to end the game. So every poll runs through {@link #once}: the
 * failure is reported and the loop carries on.
 *
 * <p>Plain JVM, no Android classes, so {@code PollLoopTest} runs it without a device.
 */
final class PollLoop {
    private PollLoop() {}

    /** Where a failed poll goes: the caller logs it; it must not be rethrown. */
    interface Reporter {
        void failed(Throwable error);
    }

    /**
     * Run one poll. Returns false when it threw; the throwable is handed to {@code reporter} (a
     * reporter that throws is itself contained, so it cannot take the loop down either).
     */
    static boolean once(Runnable body, Reporter reporter) {
        try {
            body.run();
            return true;
        } catch (Throwable error) {
            if (reporter != null) {
                try {
                    reporter.failed(error);
                } catch (Throwable ignored) {
                    // Reporting a failure must not become a second failure.
                }
            }
            return false;
        }
    }
}
