package io.github.dixonsolutions.godnotic;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Live engine swap under an AndySU host (Host API level 2, D29), by reflection so the same sources
 * build and run without AndySU (like {@link AndySUCompat}; no compile dependency on com.andysu.api).
 *
 * <p>{@link #register} hands the host a {@code LiveSwapHandler}. When the host has a staged release
 * that only changes native code and content, it calls {@code onLiveSwap(slotDir, version, cb)}: this
 * class finds the release's {@code libmain*.so} and asks the resident loader
 * ({@code libandysu_xonotic_loader.so}, {@link #nativeRequestSwap}) to swap the engine in this
 * process. The loader reports back through {@link #onEngineEvent} (started, failed(reason),
 * healthy) and {@link #beforeEngineStart} (unpack the new release's data between the old engine's
 * teardown and the new engine's start), both called from native threads.
 */
final class EngineSwap {
    private static final String TAG = "Godnotic";
    private static final int EV_STARTED = 1;
    private static final int EV_FAILED = 2;
    private static final int EV_HEALTHY = 3;

    private static volatile Context appContext;
    private static volatile Object callback;       // com.andysu.api.LiveSwapCallback
    private static volatile Object host;           // com.andysu.api.HostApi
    private static volatile String fromVersion;    // the release running before the swap: where a rollback goes
    private static volatile String toVersion;      // the release being swapped in
    private static final long WATCHDOG_MS = 90_000;    // data preparation + engine start + health
    private static final long STARTED_WATCHDOG_MS = 60_000;
    private static final String PREFS = "engine_swap";
    private static final LiveSwapGuard guard = new LiveSwapGuard();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final Runnable watchdog = EngineSwap::onWatchdog;
    private static volatile String deviceKey;

    private EngineSwap() {}

    /** The loader answers for the engine library it loads; null means the swap was accepted. */
    static native String nativeRequestSwap(String enginePath);

    /** Where a trial has to survive the kill it guards against: fsynced files under no_backup. */
    private static File trialDir(Context context) {
        return new File(context.getNoBackupFilesDir(), "engine-swap");
    }

    /** Whether the player left live apply on (the default). */
    static boolean liveEnabled(Context context) {
        return prefs(context).getBoolean("live_swap", true);
    }

    /** Settings > Updates, Apply live: the same switch the bridge reads before offering a swap. */
    static void setLiveEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean("live_swap", enabled).apply();
    }

    /**
     * Why a live swap cannot be offered now, or null when it can. Kept beside the decision so the
     * Updates screen can say the reason instead of silently restarting instead.
     */
    static String liveBlockedReason(Context context) {
        if (appContext == null) {
            return null; // register() has not run yet
        }
        deviceKey = deviceKey();
        File dir = trialDir(context);
        if (SwapTrial.trial(dir, deviceKey)) {
            // The process died between writing the trial marker and reporting healthy: count it
            // once, then let the backoff decide. Never leave the device class banned forever.
            SwapTrial.failed(dir, deviceKey, System.currentTimeMillis(), toVersion == null ? "" : toVersion);
            Log.w(TAG, "An unfinished live swap was recorded; live returns after the backoff");
        }
        if (!liveEnabled(context)) {
            return "live apply is off in Settings";
        }
        if (!guard.canAccept()) {
            return "a live swap is already running";
        }
        long wait = SwapTrial.retryAfter(dir, deviceKey, System.currentTimeMillis());
        if (wait > 0) {
            long minutes = (wait + 59_999) / 60_000;
            String version = SwapTrial.failedVersion(dir, deviceKey);
            return "the last live swap" + (version.isEmpty() ? "" : " to " + version)
                    + " did not finish; retrying in " + minutes + " min";
        }
        return null;
    }

    /** The bridge asks before offering a release to the host: is a live swap possible now? */
    static boolean liveReady(Context context) {
        return liveBlockedReason(context) == null;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String deviceKey() {
        String abi = Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "?";
        String s = Build.MANUFACTURER + "|" + Build.MODEL + "|" + Build.HARDWARE + "|" + Build.VERSION.SDK_INT + "|" + abi;
        return Integer.toHexString(s.hashCode());
    }

    static void register(Context context) {
        appContext = context.getApplicationContext();
        try {
            Class<?> hostClass = Class.forName("com.andysu.api.Host");
            Object current = hostClass.getMethod("current").invoke(null);
            if (current == null) {
                return; // not under an AndySU host
            }
            Class<?> api = Class.forName("com.andysu.api.HostApi");
            int level = (Integer) api.getMethod("apiLevel").invoke(current);
            if (level < 2) {
                return; // an older host: no live native swaps, updates apply on the next start
            }
            // Register even when the last trial failed or first use is still ahead: the host
            // reports "this build never registers a handler" and falls back to a restart when we
            // do not, which is what left the tablet applying every update on the next start. The
            // per-swap check in onLiveSwap declines with a reason when live is off or backing off.
            String blocked = liveBlockedReason(context);
            if (blocked != null) {
                Log.i(TAG, "Live engine swap handler registered, but live is waiting: " + blocked);
            }
            Class<?> handlerClass = Class.forName("com.andysu.api.LiveSwapHandler");
            InvocationHandler ih = (proxy, method, args) -> {
                if (method.getName().equals("onLiveSwap")) {
                    return onLiveSwap((File) args[0], (String) args[1], args[2]);
                }
                return method.getName().equals("hashCode") ? System.identityHashCode(proxy)
                        : method.getName().equals("equals") ? proxy == args[0] : "XonoticLiveSwapHandler";
            };
            Object handler = Proxy.newProxyInstance(handlerClass.getClassLoader(), new Class<?>[] { handlerClass }, ih);
            api.getMethod("setLiveSwapHandler", handlerClass).invoke(current, handler);
            host = current;
            Log.i(TAG, "Live engine swap handler registered with the AndySU host (API level " + level + ")");
        } catch (ClassNotFoundException | NoClassDefFoundError t) {
            // A build without an AndySU host (a sideloaded APK, the .perf test
            // package): one line, not a stack trace on every start.
            Log.i(TAG, "No AndySU host; live engine swaps are off");
        } catch (Throwable t) {
            Log.w(TAG, "AndySU live swap not available: " + t);
        }
    }

    static void unregister() {
        Object h = host;
        host = null;
        if (h == null) {
            return;
        }
        try {
            Class<?> api = Class.forName("com.andysu.api.HostApi");
            api.getMethod("setLiveSwapHandler", Class.forName("com.andysu.api.LiveSwapHandler")).invoke(h, (Object) null);
        } catch (Throwable t) {
            // the host is gone or older: nothing to undo
        }
    }

    /** Host thread: main. Returns true to accept (the loader then swaps and reports through callbacks). */
    private static boolean onLiveSwap(File slotDir, String version, Object cb) {
        String blocked = liveBlockedReason(appContext);
        if (blocked != null) {
            Log.i(TAG, "Live swap to " + version + " declined: " + blocked);
            return false;
        }
        if (KeepAliveService.engineBusy) {
            // The host offers the swap when the app comes back to the front. A swap
            // restarts the engine, which would drop the player's server, join or
            // download: decline, and the release applies on the next start instead.
            Log.i(TAG, "Live swap to " + version + " declined: the game is connected, joining or downloading");
            return false;
        }
        File lib = findEngine(slotDir);
        if (lib == null) {
            Log.w(TAG, "Live swap to " + version + " declined: no libmain in " + slotDir);
            return false;
        }
        if (!guard.canAccept()) {
            // A second swap while one is being applied would have two engines swapping under one
            // process: decline it; the release applies on the next start instead.
            Log.w(TAG, "Live swap to " + version + " declined: a swap is already in flight");
            return false;
        }
        // Before the loader may start the swap: the host has not switched its slot yet, so this is
        // still the running release, and a rollback goes back to it.
        fromVersion = AndySUCompat.payloadVersion();
        toVersion = version;
        // The trial marker is on disk (fsynced) before the native side is touched: if the process
        // dies inside the request or the swap, the next start must not offer live apply to this
        // device class again.
        deviceKey = deviceKey();
        File trials = trialDir(appContext);
        if (!SwapTrial.begin(trials, deviceKey)) {
            Log.w(TAG, "Live swap to " + version + " declined: could not record the trial");
            fromVersion = toVersion = null;
            return false;
        }
        try {
            String refused = nativeRequestSwap(lib.getAbsolutePath());
            if (refused != null) {
                Log.w(TAG, "Live swap to " + version + " declined by the loader: " + refused);
                SwapTrial.refused(trials, deviceKey);
                fromVersion = toVersion = null;
                return false;
            }
        } catch (UnsatisfiedLinkError e) {
            Log.w(TAG, "Live swap to " + version + " declined: no loader in this build", e);
            SwapTrial.refused(trials, deviceKey);
            fromVersion = toVersion = null;
            return false;
        }
        callback = cb;
        if (!guard.accept(SystemClock.elapsedRealtime(), WATCHDOG_MS)) {
            // canAccept() said yes a moment ago; the loader is already swapping, so let the host
            // hear about it rather than pretending the request was refused.
            Log.w(TAG, "Live swap to " + version + " accepted without the guard");
        }
        armWatchdog(WATCHDOG_MS);
        Log.i(TAG, "Live swap to " + version + " accepted: " + lib);
        return true;
    }

    private static File findEngine(File slotDir) {
        for (String abi : Build.SUPPORTED_ABIS) {
            File dir = new File(slotDir, "lib/" + abi);
            File exact = new File(dir, "libmain.so");
            if (exact.isFile()) {
                return exact;
            }
            File[] hashed = dir.listFiles((d, name) -> name.startsWith("libmain.") && name.endsWith(".so"));
            if (hashed != null && hashed.length > 0) {
                return hashed[0];
            }
        }
        return null;
    }

    private static void callback(String method, String reason) {
        Object cb = callback;
        if (cb == null) {
            return;
        }
        try {
            Class<?> cls = Class.forName("com.andysu.api.LiveSwapCallback");
            if (reason == null) {
                cls.getMethod(method).invoke(cb);
            } else {
                cls.getMethod(method, String.class).invoke(cb, reason);
            }
        } catch (Throwable t) {
            Log.w(TAG, "Could not report " + method + " to the AndySU host", t);
        }
    }

    /** Native thread (SDL thread or the loader's): the loader's progress, forwarded to the host. */
    static void onEngineEvent(int kind, String detail) {
        switch (kind) {
            case EV_STARTED:
                if (!guard.started(SystemClock.elapsedRealtime(), STARTED_WATCHDOG_MS)) break;
                armWatchdog(STARTED_WATCHDOG_MS);
                callback("started", null);
                break;
            case EV_FAILED:
                if (!guard.failed()) break;
                markDeviceBad(toVersion);
                cancelWatchdog();
                callback("failed", detail.isEmpty() ? "the new engine failed to start" : detail);
                callback = null;
                break;
            case EV_HEALTHY:
                // A rollback's old engine also reports healthy, but the host must not commit a
                // failed trial. A cold start has no live guard state and still reports through.
                boolean trialHealthy = guard.healthy();
                if (guard.state() == LiveSwapGuard.State.FAILED || guard.state() == LiveSwapGuard.State.EXPIRED) break;
                main.removeCallbacks(watchdog);
                if (appContext != null && deviceKey != null) {
                    // Healthy is healthy, including a cold start after a failed live trial: the
                    // device can swap again, and the next failure starts its count from one.
                    SwapTrial.healthy(trialDir(appContext), deviceKey);
                }
                try {
                    Object h = Class.forName("com.andysu.api.Host").getMethod("current").invoke(null);
                    if (h != null) {
                        Class.forName("com.andysu.api.HostApi").getMethod("markHealthy").invoke(h);
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "Could not mark the release healthy", t);
                }
                callback = null;
                break;
            default:
                break;
        }
    }

    /**
     * SDL thread, after the old engine is gone and before the next one starts: the game data
     * (QuakeC progs, menu skin) has to belong to the release that is about to run, unpacked from
     * that release's assets. {@code swapped} 1 = the new release (the host already switched the
     * app's asset loader), 2 = the in-process rollback to the previous one. 0 = go on, anything
     * else abandons the start.
     */
    static int beforeEngineStart(int swapped) {
        Context c = appContext;
        if (swapped < 1 || c == null) {
            return 0;
        }
        GameData data = new GameData(c);
        long t0 = System.currentTimeMillis();
        try {
            if (fromVersion != null) { // driven by an AndySU host: its slot switch has to be done first
                // The host switches its slot (version, asset loader) on the main thread: right after
                // onLiveSwap returns for the swap (same main-thread task), and in onLiveFailed, posted
                // by the loader's failed(reason) just before this call, for the rollback. A task queued
                // now runs after either, so once it has run the assets are the right release's.
                awaitMainLooper();
                String want = swapped == 1 ? toVersion : fromVersion;
                if (!want.equals(AndySUCompat.payloadVersion())) {
                    throw new IOException("the host is on " + AndySUCompat.payloadVersion() + ", not " + want);
                }
            }
            if (swapped == 1 || fromVersion == null) { // fromVersion null: no host, nothing changes between releases
                data.prepare((text, note, percent) -> { });
            } else {
                data.prepareAfterRollback((text, note, percent) -> { }, toVersion);
            }
            Log.i("AndySU-Event", "{\"event\":\"game_data_prepared\",\"pid\":" + Process.myPid() + ",\"swapped\":" + swapped
                    + ",\"release\":\"" + AndySUCompat.payloadVersion() + "\",\"data_version\":\"" + data.bundleVersion()
                    + "\",\"ms\":" + (System.currentTimeMillis() - t0) + "}");
            return 0;
        } catch (IOException | RuntimeException | InterruptedException e) {
            guard.failed();
            markDeviceBad(toVersion);
            cancelWatchdog();
            Log.e(TAG, "Preparing the game data failed (swapped=" + swapped + ")", e);
            Log.i("AndySU-Event", "{\"event\":\"game_data_failed\",\"pid\":" + Process.myPid() + ",\"swapped\":" + swapped + "}");
            if (swapped == 2) {
                // The previous engine must not run on a mix of two releases' data. Make the next
                // start unpack everything and let the host relaunch the app now; if that is not
                // possible the loader stops (return 1) and the next start does it.
                data.invalidateBundle();
                restartThroughHost();
            }
            return 1;
        }
    }

    private static void armWatchdog(long ms) {
        main.removeCallbacks(watchdog);
        main.postDelayed(watchdog, ms);
    }

    private static void cancelWatchdog() {
        main.removeCallbacks(watchdog);
    }

    /** The new engine did not report healthy: report failed, then relaunch on the old slot. */
    private static void onWatchdog() {
        if (!guard.expire(SystemClock.elapsedRealtime())) return;
        Log.w(TAG, "Live swap watchdog expired; falling back to relaunch");
        markDeviceBad(toVersion);
        callback("failed", "the new engine did not report healthy in time");
        callback = null;
        main.postDelayed(() -> {
            if (guard.state() != LiveSwapGuard.State.HEALTHY) restartThroughHost();
        }, 1_500);
    }

    private static void markDeviceBad(String version) {
        if (appContext != null && deviceKey != null) {
            // fsynced, with the release: the kill that ends this trial must not also lose the
            // fact that it failed, and the next start retries after the backoff.
            SwapTrial.failed(trialDir(appContext), deviceKey, System.currentTimeMillis(), version);
        }
    }

    /** Returns once the main thread has run everything queued before this call. */
    private static void awaitMainLooper() throws InterruptedException, IOException {
        CountDownLatch done = new CountDownLatch(1);
        new Handler(Looper.getMainLooper()).post(done::countDown);
        if (!done.await(10, TimeUnit.SECONDS)) {
            throw new IOException("the main thread did not process the host's rollback in time");
        }
    }

    /** {@code HostApi.updates().restartNow()}: the host relaunches the app (does not return). */
    private static void restartThroughHost() {
        try {
            Object h = host;
            if (h == null) {
                return;
            }
            Object updates = Class.forName("com.andysu.api.HostApi").getMethod("updates").invoke(h);
            Class.forName("com.andysu.api.UpdateClient").getMethod("restartNow").invoke(updates);
        } catch (Throwable t) {
            Log.w(TAG, "Could not ask the AndySU host to relaunch", t);
        }
    }
}
