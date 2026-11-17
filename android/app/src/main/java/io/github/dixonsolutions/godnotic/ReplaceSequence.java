package io.github.dixonsolutions.godnotic;

/**
 * The order of an AndySU apply: quiet the old process, mark it replacing, only then ask the host
 * to relaunch it.
 *
 * <p>The host's {@code restartNow()} does not return: it replaces the payload and relaunches the
 * process. Anything written after that call is dead code, which is how the screens and services of
 * the old process used to keep running into the swap — the window in which the tablet crashed.
 * {@link #apply} makes the order explicit and keeps the relaunch in a {@code finally}, so even a
 * failure in the quiet step still asks for the apply rather than leaving a quiet app behind.
 *
 * <p>Plain JVM, so {@code ReplaceSequenceTest} runs it without a device.
 */
final class ReplaceSequence {
    private ReplaceSequence() {}

    static void apply(Runnable quiet, Runnable beginReplace, Runnable relaunch) {
        try {
            quiet.run();
            beginReplace.run();
        } finally {
            relaunch.run();
        }
    }
}
