package io.github.dixonsolutions.godnotic;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * The per-device record of live engine swap trials, kept as files under {@code no_backup} rather
 * than in {@code SharedPreferences}.
 *
 * <p>The record only matters if it survives the death it guards against: the loader swaps
 * {@code libmain.so} in-process, and if the new engine dies the process is killed with no callback.
 * A preference write is asynchronous ({@code apply()}) and can be lost in exactly that kill, which
 * would leave the next start offering the same swap to the same device class again. Every write
 * here goes to a temporary file, is fsynced and then renamed, so a start either sees the record
 * that was written or none at all.
 *
 * <p>{@code trial-<key>} exists while a swap is being tried. {@code fail-<key>} remembers that a
 * trial on this device class did not become healthy: a count, the time and the release, one line.
 * A failure is not permanent — the engine is retried after a growing wait (10 minutes, doubling,
 * capped at six hours), so a single crash, a kill by the system or a bad release does not leave
 * the tablet applying updates by restarts forever. A healthy swap deletes both files, and the
 * count starts again.
 */
final class SwapTrial {
    private static final long BASE_BACKOFF_MS = 10 * 60 * 1000L;
    private static final long MAX_BACKOFF_MS = 6 * 60 * 60 * 1000L;
    private static final int MAX_SHIFT = 8;

    private SwapTrial() {}

    /** A device class is in the middle of a swap that has not reported healthy. */
    static boolean trial(File dir, String key) {
        return file(dir, "trial-", key).isFile();
    }

    /** Before the native request: the trial exists on disk before the engine is touched. */
    static boolean begin(File dir, String key) {
        return write(file(dir, "trial-", key), "trial");
    }

    /** The new engine reported healthy: the failure history is over. */
    static void healthy(File dir, String key) {
        delete(file(dir, "trial-", key));
        delete(file(dir, "fail-", key));
    }

    /** The loader declined the request: there was no trial to record. */
    static void refused(File dir, String key) {
        delete(file(dir, "trial-", key));
    }

    /**
     * A trial failed, timed out or died unfinished. Written to disk before anything else happens,
     * with the release that failed, so the next start can say why live is waiting and retry it
     * after the backoff instead of never offering a live swap on this device again.
     */
    static void failed(File dir, String key, long nowMs, String version) {
        int attempts = attempts(dir, key) + 1;
        write(file(dir, "fail-", key),
                attempts + "\n" + nowMs + "\n" + (version == null ? "" : version));
        delete(file(dir, "trial-", key));
    }

    /** The failure count for this device class (0 when it has never failed). */
    static int attempts(File dir, String key) {
        String text = read(file(dir, "fail-", key));
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int nl = text.indexOf('\n');
        try {
            return Math.max(0, Integer.parseInt((nl < 0 ? text : text.substring(0, nl)).trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** The release named by the last failure, or "" when there is none. */
    static String failedVersion(File dir, String key) {
        String text = read(file(dir, "fail-", key));
        if (text == null) {
            return "";
        }
        int first = text.indexOf('\n');
        int second = first < 0 ? -1 : text.indexOf('\n', first + 1);
        return second < 0 ? "" : text.substring(second + 1).trim();
    }

    /** How long until this device class may try again; 0 when it may try now. */
    static long retryAfter(File dir, String key, long nowMs) {
        String text = read(file(dir, "fail-", key));
        if (text == null) {
            return 0;
        }
        int first = text.indexOf('\n');
        int second = first < 0 ? -1 : text.indexOf('\n', first + 1);
        if (first < 0) {
            return 0;
        }
        long at;
        try {
            at = Long.parseLong(text.substring(first + 1, second < 0 ? text.length() : second).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
        long wait = backoff(attempts(dir, key));
        long left = at + wait - nowMs;
        // A clock that went backwards must not mean "wait forever".
        return Math.max(0, Math.min(left, wait));
    }

    /** The exponential wait after {@code attempts} failures. */
    static long backoff(int attempts) {
        if (attempts <= 0) {
            return 0;
        }
        int shift = Math.min(attempts - 1, MAX_SHIFT);
        return Math.min(BASE_BACKOFF_MS << shift, MAX_BACKOFF_MS);
    }

    private static File file(File dir, String prefix, String key) {
        return new File(dir, prefix + safe(key));
    }

    /** The key is a hex hash today; keep a hand-edited or older key inside the directory. */
    static String safe(String key) {
        if (key == null || key.isEmpty()) {
            return "unknown";
        }
        StringBuilder out = new StringBuilder(key.length());
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            out.append((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_' ? c : '_');
        }
        return out.toString();
    }

    private static String read(File file) {
        try {
            return new String(java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static boolean write(File file, String text) {
        File dir = file.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
            return false;
        }
        File tmp = new File(file.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.flush();
            // The kill this record guards against takes the page cache with it.
            out.getFD().sync();
        } catch (IOException | RuntimeException e) {
            // The caller declines the swap and logs it; this class stays free of Android.
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return false;
        }
        if (!tmp.renameTo(file)) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            return false;
        }
        return true;
    }

    private static void delete(File file) {
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
