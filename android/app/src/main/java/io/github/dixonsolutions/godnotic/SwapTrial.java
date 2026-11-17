package io.github.dixonsolutions.godnotic;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * The per-device record of a live engine swap trial, kept as files under {@code no_backup} rather
 * than in {@code SharedPreferences}.
 *
 * <p>The record only matters if it survives the death it guards against: the loader swaps
 * {@code libmain.so} in-process, and if the new engine dies the process is killed with no callback.
 * A preference write is asynchronous ({@code apply()}) and can be lost in exactly that kill, which
 * would leave the next start offering the same swap to the same device class again — the crash
 * loop the trial marker exists to stop. Every write here goes to a temporary file, is fsynced and
 * then renamed, so a start either sees the trial that began or none at all.
 *
 * <p>{@code trial-<key>} exists while a swap is being tried; {@code bad-<key>} means this device
 * class must never live-swap again. No file of either kind is the normal state (the first trial, or
 * one that finished healthy).
 */
final class SwapTrial {
    private SwapTrial() {}

    /** A device class is in the middle of a swap that has not reported healthy. */
    static boolean trial(File dir, String key) {
        return file(dir, "trial-", key).isFile();
    }

    /** A trial on this device class failed (or was never finished): no more live swaps. */
    static boolean bad(File dir, String key) {
        return file(dir, "bad-", key).isFile();
    }

    /** Before the native request: the trial exists on disk before the engine is touched. */
    static boolean begin(File dir, String key) {
        return write(file(dir, "trial-", key), "trial");
    }

    /** The new engine reported healthy: the class is good again (no marker at all). */
    static void healthy(File dir, String key) {
        delete(file(dir, "trial-", key));
    }

    /** The loader declined the request: there was no trial to record. */
    static void refused(File dir, String key) {
        healthy(dir, key);
    }

    /** A trial failed, timed out, or died: durable before anything else happens. */
    static void failed(File dir, String key) {
        write(file(dir, "bad-", key), "bad");
        delete(file(dir, "trial-", key));
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
