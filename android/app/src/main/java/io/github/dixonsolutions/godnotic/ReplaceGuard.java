package io.github.dixonsolutions.godnotic;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A package replacement, from both sides: quiet the app before it is replaced, and clear what the
 * killed process left when the new build starts. {@link PackageReplace} decides what is stale.
 *
 * <p>Android kills the process for an install (and an AndySU host relaunches it for a staged
 * release) with SIGKILL and no callbacks. What the process was doing at that moment is the
 * difference between a replacement and a crash: a QuakeC {@code config.cfg} or
 * {@code touch.layout.cfg} written with plain {@code fopen}/{@code fprintf} is truncated by a kill
 * in the middle, a data pack downloaded into a {@code .part} file keeps bytes it cannot resume
 * from, and a foreground service's notification is left behind with the process. So the last thing
 * done before handing the APK to the installer is to stop the services, release the locks and ask
 * the engine to quit, and the first thing done in the new process is to drop the one-shot requests
 * and cached builds the old one left.
 */
final class ReplaceGuard {
    private static final String TAG = "Godnotic";
    private static final String PREFS = "package-replace";
    private static final String PREF_VERSION = "version";
    /** How long a screen gets to finish before the replacement is under way without it. */
    private static final long FINISH_MS = 250;

    /**
     * An apply has begun: the package is being swapped (or the host is relaunching us). Nothing of
     * this process may start again: no service, no download, no screen. That window is what a
     * crash report calls "the old version crashed while the update was applied".
     */
    private static volatile boolean replacing;
    /** The screens that are alive, so a replacement can end them while their classes still load. */
    private static final List<WeakReference<Activity>> activities = new CopyOnWriteArrayList<>();

    private ReplaceGuard() {}

    /** True from the moment an apply begins until this process is gone. */
    static boolean isReplacing() {
        return replacing;
    }

    /** An activity that a replacement has to finish (its class is the one being replaced). */
    static void watch(Activity activity) {
        if (activity != null) {
            activities.add(new WeakReference<>(activity));
        }
    }

    static void unwatch(Activity activity) {
        for (WeakReference<Activity> ref : activities) {
            if (ref.get() == activity) {
                activities.remove(ref);
            }
        }
    }

    /**
     * The replacement itself: after the installer session commits or the host is asked to
     * relaunch. From here the process only winds down — every service is stopped and refused a
     * restart, every screen is finished on the main thread, and the update loop stops touching the
     * package that is being swapped. The old process then dies with nothing of ours running.
     */
    static void beginReplace(Context context) {
        final Context app = context.getApplicationContext();
        replacing = true;
        try {
            ProcessState.phase(app, "replacing");
            ProcessState.engine(app, false);
            KeepAliveService.stop(app);
            DataService.stopForReplace();
            LanLocks.release();
        } catch (RuntimeException | LinkageError e) {
            Log.w(TAG, "Could not quiet an apply before it replaces the package", e);
        }
        Handler main = new Handler(Looper.getMainLooper());
        for (WeakReference<Activity> ref : activities) {
            Activity activity = ref.get();
            if (activity != null) {
                main.post(activity::finish);
            }
        }
        activities.clear();
        Log.i(TAG, "Apply begun: services stopped, screens finished, no new work this process");
    }

    /**
     * Runs in {@code Application.onCreate} of every start. When the installed version differs from
     * the one the last start stamped, a replacement happened in between: the request files and
     * engine state the killed process left are deleted, and so are the cached APKs that can no
     * longer be installed (the running one, and older ones). A newer cached build stays.
     *
     * <p>No manifest receiver: a live payload cannot add one (the host's manifest is frozen), and
     * the version stamp is what the receiver would have told us anyway.
     */
    static void firstStart(Context context) {
        Context app = context.getApplicationContext();
        SharedPreferences prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String installed = new AppUpdater(app).installedVersion();
        boolean replacement = PackageReplace.replaced(prefs.getString(PREF_VERSION, ""), installed);
        prefs.edit().putString(PREF_VERSION, installed).apply();
        if (!replacement) {
            return;
        }
        File base;
        try {
            base = new GameData(app).baseDir();
        } catch (RuntimeException | LinkageError e) {
            Log.w(TAG, "Could not find the basedir to clear after the replacement", e);
            return;
        }
        File touch = new File(GameData.engineWriteDir(base), "touch");
        for (String name : PackageReplace.REQUESTS) {
            delete(new File(touch, name));
        }
        File user = GameData.userDir(base);
        for (String name : PackageReplace.STATE) {
            delete(new File(user, name));
        }
        File cache = new AppUpdater(app).cacheDir();
        String[] names = cache.list();
        if (names != null) {
            List<String> stale = PackageReplace.staleApks(Arrays.asList(names), installed);
            for (String name : stale) {
                delete(new File(cache, name));
            }
        }
        Log.i(TAG, "First start of " + installed + " after a package replacement: requests, engine "
                + "state and the installed build's cached APK cleared");
    }

    /**
     * The last thing before the installer gets the APK (or the host relaunches): nothing of this
     * process keeps writing, holding a socket or keeping a wake lock while it is being replaced.
     *
     * <p>Called from the threads that drive installs, never the main one; the engine is given
     * {@link PackageReplace#QUIET_MS} to write its configs and exit. Skipped on the main thread
     * rather than blocking it.
     */
    static void beforeReplace(Context context) {
        Context app = context.getApplicationContext();
        try {
            // Stopping now, before the user is asked to confirm, is not the point where the
            // package starts to change: that is beginReplace, once the session is committed. On a
            // cancelled confirmation the app stays up with its services able to start again.
            ProcessState.phase(app, "quiet");
            KeepAliveService.stop(app);
            DataService.stopForReplace();
            LanLocks.release();
            if (!XonoticActivity.running) {
                return;
            }
            if (Looper.myLooper() == Looper.getMainLooper()) {
                // A caller on the UI thread cannot wait for the engine here; the services are
                // stopped all the same, which is the part that must not be forgotten.
                Log.w(TAG, "Asked to quiet the app from the main thread: the engine is not waited for");
                return;
            }
            // The engine writes configs, the touch layout and its last state on the way down.
            NativeBridge.enqueue(PackageReplace.QUIT);
            Thread.sleep(PackageReplace.QUIET_MS);
            Log.i(TAG, "Engine asked to quit before the replacement");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException | LinkageError e) {
            Log.w(TAG, "Could not quiet the app before it is replaced", e);
        }
    }

    private static void delete(File file) {
        if (file.exists() && !file.delete()) {
            Log.w(TAG, "Could not remove " + file);
        }
    }
}
