package io.github.dixonsolutions.godnotic;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import java.nio.charset.StandardCharsets;

/**
 * Publishes what this process is doing to the system, so every {@code ApplicationExitInfo} carries
 * the state it died in ({@code getProcessStateSummary}), and so the crash report can print it.
 *
 * <p>The versions in a crash report's header are the ones <em>reading</em> the report: the system's
 * exit reason is only read on the next start, by the next version. The state summary is the only
 * thing the dead process itself leaves behind, so it is written on every change rather than once:
 * app and payload version, whether the AndySU host is there, the screen or stage, whether the engine
 * runs, and the update phase. It is kept short and ordered ({@link ProcessStateText}) and only
 * republished when the line actually changes.
 */
final class ProcessState {
    private static final String TAG = "Godnotic";

    private static volatile String app = "";
    private static volatile String payload = "";
    private static volatile String host = "";
    private static volatile String stage = "";
    private static volatile String phase = "";
    private static volatile boolean engine;
    /** The last line handed to the system: an unchanged state is not republished. */
    private static volatile String published = "";

    private ProcessState() {}

    /** The first line of the process, from Application.onCreate onwards. */
    static void start(Context context) {
        Context app = context.getApplicationContext();
        stage(app, "application");
        refresh(app);
    }

    /** Recompute the versions, the host and the payload, then publish if anything changed. */
    static void refresh(Context context) {
        Context app = context.getApplicationContext();
        ProcessState.app = installedVersion(app);
        ProcessState.payload = value(AndySUCompat.payloadVersion());
        ProcessState.host = hostStatus(app);
        publish(app);
    }

    /** The screen or stage the process is in: boot, game, provider, none. */
    static void stage(Context context, String stage) {
        ProcessState.stage = value(stage);
        publish(context.getApplicationContext());
    }

    /** The engine is up (or went away): the game's own thread is running. */
    static void engine(Context context, boolean running) {
        engine = running;
        publish(context.getApplicationContext());
    }

    /** The update phase: checking, downloading, install, replacing, data, idle. */
    static void phase(Context context, String phase) {
        ProcessState.phase = value(phase);
        publish(context.getApplicationContext());
    }

    /** The line as last published (what a report prints under {@code state:}). */
    static String summary() {
        return published;
    }

    private static synchronized void publish(Context context) {
        String next = ProcessStateText.format(app, payload, host, stage, engine, phase);
        if (!ProcessStateText.changed(published, next)) {
            return;
        }
        published = next;
        if (Build.VERSION.SDK_INT < 28) {
            return;
        }
        try {
            ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (manager != null) {
                manager.setProcessStateSummary(next.getBytes(StandardCharsets.UTF_8));
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not publish the process state", e);
        }
    }

    private static String installedVersion(Context context) {
        try {
            String name = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionName;
            return value(name);
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return "";
        }
    }

    private static String hostStatus(Context context) {
        try {
            if (!AndySUCompat.present()) {
                return "absent";
            }
            String mode = AndySUCompat.mode();
            return mode == null || mode.isEmpty() ? "present" : "present:" + mode;
        } catch (Throwable t) {
            return "unknown";
        }
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }
}
