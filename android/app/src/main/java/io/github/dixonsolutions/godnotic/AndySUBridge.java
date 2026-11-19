package io.github.dixonsolutions.godnotic;

import android.app.ActivityManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * File bridge between the Updates screen (QuakeC describes it; the native interface or the
 * QuakeC menu draws it) and AndySU's payload updater.
 *
 * <p>The menu writes one request token into {@code touch/andysu-update-request.txt}; this class
 * serves it and publishes {@code touch/andysu-update-status.txt} once a second (the format is
 * documented in qcsrc/menu/xonotic/touch_update_util.qh). The decisions live in
 * {@link AndySUState}; this class only gathers what the host, the network and the clock say.
 * Every host call is by reflection and feature-detected: an old frozen host without a method
 * simply does not get the switch that needs it.
 */
final class AndySUBridge {
    private static final String TAG = "Godnotic";

    static final String REQUEST_CHECK = "check";
    static final String REQUEST_APPLY = "apply";
    /** "Download now": fetch the release a check found, on this connection. */
    static final String REQUEST_DOWNLOAD = "download";
    static final String REQUEST_AUTO_ON = "auto-on";
    static final String REQUEST_AUTO_OFF = "auto-off";
    static final String REQUEST_WIFI_ON = "wifi-on";
    static final String REQUEST_WIFI_OFF = "wifi-off";
    static final String REQUEST_LIVE_ON = "live-on";
    static final String REQUEST_LIVE_OFF = "live-off";

    private static final String PREFS = "andysu_updates";
    private static final String PREF_AUTO = "auto";
    private static final String PREF_WIFI_ONLY = "wifi_only";
    private static final String PREF_CHECKED_AT = "checked_at";
    private static final String PREF_CHECKED_RESULT = "checked_result";

    /** A check the player asked for gives its manual mode back after this long at the latest. */
    private static final long MANUAL_SETTLE_MS = 20_000;
    private static final long MANUAL_MAX_MS = 30 * 60_000;

    private final Context context;
    private final File statusFile;
    private final File requestFile;
    private final File blobDir;
    private final File hostRoot;
    private final SharedPreferences prefs;
    private final AndySUState.Progress progress = new AndySUState.Progress();
    private final boolean listening;

    private String requestError = "";
    private String lastRecord = "";
    private String lastStatusJson = "";
    private String noticeText = "";
    private String channel;
    private String stagedForApply = "";
    private String stateForApply = "idle";
    private String applyPolicy;
    private long lastBusyAt;
    /** The last poll found the game safe to restart or swap (menu, idle); EngineSwap reads it. */
    static volatile boolean applySafe;
    /** The player tapped Apply now while a match ran: apply from the menu. */
    private boolean applyWanted;
    /** The release whose automatic apply was refused or failed: not announced again. */
    private String autoBlocked = "";
    /** The staged release changes only native code and content: the host can swap it live. */
    private boolean pendingNativeLive;
    private String announced = "";
    private long announcedAt;
    private final Handler main = new Handler(Looper.getMainLooper());

    /** The mode a player's own check runs in, until it settles; null when none is running. */
    private String manualMode;
    private long manualAt;
    /** The host has finished the player's check (it went busy, then ended). */
    private volatile boolean manualDone;
    /** A check is under way (the host said "checking"); its end is the last-checked time. */
    private volatile boolean checkRunning;

    AndySUBridge(Context context, File baseDir) {
        this.context = context.getApplicationContext();
        File touch = new File(GameData.engineWriteDir(baseDir), "touch");
        statusFile = new File(touch, "andysu-update-status.txt");
        requestFile = new File(touch, "andysu-update-request.txt");
        // AndySU's root in both of its modes: <data>/no_backup/andysu
        hostRoot = new File(new File(this.context.getApplicationInfo().dataDir, "no_backup"), "andysu");
        blobDir = new File(hostRoot, "blobs/sha256");
        prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        listening = AndySUCompat.present() && AndySUCompat.setStatusListener(this::onStatus);
    }

