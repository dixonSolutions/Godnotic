package io.github.dixonsolutions.godnotic;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Which of the app's deaths were a bug, kept for the player to send: the system's record of recent
 * process exits (API 30+, with the native tombstone or ANR trace on 31+) and an
 * {@link Thread.UncaughtExceptionHandler} that writes the stack before the process goes.
 *
 * <p>A live update restarts the app inside the AndySU host, so the interesting case is the one in
 * the report: {@code 1.2.258 -> 1.2.259}. Each report says the app and payload version it happened
 * under, the version the last report saw (what it was before an update) and the host's own state.
 * Nothing is uploaded anywhere: the reports wait in app storage and leave only through the share
 * sheet, which the banner over the main menu and the Settings entry open.
 */
final class CrashStore {
    private static final String TAG = "Godnotic";
    private static final String PREFS = "crash-reports";
    private static final String PREF_SEEN = "seen_ms";
    private static final String PREF_COLLECTED = "collected_ms";
    private static final String PREF_VERSION = "version";
    private static final String PREF_PAYLOAD = "payload";
    /** One handler for the process, however many Activities start. */
    private static volatile boolean handled;

    private CrashStore() {}

    static File dir(Context context) {
        return new File(context.getNoBackupFilesDir(), "crash-reports");
    }

    /** Install the handler and read the system's exit reasons, off the main thread. */
    static void start(final Context context) {
        final Context app = context.getApplicationContext();
        installHandler(app);
        Thread reader = new Thread(() -> {
            try {
                collect(app);
            } catch (Throwable t) {
                Log.w(TAG, "Could not read the process exit reasons", t);
            }
        }, "crash-report-reader");
        reader.setDaemon(true);
        reader.start();
    }

