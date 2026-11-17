package io.github.dixonsolutions.godnotic;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a package replacement invalidates, decided without Android so it can be tested on the JVM.
 *
 * <p>The installer (and an AndySU host relaunching into a staged release) kills the process without
 * letting it finish anything: no {@code onDestroy}, no last write, no notification. Two things then
 * survive into the next process and mean something they should not:
 *
 * <ul>
 *   <li>a request file the menu wrote for the update service, which the killed process never
 *       served — a stale {@code install} token would install again on the first start;</li>
 *   <li>the update cache, holding the very APK that was just installed, or one older than it,
 *       which nothing can install any more.</li>
 * </ul>
 *
 * <p>The version stamp is also what tells a first start of a new build from an ordinary restart;
 * {@link ReplaceGuard} does the file work.
 */
final class PackageReplace {

    /** How long the engine gets to write its configs after being asked to quit, before the installer may kill it. */
    static final long QUIET_MS = 800;

    /** The engine command that shuts it down cleanly (it writes configs and the layout on the way). */
    static final String QUIT = "quit";

    /**
     * Request files in {@code <userdir>/data/touch} that the process being replaced never served.
     * A token is a one-shot instruction ("install", "apply", "share"): serving it after a
     * replacement acts on a screen that no longer exists.
     */
    static final String[] REQUESTS = {
        "update-request.txt", "andysu-update-request.txt", "crash-reports-request.txt",
    };

    /** Engine state in the userdir that described the process being killed, not this one. */
    static final String[] STATE = { "data/touch_netstate", "data/touch_update_game_state" };

    /** "Godnotic-live-1.2.261-arm64.apk" -> "1.2.261", "x" -> null. */
    private static final Pattern VERSION = Pattern.compile("(\\d+(?:\\.\\d+)+)");

    private PackageReplace() {}

    /**
     * True when the build stamped by the last start is not the one running now: the package was
     * replaced (installed, updated, or restored) since, so its leftovers are not this session's.
     */
    static boolean replaced(String stamped, String installed) {
        return stamped == null || stamped.isEmpty()
                || installed == null || installed.isEmpty()
                || !stamped.equals(installed);
    }

    /** The dotted version in a cached asset's name, or null. */
    static String versionIn(String name) {
        if (name == null) {
            return null;
        }
        Matcher matcher = VERSION.matcher(name);
        return matcher.find() ? matcher.group(1) : null;
    }

    /**
     * Cache files that can never be installed now: the release that is running, anything older,
     * and anything with no version in its name. A newer one stays for the next start.
     */
    static List<String> staleApks(List<String> names, String installed) {
        List<String> stale = new ArrayList<>();
        if (names == null) {
            return stale;
        }
        for (String name : names) {
            String version = versionIn(name);
            if (version == null || installed == null || installed.isEmpty()
                    || compare(version, installed) <= 0) {
                stale.add(name);
            }
        }
        return stale;
    }

    /** Numeric dotted-version compare; non-numeric parts sort as 0 (the rule {@link AppUpdater} uses). */
    private static int compare(String a, String b) {
        String[] left = a.split("\\.");
        String[] right = b.split("\\.");
        int parts = Math.max(left.length, right.length);
        for (int i = 0; i < parts; i++) {
            int l = i < left.length ? number(left[i]) : 0;
            int r = i < right.length ? number(right[i]) : 0;
            if (l != r) {
                return l < r ? -1 : 1;
            }
        }
        return 0;
    }

    private static int number(String part) {
        int end = 0;
        while (end < part.length() && Character.isDigit(part.charAt(end))) {
            end++;
        }
        if (end == 0) {
            return 0;
        }
        try {
            return Integer.parseInt(part.substring(0, end));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
