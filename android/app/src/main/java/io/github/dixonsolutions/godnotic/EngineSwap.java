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
    /**
     * The swap in flight (or the last one) was started by the app for a release the host could not
     * swap itself (its Java code changed): no host callback, the host stays on its slot, and the
     * new engine runs on the staged slot's data until the next start promotes that slot.
     */
    private static volatile boolean detached;
    /** Detached: the staged slot whose data the new engine runs on. */
    private static volatile File detachedBundle;
    /** Detached: what the data override was before this swap, restored by an in-process rollback. */
    private static volatile String previousLive;
    private static volatile File previousBundle;
    /**
     * The release whose engine and data this process runs after a detached swap, "" when none. It
     * is process-wide: the next start runs whatever the host promotes.
     */
    private static volatile String liveRelease = "";
    /** Why the last offer (host or app) was declined, for the Updates screen. */
    private static volatile String lastDecline;

    private EngineSwap() {}

    /** The release this process already swapped its engine and data to, "" when none. */
    static String liveRelease() {
        return liveRelease;
    }

    /** An engine swap of this process was accepted and has not reported healthy or failed yet. */
    static boolean swapInFlight() {
        LiveSwapGuard.State st = guard.state();
        return st == LiveSwapGuard.State.ACCEPTED || st == LiveSwapGuard.State.STARTED;
    }

    /** Whether the engine (and its loader) runs in this process: a swap needs one to replace. */
    static boolean engineRunning() {
        return LiveApply.engineUp(appContext != null, XonoticActivity.running, ProcessState.engineStarted());
    }

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
        if (!guard.canAccept()) {
            // This process is in the middle of a swap: its marker is not an old failure.
            return "a live swap is already running";
        }
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

    /** Why a swap to {@code version} from {@code slotDir} cannot start now, or null. */
    private static String preflight(File slotDir, String version) {
        String blocked = liveBlockedReason(appContext);
        if (blocked != null) return blocked;
        if (KeepAliveService.engineBusy) {
            // The host offers the swap when the app comes back to the front. A swap
            // restarts the engine, which would drop the player's server, join or
            // download: decline, and the release applies on the next start instead.
            return "the game is connected, joining or downloading";
        }
        if (!AndySUBridge.applySafe) {
            // A swap restarts the engine: only from the menu, idle for a few seconds. The bridge
            // offers the release again once that holds.
            return "a match may be running";
        }
        if (findEngine(slotDir) == null) return "no engine library in " + slotDir;
        if (!guard.canAccept()) {
            // A second swap while one is being applied would have two engines swapping under one
            // process: decline it; the release applies on the next start instead.
            return "a swap is already in flight";
        }
        return null;
    }

    private static boolean decline(String version, String reason) {
        lastDecline = reason;
        Log.i(TAG, "Live swap to " + version + " declined: " + reason);
        return false;
    }

    /** Host thread: main. Returns true to accept (the loader then swaps and reports through callbacks). */
    private static synchronized boolean onLiveSwap(File slotDir, String version, Object cb) {
        String blocked = preflight(slotDir, version);
        if (blocked != null) return decline(version, blocked);
        if (!liveRelease.isEmpty()) {
            // This process already runs a release's engine that the host has not switched to: the
            // host's slot switch would hand that engine the host's data. The next start applies it.
            return decline(version, "this process already runs " + liveRelease + "'s engine");
        }
        File lib = findEngine(slotDir);
        detached = false;
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

    /**
     * The app's own live swap of a staged release the host cannot swap (its Java code changed):
     * the engine and the game data of {@code slotDir} replace the running ones in this process; the
     * host keeps its slot, and the release's Java code takes over when the host promotes the slot
     * at the next start. Returns null when the loader accepted the swap, else why not. Never
     * restarts: a refusal leaves the running engine as it was.
     */
    static synchronized String swapEngine(File slotDir, String version) {
        if (!engineRunning()) return "the game has not started yet";
        String blocked = preflight(slotDir, version);
        if (blocked != null) {
            decline(version, blocked);
            return blocked;
        }
        File bundle = new File(slotDir, LiveApply.DATA_BUNDLE);
        if (!bundle.isFile()) {
            decline(version, "no game data in " + slotDir);
            return lastDecline;
        }
        File lib = findEngine(slotDir);
        deviceKey = deviceKey();
        File trials = trialDir(appContext);
        if (!SwapTrial.begin(trials, deviceKey)) {
            decline(version, "could not record the trial");
            return lastDecline;
        }
        String was = liveRelease;
        detached = true;
        detachedBundle = bundle;
        previousLive = was.isEmpty() ? null : was;
        previousBundle = previousLive == null ? null : detachedBundle(previousLive);
        fromVersion = was.isEmpty() ? AndySUCompat.payloadVersion() : was;
        toVersion = version;
        callback = null;
        try {
            String refused = nativeRequestSwap(lib.getAbsolutePath());
            if (refused != null) {
                SwapTrial.refused(trials, deviceKey);
                detached = false;
                fromVersion = toVersion = null;
                decline(version, "the loader refused it: " + refused);
                return lastDecline;
            }
        } catch (UnsatisfiedLinkError e) {
            SwapTrial.refused(trials, deviceKey);
            detached = false;
            fromVersion = toVersion = null;
            decline(version, "no engine loader in this build");
            return lastDecline;
        }
        guard.accept(SystemClock.elapsedRealtime(), WATCHDOG_MS);
        armWatchdog(WATCHDOG_MS);
        lastDecline = null;
        Log.i(TAG, "Live engine and data swap to " + version + " accepted (the Java code follows at the next start): " + lib);
        return null;
    }

    /** The data bundle of a release slot next to {@code detachedBundle}'s, or null. */
    private static File detachedBundle(String version) {
        File current = detachedBundle;
        if (current == null) return null;
        File slots = current.getParentFile() == null ? null : current.getParentFile().getParentFile();
        slots = slots == null ? null : slots.getParentFile();
        if (slots == null) return null;
        File b = new File(new File(slots, version), LiveApply.DATA_BUNDLE);
        return b.isFile() ? b : null;
    }

    /** The host's own in-process apply is reachable without its relaunch fallback. */
    static boolean hostLiveEntry() {
        if (AndySUCompat.has("applyLive")) return true; // the public API of newer hosts
        try {
            Class<?> c = Class.forName("com.andysu.host.AndySU");
            c.getField("INSTANCE");
            c.getMethod("applyLive");
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Ask the host for its live swap of a native-only release, through the entry that cannot fall
     * back to a relaunch. {@code UpdateClient.applyNow()} calls the same {@code applyLive()} but
     * relaunches the process when it returns false, which is how a declined swap used to close the
     * app. Returns null when the host swapped (our handler accepted), else why not.
     */
    static String hostApplyLive() {
        if (AndySUCompat.has("applyLive")) {
            // A newer host's public entry: never restarts, answers with what it did.
            lastDecline = null;
            try {
                String result = AndySUCompat.applyLive();
                if ("swapped".equals(result)) return null;
                String why = lastDecline;
                return why != null ? why : "the host did not swap it (" + result + ")";
            } catch (ReflectiveOperationException | RuntimeException e) {
                return "the host's live apply failed: " + e;
            }
        }
        // Host API 3 (the installed hosts): the same applyLive() that applyNow() calls first, but
        // reached directly, so a false answer cannot turn into applyNow()'s relaunch.
        final Object instance;
        final Method apply;
        try {
            Class<?> c = Class.forName("com.andysu.host.AndySU");
            instance = c.getField("INSTANCE").get(null);
            apply = c.getMethod("applyLive");
        } catch (Throwable t) {
            return "this AndySU host has no live apply entry";
        }
        lastDecline = null;
        final Object[] result = new Object[1];
        CountDownLatch done = new CountDownLatch(1);
        main.post(() -> {
            try {
                result[0] = apply.invoke(instance);
            } catch (Throwable t) {
                result[0] = t instanceof java.lang.reflect.InvocationTargetException && t.getCause() != null ? t.getCause() : t;
            } finally {
                done.countDown();
            }
        });
        try {
            if (!done.await(10, TimeUnit.SECONDS)) return "the host did not answer";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "interrupted";
        }
        if (Boolean.TRUE.equals(result[0])) return null;
        if (result[0] instanceof Throwable) return "the host's live apply failed: " + ((Throwable) result[0]).getMessage();
        String why = lastDecline;
        return why != null ? why : "the host declined it (a release is still being confirmed, or it is not native-only)";
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
                if (detached) liveRelease = previousLive == null ? "" : previousLive;
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
                    // Detached: the host's slot is not the release that just proved healthy; the
                    // host confirms that one itself after the next start promotes it.
                    Object h = detached && trialHealthy ? null
                            : Class.forName("com.andysu.api.Host").getMethod("current").invoke(null);
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
        if (detached) {
            return beforeDetachedStart(data, swapped, t0);
        }
        try {
            // A host-driven swap reads the host's (switched) assets, never a detached override.
            GameData.clearLiveBundle();
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

    /**
     * Detached swap: the host has not switched its slot, so the new release's data comes from the
     * staged slot's file ({@link GameData#useLiveBundle}); a rollback puts back whatever the data
     * was before this swap (the host's slot, or an earlier detached swap's release).
     */
    private static int beforeDetachedStart(GameData data, int swapped, long t0) {
        try {
            if (swapped == 1) {
                GameData.useLiveBundle(toVersion, detachedBundle);
                data.prepare((text, note, percent) -> { });
                liveRelease = toVersion;
            } else {
                if (previousLive != null && previousBundle != null) GameData.useLiveBundle(previousLive, previousBundle);
                else GameData.clearLiveBundle();
                liveRelease = previousLive == null ? "" : previousLive;
                data.prepareAfterRollback((text, note, percent) -> { }, toVersion);
            }
            Log.i("AndySU-Event", "{\"event\":\"game_data_prepared\",\"pid\":" + Process.myPid() + ",\"swapped\":" + swapped
                    + ",\"detached\":true,\"release\":\"" + (swapped == 1 ? toVersion : fromVersion) + "\",\"data_version\":\""
                    + data.bundleVersion() + "\",\"ms\":" + (System.currentTimeMillis() - t0) + "}");
            return 0;
        } catch (IOException | RuntimeException e) {
            guard.failed();
            markDeviceBad(toVersion);
            cancelWatchdog();
            Log.e(TAG, "Preparing the game data failed (detached, swapped=" + swapped + ")", e);
            Log.i("AndySU-Event", "{\"event\":\"game_data_failed\",\"pid\":" + Process.myPid() + ",\"swapped\":" + swapped
                    + ",\"detached\":true}");
            if (swapped == 1) {
                // The loader rolls back to the previous engine, which calls back with swapped=2.
                liveRelease = previousLive == null ? "" : previousLive;
            } else {
                // The previous engine must not run on a mix of two releases' data: the next start
                // unpacks everything again. The loader stops this engine (return 1).
                GameData.clearLiveBundle();
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
