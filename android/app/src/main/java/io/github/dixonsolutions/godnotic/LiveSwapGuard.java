package io.github.dixonsolutions.godnotic;

/**
 * The state machine behind an in-process engine swap, kept free of Android classes so it runs as a
 * plain JVM unit test. EngineSwap drives it from the loader's callbacks and its watchdog.
 *
 * <p>ACCEPTED covers the wait for the loader to start the new engine (data preparation included);
 * STARTED is the shorter wait for the new engine to report healthy. EXPIRED is terminal: the
 * caller reports failed and relaunches through the host.
 */
final class LiveSwapGuard {
    enum State { IDLE, ACCEPTED, STARTED, HEALTHY, FAILED, EXPIRED }

    private State state = State.IDLE;
    private long deadline = -1;

    /** A device class may live-swap only if the host supports it, the user left it on and no
     *  earlier trial on this class ended in a crash or a watchdog. */
    static boolean allowed(boolean hostApi2, boolean settingOn, boolean badDevice, boolean unfinishedTrial) {
        return hostApi2 && settingOn && !badDevice && !unfinishedTrial;
    }

    /** A new trial may begin when none is in flight and the last one was not a failure. */
    synchronized boolean canAccept() {
        return state == State.IDLE || state == State.HEALTHY;
    }

    synchronized boolean accept(long now, long timeoutMs) {
        if (!canAccept()) return false;
        state = State.ACCEPTED;
        deadline = timeoutMs > 0 ? now + timeoutMs : -1;
        return true;
    }

    synchronized boolean started(long now, long timeoutMs) {
        if (state != State.ACCEPTED) return false;
        state = State.STARTED;
        deadline = timeoutMs > 0 ? now + timeoutMs : -1;
        return true;
    }

    synchronized boolean healthy() {
        if (state != State.ACCEPTED && state != State.STARTED) return false;
        state = State.HEALTHY;
        deadline = -1;
        return true;
    }

    synchronized boolean failed() {
        if (state != State.ACCEPTED && state != State.STARTED) return false;
        state = State.FAILED;
        deadline = -1;
        return true;
    }

    /** Marks the wait expired once; false when it already is or the swap is not waiting. */
    synchronized boolean expire(long now) {
        if ((state != State.ACCEPTED && state != State.STARTED) || deadline < 0 || now < deadline) return false;
        state = State.EXPIRED;
        deadline = -1;
        return true;
    }

    synchronized boolean active() {
        return state == State.ACCEPTED || state == State.STARTED;
    }

    synchronized State state() { return state; }
    long deadline() { return deadline; }
}
