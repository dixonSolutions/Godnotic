package io.github.dixonsolutions.godnotic;

/** Process names of the AndySU host that are not the game. Plain JVM so it has a unit test. */
final class HostProcess {
    private static final String PHOENIX = ":andysu_phoenix";

    private HostProcess() {}

    /**
     * The host's short-lived relaunch helper. Its Application is this app's class, but the game's
     * initialization must not run there: it is about to kill the old process and exit.
     */
    static boolean isRelaunch(String processName) {
        return processName != null && processName.endsWith(PHOENIX);
    }
}