    String takeRequest() {
        if (!requestFile.isFile()) return null;
        String token = null;
        try {
            List<String> lines = Files.readAllLines(requestFile.toPath(), StandardCharsets.UTF_8);
            if (!lines.isEmpty()) token = lines.get(0).trim();
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "Could not read AndySU update request", e);
        }
        if (!requestFile.delete()) Log.w(TAG, "Could not clear " + requestFile);
        return token == null || token.isEmpty() ? null : token;
    }

    synchronized void serve(String request) {
        try {
            switch (request) {
                case REQUEST_CHECK:
                case REQUEST_DOWNLOAD: {
                    String manual = hasMode() ? AndySUState.manualMode(auto(), wifiOnly(), metered(),
                            REQUEST_DOWNLOAD.equals(request)) : null;
                    if ("auto".equals(manual) && !"next_start".equals(applyPolicy))
                        manual = "download_only";
                    if (manual != null) {
                        AndySUCompat.setMode(manual);
                        manualMode = manual;
                        manualAt = System.currentTimeMillis();
                        manualDone = false;
                    }
                    AndySUCompat.checkNow();
                    break;
                }
                case REQUEST_APPLY:
                    if ("shell-update".equals(stateForApply)) {
                        AndySUCompat.applyNow();
                        break;
                    }
                    if (!applySafe) {
                        // A match is running or loading: replacing the process would kill it.
                        applyWanted = true;
                        requestError = "Update ready. It will apply when you are back in the menu.";
                        publish();
                        return;
                    }
                    applyWanted = true;
                    // Apply now is live whenever the release can be: no package replacement, no
                    // restart, the player stays in the menu. Restart is only the fallback.
                    if (AndySUState.shouldApplyLive("staged", false, applyPolicy, pendingNativeLive)
                            && EngineSwap.liveReady(context)) {
                        try {
                            AndySUCompat.applyNow();
                        } catch (ReflectiveOperationException | RuntimeException e) {
                            requestError = "Could not apply the update live: "
                                    + AndySUState.oneLine(e.getMessage());
                        }
                        publish();
                        return;
                    }
                    if (!prepareRestart()) { publish(); return; }
                    // Quiet this process and mark it replacing *before* the host is asked: the
                    // relaunch does not return, so anything after the call may never run.
                    ReplaceSequence.apply(
                            () -> ReplaceGuard.beforeReplace(context),
                            () -> ReplaceGuard.beginReplace(context),
                            () -> {
                                try {
                                    if (AndySUCompat.has("restartToApply")) {
                                        String result = AndySUCompat.restartToApply();
                                        if (!"restarting".equals(result)) {
                                            throw new IllegalStateException(AndySUState.applyRefusedMessage());
                                        }
                                    } else if (AndySUCompat.has("restartNow")) {
                                        AndySUCompat.restartNow();
                                    } else {
                                        AndySUCompat.applyNow();
                                    }
                                } catch (ReflectiveOperationException e) {
                                    throw new IllegalStateException(e);
                                }
                            });
                    // restartNow should terminate the process; if it returns, report it.
                    requestError = "AndySU did not restart to apply the update.";
                    publish();
                    return;
                case REQUEST_AUTO_ON:
                case REQUEST_AUTO_OFF:
                    prefs.edit().putBoolean(PREF_AUTO, REQUEST_AUTO_ON.equals(request)).apply();
                    break;
                case REQUEST_WIFI_ON:
                case REQUEST_WIFI_OFF:
                    prefs.edit().putBoolean(PREF_WIFI_ONLY, REQUEST_WIFI_ON.equals(request)).apply();
                    break;
                case REQUEST_LIVE_ON:
                    EngineSwap.setLiveEnabled(context, true);
                    AndySUCompat.setApplyPolicy("live");
                    break;
                case REQUEST_LIVE_OFF:
                    EngineSwap.setLiveEnabled(context, false);
                    AndySUCompat.setApplyPolicy("next_start");
                    break;
                default:
                    return;
            }
            requestError = "";
        } catch (ReflectiveOperationException | RuntimeException e) {
            requestError = "Could not contact the AndySU updater: " + AndySUState.oneLine(e.getMessage());
            Log.w(TAG, requestError, e);
        }
        publish();
    }

    /** Gather, decide, write. Called once a second by the update service thread. */
    synchronized void publish() {
        if (ReplaceGuard.isReplacing()) {
            // The package is being swapped under this process: publishing a status into the old
            // payload (or acting on it) is exactly the work that must not happen now.
            return;
        }
        AndySUState.Record r = new AndySUState.Record();
        r.message = requestError;
        r.installed = AndySUState.oneLine(AndySUCompat.payloadVersion());
        if (!AndySUCompat.present()) {
            r.message = "Payload updates are available only in an AndySU-enabled install.";
            write(r);
            notice(null);
            return;
        }
        try {
            int level = AndySUCompat.apiLevel();
            String statusJson = AndySUCompat.updateStatus();
            if (statusJson == null) statusJson = "{}";
            JSONObject status = new JSONObject(statusJson);
            String state = AndySUState.normalize(status.optString("state", "idle"));
            String release = status.optString("release", status.optString("id", ""));
            JSONObject core = confirmStableTrial(coreState());
            String pending = pendingRelease(core, r.installed);
            if (core != null) syncNextStartRequest(pending, auto());
            String problem = "apply_refused".equals(state) ? AndySUState.applyRefusedMessage()
                    : applyProblem(core, pending);
            if (requestError.startsWith("Still confirming the current version")) requestError = "";
            r.message = requestError;
            if (!problem.isEmpty()) r.message = problem;
            // The host's status keeps saying "staged" after that release has committed.
            if ("staged".equals(state) && !release.isEmpty() && release.equals(r.installed)) state = "uptodate";
            // After a restart the status starts over at idle; the core still has the staged release.
            if ("idle".equals(state) && !pending.isEmpty()) {
                state = "staged";
                release = pending;
            }
            String staged = "staged".equals(state) ? (release.isEmpty() ? pending : release) : pending;

            if (!listening && !statusJson.equals(lastStatusJson)) onStatus(statusJson);
            lastStatusJson = statusJson;

            boolean modeControl = hasMode();
            boolean metered = metered();
            boolean auto = auto();
            boolean wifiOnly = wifiOnly();
            String mode = modeControl ? AndySUCompat.mode() : "";
            Boolean live = null;
            String policy = null;
            if (AndySUCompat.has("applyPolicy")) {
                try {
                    policy = AndySUCompat.applyPolicy();
                    live = "live".equals(policy);
                } catch (ReflectiveOperationException | RuntimeException e) {
                    live = null;
                    policy = "unknown";
                }
            }
            JSONObject pendingSlot = core == null ? null : core.optJSONObject("pending");
            pendingNativeLive = pendingSlot != null && pendingSlot.optBoolean("native_live");
            boolean downloading = "downloading".equals(state);
            if (modeControl) mode = steerMode(mode, state, auto, wifiOnly, metered,
                    !staged.isEmpty(), downloading, policy);
            stagedForApply = staged;
            stateForApply = !staged.isEmpty() ? "staged" : state;
            applyPolicy = policy;
            ProcessState.phase(context, stateForApply);

            if ("downloading".equals(state)) {
                Map<String, Long> parts = parts();
                if (!progress.started()) progress.start(status.optLong("download_bytes", 0), parts);
                progress.sample(System.currentTimeMillis(), parts, key -> {
                    File blob = new File(blobDir, key);
                    return blob.isFile() ? blob.length() : -1;
                });
            } else {
                progress.reset();
            }

            r.state = !staged.isEmpty() && !r.message.isEmpty() ? "error" : state;
            r.latest = "available".equals(state) || "downloading".equals(state) || "shell-update".equals(state)
                    ? release : staged;
            r.percent = progress.percent();
            if (r.message.isEmpty()) {
                r.message = AndySUState.friendlyMessage(
                        status.optString("message", status.optString("reason", status.optString("code", ""))));
            }
            if ("shell-update".equals(state) && r.message.isEmpty()) {
                r.message = "The host APK needs an update before this payload can run.";
            }
            r.auto = modeControl ? auto : "auto".equals(mode);
            r.mode = modeControl ? mode : "unknown";
            r.put("staged", staged)
                    .put("available", "available".equals(state) ? release : "")
                    .flag("auto", modeControl ? auto : null)
                    .flag("wifi", modeControl ? wifiOnly : null)
                    .flag("live", live)
                    .put("live_reason", live != null && live ? liveReason() : "")
                    .put("net", metered ? "metered" : (Net.kind(context) == Net.Kind.OFFLINE ? "offline" : "unmetered"))
                    .put("hold", AndySUState.hold(state, auto, wifiOnly, metered))
                    .put("api", level)
                    .put("channel", channel())
                    .put("abi", abi());
            long size = status.optLong("download_bytes", 0);
            if (size > 0) r.put("size", size);
            if (progress.started()) {
                r.put("done", progress.done).put("total", progress.total).put("speed", progress.speed)
                        .put("eta", progress.eta);
            }
            long checkedAt = prefs.getLong(PREF_CHECKED_AT, 0);
            if (checkedAt > 0) {
                r.put("checked_min", Math.max(0, (System.currentTimeMillis() - checkedAt) / 60_000))
                        .put("checked_result", prefs.getString(PREF_CHECKED_RESULT, ""));
            }
            notice("downloading".equals(state) ? release : null);
        } catch (Exception e) {
            stagedForApply = "";
            stateForApply = "idle";
            r.state = "incompatible";
            r.message = "This AndySU host does not expose the payload update API.";
            Log.i(TAG, r.message, e);
            notice(null);
        }
        write(r);
    }

    /**
     * The engine is connected, joining or downloading (or back to idle). While it is busy the
     * host's apply policy is next_start, so a release that stages mid-match waits; the moment the
     * player is back in the menu the policy returns to live and autoApply swaps it in-process.
     */
    void onEngineBusy(boolean busy) {
        if (!AndySUCompat.present() || !AndySUCompat.has("applyPolicy")) return;
        try {
            long now = System.currentTimeMillis();
            if (busy) lastBusyAt = now;
            String want = AndySUState.desiredPolicy(EngineSwap.liveEnabled(context), busy, now, lastBusyAt);
            String current = applyPolicy != null && !"unknown".equals(applyPolicy)
                    ? applyPolicy : AndySUCompat.applyPolicy();
            if (!want.equals(current)) {
                AndySUCompat.setApplyPolicy(want);
                applyPolicy = want;
                Log.i(TAG, "AndySU apply policy " + current + " -> " + want);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            Log.w(TAG, "Could not steer the AndySU apply policy", e);
        }
    }

    private static boolean hasStagedVersion(String version) {
        return version != null && !version.isEmpty();
    }

    /** The reason live is waiting, for Settings > Updates; "" when live is available. */
    private String liveReason() {
        String blocked = EngineSwap.liveBlockedReason(context);
        return blocked == null ? "" : "Live apply is waiting: " + blocked + ".";
    }

    /** What the last publish decided a restart would apply, for the boot screen's guard. */
    String stateForApply() {
        return stateForApply;
    }

    /** The host's apply policy as last read ("live", "next_start", null when unknown). */
    String applyPolicy() {
        return applyPolicy;
    }

    /** Called after publish on each poll, only while the player can see the notice. */
    synchronized void autoApply(boolean inMatch, boolean visible) {
        String version = stagedForApply;
        long nowMs = System.currentTimeMillis();
        if (inMatch) {
            lastBusyAt = nowMs;
            onEngineBusy(true); // hold the host's policy at next_start so it cannot swap either
        }
        String stage = ProcessState.stage();
        // The boot screen has no engine: its gate file does not exist and it is not a match.
        boolean boot = "boot".equals(stage);
        boolean safe = AndySUState.safeToApplyNow(stage, KeepAliveService.engineBusy,
                boot || !inMatch ? "safe" : "busy", visible,
                lastBusyAt == 0 ? Long.MAX_VALUE : nowMs - lastBusyAt);
        applySafe = safe;
        if (!hasStagedVersion(version)) applyWanted = false;
        boolean asked = applyWanted;
        if (!safe || !visible || !AndySUState.mayApplyUnasked(auto(), asked)
                || !AndySUState.shouldAutoApply(stateForApply, inMatch, applyPolicy)) {
            announced = "";
            return;
        }
        if (version.isEmpty()) {
            // A staged release without a version cannot be told apart from a stale status.
            announced = "";
            return;
        }
        if (!applyProblem(coreState(), version).isEmpty()) {
            announced = "";
            return;
        }
        // A release whose automatic apply was refused or failed is not announced again (the toast
        // used to repeat every poll); the Updates screen carries the reason and Apply now remains.
        if (!AndySUState.mayAnnounce(version, autoBlocked)) return;
        final String noticeVersion = version;
        long now = SystemClock.elapsedRealtime();
        // Live first: a native/content release is swapped in the running process, and only a
        // release that cannot be swapped restarts (with the reason on the Updates screen).
        if (AndySUState.shouldApplyLive(stateForApply, inMatch, applyPolicy, pendingNativeLive)
                && EngineSwap.liveReady(context)) {
            if (version.equals(announced) && now - announcedAt < 60_000) return;
            announced = version;
            announcedAt = now;
            main.post(() -> Toast.makeText(context, "Applying " + noticeVersion + " live…",
                    Toast.LENGTH_SHORT).show());
            try {
                AndySUCompat.applyNow();
            } catch (ReflectiveOperationException | RuntimeException e) {
                announced = "";
                autoBlocked = noticeVersion;
                requestError = "Could not apply the update live: " + AndySUState.oneLine(e.getMessage());
                Log.w(TAG, requestError, e);
            }
            return;
        }
        if (!version.equals(announced)) {
            announced = version;
            announcedAt = now;
            main.post(() -> Toast.makeText(context, "?".equals(noticeVersion) ? "Updating — restarting…"
                    : "Updating to " + noticeVersion + " — restarting…",
                    Toast.LENGTH_SHORT).show());
        } else if (now - announcedAt >= 1000) {
            android.content.SharedPreferences prefs =
                    context.getSharedPreferences("andysu_apply", Context.MODE_PRIVATE);
            long wall = System.currentTimeMillis();
            if (!AndySUState.mayAutoRestart(noticeVersion, prefs.getString("version", ""),
                    prefs.getLong("at", 0L), wall)) {
                // Avoid a restart loop; publish the failure from the persisted attempt.
                announced = "";
                autoBlocked = noticeVersion;
                stagedForApply = "";
                stateForApply = "idle";
                Log.w(TAG, "Not restarting again for " + noticeVersion);
                return;
            }
            // Do not repeatedly ask if the host rejects or returns from the relaunch.
            announced = "";
            autoBlocked = noticeVersion;
            stagedForApply = "";
            stateForApply = "idle";
            if (!prepareRestart()) {
                publish();
                return;
            }
            // Quiet this process and mark it replacing *before* the host is asked to relaunch:
            // the relaunch does not return, so the work after the call may never run.
            ReplaceSequence.apply(
                    () -> ReplaceGuard.beforeReplace(context),
                    () -> ReplaceGuard.beginReplace(context),
                    () -> {
                        try {
                            if (AndySUCompat.has("restartToApply")) {
                                String result = AndySUCompat.restartToApply();
                                if (!"restarting".equals(result)) {
                                    throw new IllegalStateException(AndySUState.applyRefusedMessage());
                                }
                            } else if (AndySUCompat.has("restartNow")) {
                                AndySUCompat.restartNow();
                            } else {
                                AndySUCompat.applyNow();
                            }
                        } catch (ReflectiveOperationException | RuntimeException e) {
                            requestError = "Could not restart to apply the update: "
                                    + AndySUState.oneLine(e.getMessage());
                            Log.w(TAG, requestError, e);
                        }
                    });
            // A host that returns or throws did not relaunch; keep Settings available.
            if (requestError.isEmpty()) requestError = "AndySU did not restart to apply the update.";
            publish();
        }
    }

    /**
     * Keep the host in the mode the switches ask for. A check the player started keeps its own
     * mode until the host is done with it (or has had MANUAL_SETTLE_MS to begin). Moving to
     * check_only in the middle of an automatic download stops it: that is what Wi-Fi only asks
     * when the connection turns metered.
     */
    private String steerMode(String mode, String state, boolean auto, boolean wifiOnly, boolean metered,
                             boolean staged, boolean downloading, String policy) throws ReflectiveOperationException {
        long now = System.currentTimeMillis();
        if (manualMode != null && !AndySUState.busy(state)
                && (manualDone || now - manualAt > MANUAL_SETTLE_MS)) {
            manualMode = null;
        }
        if (manualMode != null && now - manualAt > MANUAL_MAX_MS) manualMode = null;
        String want = manualMode != null ? manualMode
                : AndySUState.targetMode(auto, wifiOnly, metered, staged, true, downloading);
        // Auto stays auto. Steering to download_only stopped the host's onStaged() from ever
        // offering the release, which is why every update waited for the next start; the busy
        // match case is handled by the apply policy (onEngineBusy), not by disabling staging.
        if (want.equals(mode)) return mode;
        AndySUCompat.setMode(want);
        Log.i(TAG, "AndySU update mode " + mode + " -> " + want);
        return want;
    }

    /**
     * From the host (main thread) for every status it sets; from publish() on a change when the
     * host takes no listener. Only the end of a check counts as one: not the "up_to_date" a live
     * swap's commit sets, not an old status read before checkNow() got going.
     */
    private void onStatus(String statusJson) {
        try {
            String state = AndySUState.normalize(new JSONObject(statusJson).optString("state", ""));
            if (AndySUState.busy(state)) {
                checkRunning = true;
                return;
            }
            // without a listener a quick check can pass between two polls: any end counts
            boolean ended = AndySUState.endsCheck(state) && (checkRunning || !listening || "deferred".equals(state));
            checkRunning = false;
            if (!ended) return;
            manualDone = true;
            prefs.edit().putLong(PREF_CHECKED_AT, System.currentTimeMillis())
                    .putString(PREF_CHECKED_RESULT, state).apply();
        } catch (Exception e) {
            Log.w(TAG, "Unreadable AndySU status " + statusJson, e);
        }
    }

    private boolean hasMode() {
        return AndySUCompat.apiLevel() >= 3 && AndySUCompat.has("setMode", String.class);
    }

    /** Automatic updates; on first use, whatever the host was set to (an older menu's switch). */
    private boolean auto() {
        if (!prefs.contains(PREF_AUTO)) {
            boolean on = true;
            try {
                String mode = AndySUCompat.mode();
                on = !"off".equals(mode) && !"check_only".equals(mode);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // keep the default
            }
            prefs.edit().putBoolean(PREF_AUTO, on).apply();
        }
        return prefs.getBoolean(PREF_AUTO, true);
    }

    /** On by default: like the game data, updates wait for Wi-Fi unless the player says otherwise. */
    private boolean wifiOnly() {
        return prefs.getBoolean(PREF_WIFI_ONLY, true);
    }

    private boolean metered() {
        Net.Kind kind = Net.kind(context);
        return kind != Net.Kind.UNMETERED && kind != Net.Kind.OFFLINE;
    }

    /**
     * Whether the player has the game on screen. Asked of the system each time rather than
     * counted from activity callbacks (which never reached the bridge on the test tablet): the
     * update service polls once a second, so leaving the game settles the background mode
     * within a second, before Android freezes a cached process.
     */
    private static boolean foreground() {
        ActivityManager.RunningAppProcessInfo info = new ActivityManager.RunningAppProcessInfo();
        try {
            ActivityManager.getMyMemoryState(info);
        } catch (RuntimeException e) {
            return true;
        }
        return info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
    }

    private static JSONObject coreState() {
        try {
            String json = AndySUCompat.coreState();
            return json == null ? null : new JSONObject(json).optJSONObject("ok");
        } catch (Exception e) {
            return null;
        }
    }

    private static String pendingRelease(JSONObject state, String installed) {
        JSONObject pending = state == null ? null : state.optJSONObject("pending");
        String id = pending == null ? "" : pending.optString("id", "");
        return id.equals(installed) ? "" : id;
    }

    /** Host API 4 queues this; the API 3 fallback is the private file the host reads at launch. */
    private void syncNextStartRequest(String pending, boolean auto) throws IOException {
        File request = new File(hostRoot, "apply_pending");
        boolean hostQueue = AndySUCompat.has("requestApplyAtNextStart");
        if (!auto || pending.isEmpty()) {
            // Do not remove another request: only remove the one Godnotic wrote.
            String ours = prefs.getString("next_start_request", "");
            if (hostQueue) {
                if (!ours.isEmpty()) {
                    try {
                        AndySUCompat.cancelApplyAtNextStart();
                    } catch (ReflectiveOperationException e) {
                        throw new IOException("Could not cancel the update request", e);
                    }
                }
            } else if (!ours.isEmpty() && request.isFile()
                    && new String(Files.readAllBytes(request.toPath()), StandardCharsets.UTF_8).trim().equals(ours)) {
                if (!request.delete()) throw new IOException("Could not clear the update request");
            }
            prefs.edit().remove("next_start_request").apply();
            return;
        }
        if (pending.equals(prefs.getString("next_start_request", ""))) return;
        if (hostQueue) {
            try {
                String result = AndySUCompat.requestApplyAtNextStart();
                if (!"queued".equals(result) && !"no_pending".equals(result)) {
                    throw new IOException("Could not request the update at next start: " + result);
                }
            } catch (ReflectiveOperationException e) {
                throw new IOException("Could not request the update at next start", e);
            }
            prefs.edit().putString("next_start_request", pending).commit();
            return;
        }
        if (request.isFile() && new String(Files.readAllBytes(request.toPath()), StandardCharsets.UTF_8)
                .trim().equals(pending)) return;
        File temp = new File(hostRoot, "godnotic_apply_pending.tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write(pending.getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
        if (!temp.renameTo(request)) throw new IOException("Could not request the update at next start");
        prefs.edit().putString("next_start_request", pending).commit();
    }

    /**
     * An unconfirmed trial that this process has run stably is confirmed here, so a player who only
     * uses menus (the engine's own gate never sees a world) is not stuck on "still confirming".
     * A release that crashes dies before this long and is rolled back by the host as before.
     */
    private JSONObject confirmStableTrial(JSONObject core) {
        if (core == null || core.isNull("trial")) return core;
        long uptime = SystemClock.elapsedRealtime() - android.os.Process.getStartElapsedRealtime();
        if (!AndySUState.trialStable(true, uptime)) return core;
        if (AndySUCompat.markHealthy()) {
            Log.i(TAG, "Confirmed the stable trial after " + uptime / 1000 + "s");
            JSONObject after = coreState();
            if (after != null) return after;
        }
        return core;
    }

    private String applyProblem(JSONObject core, String pending) {
        if (pending.isEmpty()) return "";
        SharedPreferences attempts = context.getSharedPreferences("andysu_apply", Context.MODE_PRIVATE);
        return AndySUState.applyProblem(core != null, core != null && !core.isNull("trial"),
                pending, attempts.getString("version", ""));
    }

    /** Refuse a restart that cannot promote the core's actual pending slot. */
    private boolean prepareRestart() {
        if (!AndySUCompat.has("restartToApply") && !AndySUCompat.has("restartNow")) {
            requestError = "This AndySU host cannot restart to apply the update.";
            return false;
        }
        JSONObject core = confirmStableTrial(coreState());
        String pending = pendingRelease(core, AndySUCompat.payloadVersion());
        String problem = AndySUState.applyProblem(core != null, core != null && !core.isNull("trial"), pending, "");
        if (problem.isEmpty() && pending.isEmpty()) problem = "AndySU has no staged update to apply.";
        if (!problem.isEmpty()) {
            requestError = problem;
            return false;
        }
        context.getSharedPreferences("andysu_apply", Context.MODE_PRIVATE).edit()
                .putString("version", pending).putLong("at", System.currentTimeMillis()).commit();
        return true;
    }

    private Map<String, Long> parts() {
        Map<String, Long> parts = new HashMap<>();
        File[] files = blobDir.listFiles();
        if (files == null) return parts;
        for (File f : files) {
            String name = f.getName();
            if (name.endsWith(".part")) parts.put(name.substring(0, name.length() - 5), f.length());
        }
        return parts;
    }

    /** The channel this host follows, from its frozen config (assets/andysu/config.json). */
    private String channel() {
        if (channel != null) return channel;
        channel = "";
        try (InputStream in = context.getAssets().open("andysu/config.json")) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0 && out.size() < 256 * 1024; ) out.write(buf, 0, n);
            channel = new JSONObject(out.toString("UTF-8")).optString("channel", "");
        } catch (Exception ignored) {
            // not a host build, or no config: no channel to show
        }
        return channel;
    }

    private String abi() {
        return UpdateBridge.abi(context);
    }

    /** The system notification follows the download: shown while one runs, gone after. */
    private void notice(String version) {
        String line = AndySUState.progressLine(progress);
        String text = version == null ? "" : version + "\n" + line;
        if (text.equals(noticeText)) return;
        noticeText = text;
        if (version == null) {
            UpdateNotice.cancel(context);
            // The download is over (or was never running): Android may freeze the process again.
            KeepAliveService.stopDownload(context);
        } else {
            UpdateNotice.show(context, version, progress.percent(), progress.total > 0, line);
            // The host fetches into this process. In the background Android freezes a cached
            // process within seconds (Android 14's app freezer) and the download stops with it, so
            // a foreground service holds it. Started here, while the game is in front: Android 12+
            // refuses a foreground service started from the background.
            KeepAliveService.show(context, KeepAliveService.KIND_DOWNLOAD, version + "\n" + line);
        }
    }

    private void write(AndySUState.Record r) {
        String record = r.encode();
        if (record.equals(lastRecord) && statusFile.isFile()) return;
        try {
            File dir = statusFile.getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs()) return;
            File tmp = new File(statusFile.getPath() + ".tmp");
            Files.write(tmp.toPath(), record.getBytes(StandardCharsets.UTF_8));
            if (!tmp.renameTo(statusFile)) {
                Log.w(TAG, "Could not replace " + statusFile);
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            } else {
                lastRecord = record;
            }
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "Could not publish AndySU update status", e);
        }
    }
}