    /**
     * Write the stack of an uncaught exception, then let the previous handler have it (the
     * system's, which shows the crash dialog and ends the process).
     */
    static void installHandler(Context context) {
        if (handled) {
            return;
        }
        handled = true;
        final Context app = context.getApplicationContext();
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable error) {
                try {
                    long now = System.currentTimeMillis();
                    String text = CrashReport.formatUncaught(now, thread.getName(), version(app),
                            payload(), host(), CrashReport.stack(error));
                    write(app, CrashReport.PREFIX + now + "-uncaught.txt", text);
                } catch (Throwable ignored) {
                    // A report must never be the reason the app dies without its usual dialog.
                }
                if (previous != null) {
                    previous.uncaughtException(thread, error);
                }
            }
        });
    }

    /** Read every exit reason newer than the last read and worth reporting, then keep five. */
    static void collect(Context context) {
        if (Build.VERSION.SDK_INT < 30) {
            prune(context);
            return;
        }
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        if (manager == null) {
            return;
        }
        List<ApplicationExitInfo> exits;
        try {
            exits = manager.getHistoricalProcessExitReasons(context.getPackageName(), 0, 16);
        } catch (RuntimeException e) {
            Log.w(TAG, "The system keeps no exit reasons here", e);
            prune(context);
            return;
        }
        SharedPreferences prefs = prefs(context);
        long collected = prefs.getLong(PREF_COLLECTED, 0);
        String versionBefore = prefs.getString(PREF_VERSION, "");
        String payloadBefore = prefs.getString(PREF_PAYLOAD, "");
        String versionNow = version(context);
        String payloadNow = payload();
        // A version or payload that differs from the last start's means the app was updated
        // between the process that died and this one: a kill around an update is then expected
        // and worth a report, not noise.
        boolean aroundUpdate = (!versionBefore.isEmpty() && !versionBefore.equals(versionNow))
                || (!payloadBefore.isEmpty() && !payloadBefore.equals(payloadNow));
        long newest = collected;
        for (ApplicationExitInfo exit : exits) {
            long when = exit.getTimestamp();
            if (when <= collected) {
                continue;
            }
            int reason = exit.getReason();
            if (!CrashReport.interesting(reason, aroundUpdate)) {
                continue;
            }
            try {
                String text = CrashReport.format(when, reason, exit.getDescription(),
                        exit.getImportance(), exit.getPss(), versionNow, payloadNow, host(),
                        versionBefore, trace(exit));
                write(context, CrashReport.fileName(when, reason), text);
            } catch (RuntimeException e) {
                Log.w(TAG, "Could not write the report of " + CrashReport.reasonName(reason), e);
            }
            newest = Math.max(newest, when);
        }
        prefs.edit().putLong(PREF_COLLECTED, newest)
                .putString(PREF_VERSION, versionNow)
                .putString(PREF_PAYLOAD, payloadNow).apply();
        prune(context);
    }

    /** The native tombstone (API 31+) or ANR trace of an exit, or "" when the system kept none. */
    private static String trace(ApplicationExitInfo exit) {
        if (Build.VERSION.SDK_INT < 31) {
            return "";
        }
        try (InputStream in = exit.getTraceInputStream()) {
            if (in == null) {
                return "";
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[16 * 1024];
            int read;
            while (out.size() < CrashReport.TRACE_LIMIT && (read = in.read(buffer)) > 0) {
                out.write(buffer, 0, Math.min(read, CrashReport.TRACE_LIMIT - out.size()));
            }
            return out.toString(StandardCharsets.UTF_8.name());
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }

    private static void write(Context context, String name, String text) {
        File dir = dir(context);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            Log.w(TAG, "Could not create " + dir);
            return;
        }
        File file = new File(dir, name);
        File tmp = new File(dir, name + ".tmp");
        try {
            Files.write(tmp.toPath(), text.getBytes(StandardCharsets.UTF_8));
            if (!tmp.renameTo(file)) {
                Log.w(TAG, "Could not replace " + file);
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "Could not write " + file, e);
        }
    }

    /** The last {@link CrashReport#KEEP} reports, newest first. */
    static List<File> reports(Context context) {
        File[] files = dir(context).listFiles();
        List<File> reports = new ArrayList<>();
        if (files == null) {
            return reports;
        }
        List<String> names = new ArrayList<>();
        for (File file : files) {
            names.add(file.getName());
        }
        for (String name : CrashReport.keepNewest(names, CrashReport.KEEP)) {
            reports.add(new File(dir(context), name));
        }
        return reports;
    }

    /** Delete all but the newest few, and anything unfinished a killed process left behind. */
    private static void prune(Context context) {
        File[] files = dir(context).listFiles();
        if (files == null) {
            return;
        }
        List<String> names = new ArrayList<>();
        for (File file : files) {
            String name = file.getName();
            if (name.endsWith(".tmp")) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
                continue;
            }
            names.add(name);
        }
        List<String> keep = CrashReport.keepNewest(names, CrashReport.KEEP);
        for (File file : files) {
            if (!keep.contains(file.getName()) && !file.getName().endsWith(".tmp")) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        }
    }

    /** A report's text, or "" when it cannot be read (a kill mid-write). */
    static String text(File report) {
        try {
            return new String(Files.readAllBytes(report.toPath()), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }

    static boolean hasUnseen(Context context) {
        List<File> reports = reports(context);
        if (reports.isEmpty()) {
            return false;
        }
        return CrashReport.shouldBanner(reports.get(0).lastModified(), prefs(context).getLong(PREF_SEEN, 0));
    }

    /** What the banner says: the newest report's headline. */
    static String bannerText(Context context) {
        List<File> reports = reports(context);
        if (reports.isEmpty()) {
            return HostRes.string(context, "crash_banner");
        }
        File newest = reports.get(0);
        for (int reason : new int[] { CrashReport.REASON_CRASH_NATIVE, CrashReport.REASON_CRASH,
                CrashReport.REASON_ANR, CrashReport.REASON_INITIALIZATION_FAILURE }) {
            if (newest.getName().contains("-" + CrashReport.reasonName(reason))) {
                return CrashReport.headline(reason);
            }
        }
        return HostRes.string(context, "crash_banner");
    }

    static void shareNewest(Context context) {
        List<File> reports = reports(context);
        if (reports.isEmpty()) {
            Toast.makeText(context, HostRes.string(context, "crash_none"), Toast.LENGTH_SHORT).show();
            return;
        }
        markSeen(context);
        share(context, text(reports.get(0)));
    }

    static void shareAll(Context context) {
        List<File> reports = reports(context);
        if (reports.isEmpty()) {
            Toast.makeText(context, HostRes.string(context, "crash_none"), Toast.LENGTH_SHORT).show();
            return;
        }
        markSeen(context);
        StringBuilder all = new StringBuilder();
        for (File report : reports) {
            String body = text(report);
            if (body.isEmpty()) {
                continue;
            }
            if (all.length() > 0) {
                all.append("\n\n=====\n\n");
            }
            all.append(body);
        }
        share(context, all.toString());
    }

    /** The Android share sheet with the report as text: the player pastes it wherever they like. */
    static void share(Context context, String text) {
        if (text == null || text.isEmpty()) {
            Toast.makeText(context, HostRes.string(context, "crash_none"), Toast.LENGTH_SHORT).show();
            return;
        }
        Intent send = new Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, HostRes.string(context, "crash_subject"))
                .putExtra(Intent.EXTRA_TEXT, text);
        Intent chooser = Intent.createChooser(send, HostRes.string(context, "crash_share"));
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(chooser);
        } catch (RuntimeException e) {
            Log.w(TAG, "No share sheet", e);
            Toast.makeText(context, HostRes.string(context, "crash_no_share"), Toast.LENGTH_LONG).show();
        }
    }

    /** The list the banner's second line and the Settings entry open: one per report, plus all. */
    static void showList(final Activity activity) {
        final List<File> reports = reports(activity);
        if (reports.isEmpty()) {
            new AlertDialog.Builder(activity)
                    .setTitle(HostRes.string(activity, "crash_title"))
                    .setMessage(HostRes.string(activity, "crash_none"))
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            return;
        }
        final String[] names = new String[reports.size()];
        for (int i = 0; i < reports.size(); i++) {
            File report = reports.get(i);
            names[i] = CrashReport.when(report.lastModified()) + " · " + report.getName();
        }
        new AlertDialog.Builder(activity)
                .setTitle(HostRes.string(activity, "crash_title"))
                .setItems(names, (dialog, which) -> share(activity, text(reports.get(which))))
                .setNeutralButton(HostRes.string(activity, "crash_share_all"), (dialog, which) -> shareAll(activity))
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    static void markSeen(Context context) {
        prefs(context).edit().putLong(PREF_SEEN, System.currentTimeMillis()).apply();
    }

    /** The requests the menu writes (touch/crash-reports-request.txt): "share", "list", "all". */
    static String takeRequest(File file) {
        if (file == null || !file.isFile()) {
            return null;
        }
        String token = null;
        try {
            List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
            for (String line : lines) {
                if (!line.trim().isEmpty()) {
                    token = line.trim();
                    break;
                }
            }
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "Could not read the crash report request", e);
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
        return token;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String version(Context context) {
        try {
            String name = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionName;
            return name == null ? "" : name;
        } catch (PackageManager.NameNotFoundException | RuntimeException e) {
            return "";
        }
    }

    private static String payload() {
        String version = AndySUCompat.payloadVersion();
        return version == null ? "" : version;
    }

    /** The AndySU host's own view of itself (version, mode, status), one line. */
    private static String host() {
        StringBuilder b = new StringBuilder();
        try {
            b.append("api=").append(AndySUCompat.apiLevel());
            String mode = AndySUCompat.mode();
            if (mode != null) {
                b.append(" mode=").append(mode);
            }
            String status = AndySUCompat.updateStatus();
            if (status != null) {
                b.append(" status=").append(status);
            }
        } catch (Throwable t) {
            b.append("unavailable");
        }
        return CrashReport.oneLine(b.toString());
    }
}
