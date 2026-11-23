package io.github.dixonsolutions.godnotic;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The decisions behind Settings &gt; Updates on an AndySU install, kept free of Android classes so
 * they run as plain JVM unit tests (src/test/java/.../AndySUStateTest.java). {@link AndySUBridge}
 * feeds it what the host and the system report and writes what it returns.
 *
 * <p>What comes from where (host API 3, see docs/ANDROID.md "Updates screen"):
 * <ul>
 * <li>installed version, status, staged release: the host ({@code HostApi.version()},
 *     {@code UpdateClient.status()}, {@code core("state").pending});</li>
 * <li>apply live: the host's persisted apply policy ({@code setApplyPolicy}, live / next_start);</li>
 * <li>automatic updates: the host's persisted update mode ({@code setMode}, level 3);</li>
 * <li>Wi-Fi only: app-side. The host's own network rule is frozen into its config
 *     ({@code network = "any"}), so the app moves the host to {@code check_only} while the
 *     connection is metered ({@link #targetMode});</li>
 * <li>download progress: app-side. The host reports only "downloading" and the byte count
 *     still to fetch; the app watches the {@code .part} files it appends to ({@link Progress}).</li>
 * </ul>
 */
final class AndySUState {
    private AndySUState() {}

    /** Ten minutes: one automatic restart per staged version in that window. */
    static final long AUTO_RESTART_WINDOW_MS = 10 * 60 * 1000L;

    /**
     * Whether an automatic restart into {@code version} may be requested now. Never for an
     * unknown version, and never twice for the same version inside the window: the host can keep
     * reporting "staged" after a relaunch that did not promote it, and asking again on every
     * start turned the app into a restart loop. The last request is persisted because the
     * process that made it is gone by the time the next one decides.
     */
    static boolean mayAutoRestart(String version, String lastVersion, long lastAtMs, long nowMs) {
        if (version == null || version.isEmpty() || "?".equals(version)) return false;
        if (!version.equals(lastVersion)) return true;
        long since = nowMs - lastAtMs;
        return since < 0 || since >= AUTO_RESTART_WINDOW_MS;
    }

    /** A release that has run this long since its trial began, without dying, is a healthy release. */
    static final long TRIAL_STABLE_MS = 60_000;
    /** Between two tries at confirming (the host commits on its main thread, so give it a moment). */
    static final long TRIAL_RETRY_MS = 4_000;
    /** After every way of confirming has been tried once, how often to go round again. */
    static final long TRIAL_SLOW_RETRY_MS = 30_000;

    /** The ways to commit the running release, in the order they are tried. */
    static final int CONFIRM_API = 1;   // HostApi.markHealthy(): every host level
    static final int CONFIRM_HOST = 2;  // com.andysu.host.AndySU.markHealthy(): the host object itself
    static final int CONFIRM_CORE = 3;  // com.andysu.host.Core.markHealthy(root): the state file, last resort
    static final int CONFIRM_METHODS = 3;

    /**
     * Whether an unconfirmed trial may be confirmed by the app itself, from how long the trial has
     * run, not from how long the process has: a live swap opens its trial in a process that may be
     * hours old, and committing that trial at once would drop the host's rollback for an engine
     * that has not started yet. {@code seenMs} is how long this process has watched this very
     * trial; {@code ageMs} how long the host says it began ago (-1: unknown).
     */
    static boolean trialStable(boolean trial, long seenMs, long ageMs) {
        return trial && seenMs >= TRIAL_STABLE_MS && (ageMs < 0 || ageMs >= TRIAL_STABLE_MS);
    }

    /** How long ago the host opened the trial ({@code state.trial.started}, epoch seconds); -1 if it cannot tell. */
    static long trialAgeMs(long startedSeconds, long nowMs) {
        if (startedSeconds <= 0) return -1;
        long age = nowMs - startedSeconds * 1000;
        return age < 0 || age > 400L * 24 * 3600 * 1000 ? -1 : age;
    }

    /**
     * Decides when and how the app confirms a trial the engine did not (the engine's own healthy
     * report is the normal way: first world, 8 s of menu frames, or 30 s of frame loop). One gate
     * per bridge; {@link #poll} once a second with what the host reports.
     */
    static final class TrialGate {
        private String key = "";
        private long seenAt;
        private long lastAttemptAt = -1;
        private int attempts;
        private int cursor;
        private int usable;
        private boolean due;

        /**
         * @param key        identity of the open trial ("" when none is open)
         * @param swapActive an engine swap of this process is under way (its trial is not stable yet)
         * @param available  per method (index 1..CONFIRM_METHODS) whether this host has it
         * @return the method to try now (CONFIRM_*), or 0
         */
        int poll(String key, long now, long ageMs, boolean swapActive, boolean[] available) {
            if (key == null || key.isEmpty()) {
                reset("", now);
                return 0;
            }
            if (!key.equals(this.key)) reset(key, now);
            usable = 0;
            for (int m = 1; m <= CONFIRM_METHODS; m++) {
                if (available != null && m < available.length && available[m]) usable++;
            }
            if (swapActive) {
                // The release is being swapped in right now: its stability counts from the end of it.
                seenAt = now;
                due = false;
                lastAttemptAt = -1;
                attempts = 0;
                cursor = 0;
                return 0;
            }
            due = trialStable(true, now - seenAt, ageMs);
            if (!due || usable == 0) return 0;
            if (lastAttemptAt >= 0 && now - lastAttemptAt < (attempts < usable ? TRIAL_RETRY_MS : TRIAL_SLOW_RETRY_MS)) {
                return 0;
            }
            for (int i = 0; i < CONFIRM_METHODS; i++) {
                int method = (cursor + i) % CONFIRM_METHODS + 1;
                if (available[method]) {
                    cursor = method;
                    attempts++;
                    lastAttemptAt = now;
                    return method;
                }
            }
            return 0;
        }

        private void reset(String key, long now) {
            this.key = key;
            seenAt = now;
            lastAttemptAt = -1;
            attempts = 0;
            cursor = 0;
            usable = 0;
            due = false;
        }

        /**
         * The trial is stable and still open after every way of confirming it was tried (or the
         * host has none): the screen stops waiting and says so.
         */
        boolean stuck(long now) {
            if (key.isEmpty() || !due) return false;
            if (usable == 0) return true;
            return attempts >= usable && lastAttemptAt >= 0 && now - lastAttemptAt >= TRIAL_RETRY_MS;
        }

        /** Milliseconds until the first try, 0 once it is due or under way. */
        long waitMs(long now, long ageMs) {
            if (key.isEmpty()) return 0;
            long a = TRIAL_STABLE_MS - (now - seenAt);
            long b = ageMs < 0 ? 0 : TRIAL_STABLE_MS - ageMs;
            return Math.max(0, Math.max(a, b));
        }

        int attempts() {
            return attempts;
        }
    }

    /**
     * Whether an open trial blocks Apply live. The host's own swap is refused by the host while it
     * is open. The app's engine and data swap does not need the host's trial at all: it waits only
     * while the confirmation is under way, and goes ahead once every way of confirming was tried.
     */
    static boolean trialBlocksLive(boolean trial, boolean hostSwap, boolean stuck) {
        return trial && (hostSwap || !stuck);
    }

    /** Under Apply live while the running release is being confirmed: no error, just the wait. */
    static String trialWaitText(long msLeft) {
        if (msLeft <= 0) return "Confirming the running version. Apply live is ready in a moment.";
        long s = (msLeft + 999) / 1000;
        return "Confirming the running version first (about " + s + " s). Apply live goes ahead right after.";
    }

    /** The host kept the running release unconfirmed after every try: say so, and offer Restart now. */
    static String trialStuckText() {
        return "AndySU has not accepted the confirmation of the running version, so it cannot swap an update in live. "
                + "Restart now relaunches Godnotic; the update applies once the new start has been confirmed.";
    }

    /**
     * The host's "apply_refused" is a record of one refused restart, not a state the screen can act
     * on, and it stays in the status after the trial that caused it is confirmed. It reads as what
     * the release is: staged while one is waiting, otherwise idle. The reason a swap cannot run
     * now comes from the core's trial (wait, or the way out), never from this word.
     */
    static String settleApplyRefused(String state, String pending) {
        if (!"apply_refused".equals(state)) return state;
        return pending == null || pending.isEmpty() ? "idle" : "staged";
    }

    /** The decision for a start that the host's restart refused (Host API with restartToApply). */
    static String applyRefusedMessage() {
        return "AndySU refused the restart because it is still confirming the running version. Try again in a minute.";
    }

    static String applyProblem(boolean stateKnown, boolean trial, String pending, String attempted) {
        if (!stateKnown) return "Could not read the AndySU update state.";
        if (pending == null || pending.isEmpty()) return "";
        if (trial) return ""; // the Updates screen shows the wait or the way out, not an error
        if (pending.equals(attempted)) return "AndySU did not apply the update; try Apply now again.";
        return "";
    }

    /** How long the engine must stay idle before the apply policy goes back to the player's choice. */
    static final long BUSY_HOLD_MS = 30_000;

    /**
     * The apply policy the host should have. The player's switch ({@code liveEnabled}) is the one
     * source; a busy engine (match, join, download) only holds it at next_start, and for
     * {@link #BUSY_HOLD_MS} after the last busy reading, because the net state file reads idle for
     * a poll between map changes and used to flip the policy every two seconds.
     */
    static String desiredPolicy(boolean liveEnabled, boolean busy, long now, long lastBusyAt) {
        if (!liveEnabled) return "next_start";
        if (busy) return "next_start";
        return lastBusyAt != 0 && now - lastBusyAt < BUSY_HOLD_MS ? "next_start" : "live";
    }

    /** The engine must have read safe for this long before an update may restart or swap it. */
    static final long IDLE_BEFORE_APPLY_MS = 5_000;

    /**
     * Whether the app may replace, restart or swap the engine for an update right now. Only from
     * the menu: never while a match is running or being joined or loaded (stage game/joining, the
     * engine reports busy, or touch_update_game_state is not "safe"), never in the background (a
     * match may be running there), and not until the engine has been idle for
     * {@link #IDLE_BEFORE_APPLY_MS}. Anything else defers to the menu or the next start.
     *
     * @param stage     ProcessState stage: boot, game, joining, provider, none, ""
     * @param engineBusy KeepAliveService.engineBusy (connected, joining or downloading)
     * @param gameState contents of touch_update_game_state, null when unreadable
     * @param idleMs    time since the engine last read busy
     */
    static boolean safeToApplyNow(String stage, boolean engineBusy, String gameState,
                                  boolean foreground, long idleMs) {
        if (!foreground || engineBusy || !"safe".equals(gameState)) return false;
        if ("joining".equals(stage) || "loading".equals(stage)) return false;
        return idleMs >= IDLE_BEFORE_APPLY_MS;
    }

    /**
     * Whether a staged release may be applied without the player tapping Apply now: only when the
     * player left automatic updates on. A host in check_only (automatic off, or Wi-Fi only on a
     * metered connection) never applies by itself; a release staged earlier waits for the tap, or
     * for the switch.
     */
    static boolean mayApplyUnasked(boolean autoSwitch, boolean userAsked) {
        return autoSwitch || userAsked;
    }

    /** False for the release whose automatic apply already failed or was refused. */
    static boolean mayAnnounce(String version, String blocked) {
        return blocked == null || blocked.isEmpty() || !blocked.equals(version);
    }

    /** Unknown policy is held; null means the host has no policy API. */
    static boolean shouldAutoApply(String state, boolean inMatch, String policy) {
        return "staged".equals(state) && !inMatch
                && (policy == null || "live".equals(policy) || "restart".equals(policy));
    }

    /**
     * Whether a staged release is swapped into the running process now: live apply is the policy,
     * the process is not in a match, and the release can be swapped in ({@code swappable}:
     * {@link LiveApply#isLive}, which is true for an engine or data change even when the Java code
     * changed too; the host's {@code native_live} alone, which is false whenever the dex changed,
     * is what used to send every release to the restart path). Nothing else applies by itself.
     */
    static boolean shouldApplyLive(String state, boolean inMatch, String policy, boolean swappable) {
        return "staged".equals(state) && !inMatch && "live".equals(policy) && swappable;
    }


    /**
     * Whether the boot screen may ask the host to restart into a staged release. Not while the
     * game data is being written: on the first start of a new payload that is the unpack of
     * {@code xonotic-slim-data.zip}, and a restart there kills the process mid-write, which leaves
     * a half-written bundle for the start after it. The engine's own update service applies the
     * release once the game is up, which is where this belongs anyway.
     */
    static boolean shouldAutoApplyAtBoot(String state, boolean preparing, String policy) {
        return !preparing && shouldAutoApply(state, false, policy);
    }

    /** Bump only with the menu's TOUCH_ANDYSU_FORMAT. Lines 9 on are key=value extras. */
    static final int FORMAT = 1;

    static final String MODE_AUTO = "auto";
    static final String MODE_DOWNLOAD_ONLY = "download_only";
    static final String MODE_CHECK_ONLY = "check_only";
    static final String MODE_OFF = "off";

    /** The host's states (UpdateClient), spelled the way the menu reads them. */
    static String normalize(String hostState) {
        if (hostState == null || hostState.isEmpty()) return "idle";
        switch (hostState) {
            case "up_to_date": return "uptodate";
            case "shell_update": return "shell-update";
            default: return hostState;
        }
    }

    /** States in which the host is working and a manual mode must not be taken back yet. */
    static boolean busy(String state) {
        return "checking".equals(state) || "downloading".equals(state) || "applying".equals(state);
    }

    /** States that end a check: when one arrives, that is the "last checked" time and result. */
    static boolean endsCheck(String state) {
        switch (state) {
            case "uptodate": case "available": case "staged": case "shell-update":
            case "deferred": case "error": case "apply_refused":
                return true;
            default:
                return false;
        }
    }

    /**
     * The mode the host should run in, from the player's two switches.
     *
     * <ul>
     * <li>Automatic updates off: {@code check_only}. Background checks still say a release is
     *     there; nothing downloads or applies until the player asks.</li>
     * <li>Automatic, any network: {@code auto}.</li>
     * <li>Automatic, Wi-Fi only, in the foreground: {@code auto} on an unmetered connection,
     *     {@code check_only} on a metered one.</li>
     * <li>Automatic, Wi-Fi only, in the background: the app no longer sees the network, and the
     *     host's periodic job runs on any network, so {@code check_only} — except while a release is
     *     already staged: the host promotes a staged release at the next start only in
     *     {@code auto} mode, and staging is where the download already happened.</li>
     * </ul>
     */
    static String targetMode(boolean auto, boolean wifiOnly, boolean metered, boolean staged,
                             boolean foreground) {
        return targetMode(auto, wifiOnly, metered, staged, foreground, false);
    }

    /**
     * The same, with a download already running counted like a staged release: leaving the app
     * while one runs must not move the host to {@code check_only}, which stops it and leaves the
     * {@code .part} files for the next start. On a metered connection it still stops, which is
     * what Wi-Fi only asks for.
     */
    static String targetMode(boolean auto, boolean wifiOnly, boolean metered, boolean staged,
                             boolean foreground, boolean downloading) {
        // One rule, whether or not the app is on screen: the host's mode follows only the
        // player's switches and the connection. Leaving the app used to drop it to check_only
        // (and the return took it back), a silent change nobody asked for.
        if (!auto) return MODE_CHECK_ONLY;
        if (!wifiOnly) return MODE_AUTO;
        if (!metered) return MODE_AUTO;
        // Metered: Wi-Fi only stops it, except that in the background a staged release stays
        // promotable at the next start (the host only does that in auto mode).
        return (!foreground && staged) ? MODE_AUTO : MODE_CHECK_ONLY;
    }

    /**
     * The host's own failure text as the player should read it. A blob that answers nothing is a
     * release whose assets are still uploading (the URL is the file's sha256), not a broken
     * phone, and the host retries it by itself; saying {@code could not download <sha256>} only
     * reads as something the player must fix.
     */
    static String friendlyMessage(String message) {
        if (message == null) return "";
        String lower = message.toLowerCase(Locale.ROOT);
        if (UpdateRetry.offline(message)) return UpdateRetry.offlineMessage();
        if (lower.contains("could not download")) return UpdateRetry.publishingMessage();
        return oneLine(message);
    }

    /**
     * The mode for a check the player asked for, held until it settles. Null: the target mode
     * already does it. {@code download} is "Download now": the player agreed to this connection.
     */
    static String manualMode(boolean auto, boolean wifiOnly, boolean metered, boolean download) {
        if (download) return auto ? MODE_AUTO : MODE_DOWNLOAD_ONLY;
        if (wifiOnly && metered) return null;            // check only; "Download now" offers the rest
        return auto ? null : MODE_DOWNLOAD_ONLY;        // fetch it, never apply it unasked
    }

    /**
     * Why a release the host knows about is not downloading: "wifi" (waiting for an unmetered
     * connection), "auto" (automatic updates are off) or "" (it is, or there is none).
     */
    static String hold(String state, boolean auto, boolean wifiOnly, boolean metered) {
        if (!"available".equals(state)) return "";
        if (!auto) return "auto";
        if (wifiOnly && metered) return "wifi";
        return "";
    }

    /** A record line holds one line of text. */
    static String oneLine(String value) {
        return value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').trim();
    }

    // ------------------------------------------------------------------ record

    /** Everything the menu shows, as written to touch/andysu-update-status.txt. */
    static final class Record {
        String state = "unavailable";
        String installed = "";
        String latest = "";
        int percent;
        String message = "";
        boolean auto;
        String mode = "off";
        /** key=value lines after the eight the first menus read; they ignore what follows. */
        final Map<String, String> extras = new java.util.LinkedHashMap<>();

        Record put(String key, String value) {
            if (value != null && !value.isEmpty()) extras.put(key, oneLine(value));
            return this;
        }

        Record put(String key, long value) {
            extras.put(key, Long.toString(value));
            return this;
        }

        Record flag(String key, Boolean value) {
            extras.put(key, value == null ? "na" : (value ? "on" : "off"));
            return this;
        }

        String encode() {
            StringBuilder b = new StringBuilder();
            b.append(FORMAT).append('\n')
                    .append(oneLine(state)).append('\n')
                    .append(oneLine(installed)).append('\n')
                    .append(oneLine(latest)).append('\n')
                    .append(Math.max(0, Math.min(100, percent))).append('\n')
                    .append(oneLine(message)).append('\n')
                    .append(auto ? "on" : "off").append('\n')
                    .append(oneLine(mode)).append('\n');
            for (Map.Entry<String, String> e : extras.entrySet()) {
                b.append(e.getKey()).append('=').append(e.getValue()).append('\n');
            }
            return b.toString();
        }
    }

    // ------------------------------------------------------------------ progress

    /** Size of a downloaded blob by its key, or -1. */
    interface BlobSize {
        long of(String key);
    }

    /**
     * Download progress from the host's {@code blobs/sha256/<key>.part} files. The host appends to
     * one part file per blob or delta and renames it to the blob when it verifies, so: bytes done =
     * what each part file grew by since the download started, counting a part that disappeared at
     * the size of the blob it became (or the last size seen, for a delta whose result has another
     * key). The total is the host's {@code download_bytes}: what was left to fetch when the check
     * ran.
     */
    static final class Progress {
        private final Map<String, Long> base = new HashMap<>();
        private final Map<String, Long> seen = new HashMap<>();
        private final long[] sampleT = new long[8];
        private final long[] sampleB = new long[8];
        private int samples;
        private boolean started;
        long total;
        long done;
        /** Bytes per second over the last few seconds, 0 before two samples. */
        long speed;
        /** Seconds left, -1 when unknown. */
        long eta = -1;

        void reset() {
            base.clear();
            seen.clear();
            samples = 0;
            started = false;
            total = 0;
            done = 0;
            speed = 0;
            eta = -1;
        }

        boolean started() {
            return started;
        }

        /** The first sample of a download: what is already on disk does not count as progress. */
        void start(long totalBytes, Map<String, Long> parts) {
            reset();
            started = true;
            total = Math.max(0, totalBytes);
            base.putAll(parts);
            seen.putAll(parts);
        }

        void sample(long nowMs, Map<String, Long> parts, BlobSize blobs) {
            if (!started) return;
            for (Map.Entry<String, Long> e : parts.entrySet()) {
                Long was = seen.get(e.getKey());
                seen.put(e.getKey(), was == null ? e.getValue() : Math.max(was, e.getValue()));
                if (!base.containsKey(e.getKey())) base.put(e.getKey(), 0L);
            }
            long sum = 0;
            for (Map.Entry<String, Long> e : seen.entrySet()) {
                String key = e.getKey();
                long now;
                if (parts.containsKey(key)) {
                    now = parts.get(key);
                } else {
                    long blob = blobs == null ? -1 : blobs.of(key);
                    now = Math.max(e.getValue(), blob);
                    seen.put(key, now);
                }
                sum += Math.max(0, now - base.get(key));
            }
            done = total > 0 ? Math.min(sum, total) : sum;
            addSample(nowMs, done);
        }

        private void addSample(long t, long bytes) {
            if (samples == sampleT.length) {
                System.arraycopy(sampleT, 1, sampleT, 0, samples - 1);
                System.arraycopy(sampleB, 1, sampleB, 0, samples - 1);
                samples--;
            }
            sampleT[samples] = t;
            sampleB[samples] = bytes;
            samples++;
            speed = 0;
            eta = -1;
            if (samples < 2) return;
            long dt = sampleT[samples - 1] - sampleT[0];
            long db = sampleB[samples - 1] - sampleB[0];
            if (dt <= 0) return;
            speed = Math.max(0, db * 1000 / dt);
            if (speed > 0 && total > 0) eta = (Math.max(0, total - done) + speed - 1) / speed;
        }

        int percent() {
            return total > 0 ? (int) Math.min(100, done * 100 / total) : 0;
        }
    }

    // ------------------------------------------------------------------ text

    /** "12.3 MB", "850 KB", "3 B": decimal units, as Android's file sizes are. */
    static String bytes(long n) {
        if (n < 1000) return n + " B";
        if (n < 1000 * 1000) return String.format(Locale.ROOT, "%d KB", n / 1000);
        if (n < 1000L * 1000 * 1000) return String.format(Locale.ROOT, "%.1f MB", n / 1e6);
        return String.format(Locale.ROOT, "%.2f GB", n / 1e9);
    }

    /** "45 s", "3 min", "1 h 5 min". */
    static String duration(long seconds) {
        if (seconds < 0) return "";
        if (seconds < 60) return seconds + " s";
        long min = (seconds + 59) / 60;
        if (min < 60) return min + " min";
        return (min / 60) + " h " + (min % 60) + " min";
    }

    /** "12.3 MB of 40.0 MB · 2.1 MB/s · 13 s left", as far as it is known. */
    static String progressLine(Progress p) {
        StringBuilder b = new StringBuilder(bytes(p.done));
        if (p.total > 0) b.append(" of ").append(bytes(p.total));
        if (p.speed > 0) b.append(" · ").append(bytes(p.speed)).append("/s");
        if (p.eta >= 0) b.append(" · ").append(duration(p.eta)).append(" left");
        return b.toString();
    }
}
