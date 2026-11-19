package io.github.dixonsolutions.godnotic;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * What counts as a crash report and what one says: plain JVM, no Android classes, so
 * {@code CrashReportTest} runs it without a device ({@link CrashStore} does the reading and writing).
 *
 * <p>Two sources feed it. The system keeps the exit reasons of this package's recent processes
 * ({@code ActivityManager.getHistoricalProcessExitReasons}, API 30+): a Java crash, a native crash,
 * an ANR, a start that never got going. A process killed by a signal or that left by itself is only
 * interesting around an update, when the host relaunches the app and a kill is expected. The other
 * source is the app's own handler, which writes the stack of an exception before the process dies.
 */
final class CrashReport {
    private CrashReport() {}

    /** ActivityManager's public constants, written out: the class exists on the device only. */
    static final int REASON_EXIT_SELF = 1;
    static final int REASON_SIGNALED = 2;
    static final int REASON_LOW_MEMORY = 3;
    static final int REASON_CRASH = 4;
    static final int REASON_CRASH_NATIVE = 5;
    static final int REASON_ANR = 6;
    static final int REASON_INITIALIZATION_FAILURE = 7;
    static final int REASON_PERMISSION_CHANGE = 8;
    static final int REASON_EXCESSIVE_RESOURCE_USAGE = 9;
    static final int REASON_USER_REQUESTED = 10;
    static final int REASON_USER_STOPPED = 11;
    static final int REASON_DEPENDENCY_DIED = 12;
    static final int REASON_OTHER = 13;
    static final int REASON_FREEZER = 14;
    static final int REASON_PACKAGE_STATE_CHANGE = 15;
    static final int REASON_PACKAGE_UPDATED = 16;

    /** Report files start with this and a millisecond timestamp, so they sort by age. */
    static final String PREFIX = "report-";
    /** The last this many reports are kept, oldest deleted. */
    static final int KEEP = 5;
    /** A native tombstone or ANR trace is cut here: a share sheet is not a log server. */
    static final int TRACE_LIMIT = 192 * 1024;

    /**
     * Whether the system's reason for a dead process is worth a report.
     *
     * <p>A crash, a native crash, an ANR or a start that failed always is. {@code SIGNALED} and
     * {@code EXIT_SELF} only when the app version or payload changed since the last report was
     * read: those are also how a live update's relaunch ends the old process, and reporting every
     * update as a crash would teach the player to ignore the banner.
     */
    static boolean interesting(int reason, boolean aroundUpdate) {
        switch (reason) {
            case REASON_CRASH:
            case REASON_CRASH_NATIVE:
            case REASON_ANR:
            case REASON_INITIALIZATION_FAILURE:
                return true;
            case REASON_SIGNALED:
            case REASON_EXIT_SELF:
                return aroundUpdate;
            default:
                return false;
        }
    }

    /**
     * Whether a death becomes a report: interesting, and not the update handover.
     *
     * <p>An apply relaunches the app, and the old process leaves by itself (or is asked to) in the
     * {@code quiet} or {@code replacing} phase. Its state line says so, and the next version reads
     * the exit. That is the handover, not a crash: writing it as a report shows the player "Godnotic
     * exited by itself" after every successful update. A crash, native crash or ANR in the same
     * phase is still a report — only a self-exit is handover, and the state is checked as well as
     * the version change so a real quiet-phase crash is not filtered with it.
     */
    static boolean shouldReport(int reason, boolean aroundUpdate, String state,
                                boolean versionChanged) {
        if (!interesting(reason, aroundUpdate)) {
            return false;
        }
        String phase = phase(state);
        boolean handoverPhase = "quiet".equals(phase) || "replacing".equals(phase);
        if (reason == REASON_SIGNALED) {
            // The host's relaunch can end the old process with a signal rather than a self-exit
            // (owner's tablet, 1.2.290 -> 1.2.292: "signaled (2)", phase=replacing). The state
            // line is the proof it was deliberate; without it a signal stays a report.
            return !handoverPhase;
        }
        if (reason != REASON_EXIT_SELF && reason != REASON_USER_REQUESTED) {
            return true;
        }
        return !(handoverPhase || versionChanged);
    }

    /** The {@code phase=...} field of a process state summary, or "" when it has none. */
    static String phase(String state) {
        if (state == null) {
            return "";
        }
        for (String field : state.trim().split("\\s+")) {
            if (field.startsWith("phase=")) {
                return field.substring("phase=".length());
            }
        }
        return "";
    }

    /** How the system spells the reason, for the file name and the first line. */
    static String reasonName(int reason) {
        switch (reason) {
            case REASON_EXIT_SELF: return "exit-self";
            case REASON_SIGNALED: return "signaled";
            case REASON_LOW_MEMORY: return "low-memory";
            case REASON_CRASH: return "crash";
            case REASON_CRASH_NATIVE: return "crash-native";
            case REASON_ANR: return "anr";
            case REASON_INITIALIZATION_FAILURE: return "init-failure";
            case REASON_PERMISSION_CHANGE: return "permission-change";
            case REASON_EXCESSIVE_RESOURCE_USAGE: return "resource-usage";
            case REASON_USER_REQUESTED: return "user-requested";
            case REASON_USER_STOPPED: return "user-stopped";
            case REASON_DEPENDENCY_DIED: return "dependency-died";
            case REASON_OTHER: return "other";
            case REASON_FREEZER: return "freezer";
            case REASON_PACKAGE_STATE_CHANGE: return "package-state";
            case REASON_PACKAGE_UPDATED: return "package-updated";
            default: return "reason-" + reason;
        }
    }

    /** The banner's words: "Godnotic crashed", "Godnotic stopped responding", ... */
    static String headline(int reason) {
        switch (reason) {
            case REASON_CRASH: return "Godnotic crashed";
            case REASON_CRASH_NATIVE: return "Godnotic crashed in native code";
            case REASON_ANR: return "Godnotic stopped responding";
            case REASON_INITIALIZATION_FAILURE: return "Godnotic failed to start";
            case REASON_SIGNALED: return "Godnotic was killed by a signal";
            case REASON_EXIT_SELF: return "Godnotic exited by itself";
            default: return "Godnotic stopped (" + reasonName(reason) + ")";
        }
    }

    /** Milliseconds since the epoch at the start of a report file name, or -1. */
    static long fileStamp(String name) {
        if (name == null || !name.startsWith(PREFIX)) {
            return -1;
        }
        int end = PREFIX.length();
        while (end < name.length() && Character.isDigit(name.charAt(end))) {
            end++;
        }
        if (end == PREFIX.length()) {
            return -1;
        }
        try {
            return Long.parseLong(name.substring(PREFIX.length(), end));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    static String fileName(long whenMs, int reason) {
        return PREFIX + whenMs + "-" + reasonName(reason) + ".txt";
    }

    /**
     * The file the handler writes for an exception, tagged with the pid that threw it: the next
     * start matches it to the system's exit reason for the same death.
     */
    static String uncaughtName(long whenMs, int pid) {
        return PREFIX + whenMs + "-uncaught-" + pid + ".txt";
    }

    /** The pid an uncaught report was written by, or -1 when the name is not one of those. */
    static int uncaughtPid(String name) {
        if (name == null) {
            return -1;
        }
        int at = name.indexOf("-uncaught-");
        if (at < 0 || !name.endsWith(".txt")) {
            return -1;
        }
        String pid = name.substring(at + "-uncaught-".length(), name.length() - 4);
        if (pid.isEmpty()) {
            return -1;
        }
        for (int i = 0; i < pid.length(); i++) {
            if (!Character.isDigit(pid.charAt(i))) {
                return -1;
            }
        }
        try {
            return Integer.parseInt(pid);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Whether an uncaught report is the same death the system recorded: the same process, and
     * written within {@code windowMs} of the timestamp the exit reason carries.
     */
    static boolean sameDeath(int exitPid, long exitWhenMs, int uncaughtPid, long uncaughtWhenMs,
                             long windowMs) {
        return exitPid > 0 && exitPid == uncaughtPid && exitWhenMs > 0 && uncaughtWhenMs > 0
                && Math.abs(exitWhenMs - uncaughtWhenMs) <= windowMs;
    }

    /** What identifies one death, so the same exit reason is not written to a report twice. */
    static String key(int pid, long whenMs, int reason, String description) {
        return whenMs + "|" + pid + "|" + reason + "|" + oneLine(description);
    }

    /**
     * The newest {@code keep} file names out of [names], oldest of those deleted. A name without a
     * timestamp is never kept: it is not one of ours.
     */
    static List<String> keepNewest(List<String> names, int keep) {
        List<String> ours = new ArrayList<>();
        for (String name : names) {
            if (fileStamp(name) >= 0) {
                ours.add(name);
            }
        }
        Collections.sort(ours, new Comparator<String>() {
            @Override public int compare(String a, String b) {
                long left = fileStamp(a);
                long right = fileStamp(b);
                return left == right ? a.compareTo(b) : (left < right ? 1 : -1);
            }
        });
        return ours.size() <= keep ? ours : new ArrayList<>(ours.subList(0, keep));
    }

    /** How long the banner stays up before it puts itself away. */
    static final long BANNER_MS = 8_000;

    /** Show the banner when a report is newer than the one the player last shared or dismissed. */
    static boolean shouldBanner(long newestReportMs, long seenMs) {
        return newestReportMs > 0 && newestReportMs > seenMs;
    }

    /** The value of a {@code key: value} header line of a report, "" when it has none. */
    static String header(String report, String key) {
        if (report == null) {
            return "";
        }
        for (String line : report.split("\n", 40)) {
            if (line.startsWith(key + ":")) {
                return line.substring(key.length() + 1).trim();
            }
            if (line.startsWith("--- ")) {
                break;
            }
        }
        return "";
    }

    /** The {@code key=value} field of a process state summary, "" when it has none. */
    static String stateField(String state, String key) {
        if (state == null) {
            return "";
        }
        for (String field : state.trim().split("\\s+")) {
            if (field.startsWith(key + "=")) {
                return field.substring(key.length() + 1);
            }
        }
        return "";
    }

    /**
     * Whether a report was recorded in the version running now or by the start right after the
     * crash. The report's {@code app:}/{@code payload:} are the versions that read it, so they match
     * the current ones when the crash happened and was read by this version, or by the first start
     * after it; the {@code state:} line holds the versions the dead process ran. Crashes of older
     * versions, read hours later by an update, match neither.
     */
    static boolean currentVersion(String report, String appNow, String payloadNow) {
        if (appNow == null || appNow.isEmpty()) {
            return false;
        }
        String state = header(report, "state");
        String diedApp = stateField(state, "app");
        String diedPayload = stateField(state, "payload");
        boolean diedNow = appNow.equals(diedApp)
                && (diedPayload.isEmpty() || payloadNow == null || diedPayload.equals(payloadNow));
        boolean readNow = appNow.equals(header(report, "app"))
                && (payloadNow == null || payloadNow.isEmpty()
                    || payloadNow.equals(header(report, "payload")));
        return diedNow || readNow;
    }

    /**
     * Where the banner's top edge sits (dp, under the status bar and cutout): below a screen's
     * header (14 dp padding + 76 dp minimum height), so it never covers a title.
     */
    static final int BANNER_TOP_DP = 96;

    /** The header's bottom edge in dp (see the native screens' bar). */
    static final int HEADER_BOTTOM_DP = 90;

    /**
     * The banner's whole rule: only in the main menu (never in or entering a match), only for a
     * report newer than the last one dismissed, and only one of this app/payload version.
     */
    static boolean shouldShowBanner(boolean inMenu, long newestReportMs, long seenMs, String report,
                                    String appNow, String payloadNow) {
        return inMenu && shouldBanner(newestReportMs, seenMs)
                && currentVersion(report, appNow, payloadNow);
    }

    /** A stack trace as text, for the handler's file. */
    static String stack(Throwable error) {
        if (error == null) {
            return "";
        }
        StringWriter out = new StringWriter();
        PrintWriter writer = new PrintWriter(out);
        error.printStackTrace(writer);
        writer.flush();
        return out.toString();
    }

    /** "2026-11-17 09:41:07" in the device's zone. */
    static String when(long whenMs) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date(whenMs));
    }

    /**
     * One report, as written to the file and shared.
     *
     * @param versionBefore the installed version the last time a report was read, "" if unknown
     * @param trace         tombstone or ANR trace, "" when the system kept none
     */
    static String format(long whenMs, int reason, String description, int importance, long pssKb,
                         String versionNow, String payloadNow, String host, String versionBefore,
                         String process, int pid, String state, String trace) {
        StringBuilder b = new StringBuilder();
        b.append("Godnotic crash report\n")
                .append("when: ").append(when(whenMs)).append(" (").append(whenMs).append(")\n")
                .append("reason: ").append(reasonName(reason)).append(" (").append(reason).append(")\n")
                .append("app: ").append(oneLine(versionNow)).append('\n')
                .append("payload: ").append(oneLine(payloadNow)).append('\n')
                .append("previous version: ").append(oneLine(versionBefore)).append('\n')
                .append("host: ").append(oneLine(host)).append('\n')
                // What the dead process was doing: written by it, not read now (see the class
                // comment: app/payload/host at the top are the versions *reading* the report).
                .append("process: ").append(oneLine(process)).append(" (pid ").append(pid).append(")\n")
                .append("state: ").append(state == null || state.isEmpty() ? "(none)" : oneLine(state)).append('\n')
                .append("importance: ").append(importance).append('\n')
                .append("pss: ").append(pssKb > 0 ? (pssKb / 1024) + " MB" : "unknown").append('\n');
        if (description != null && !description.isEmpty()) {
            b.append("description: ").append(oneLine(description)).append('\n');
        }
        b.append("--- trace ---\n");
        String body = trace == null ? "" : trace;
        if (body.length() > TRACE_LIMIT) {
            body = body.substring(0, TRACE_LIMIT) + "\n[cut at " + TRACE_LIMIT + " bytes]\n";
        }
        b.append(body.isEmpty() ? "(none)\n" : body);
        return b.toString();
    }

    /** A report from the app's own handler, before the process dies. */
    static String formatUncaught(long whenMs, String thread, String versionNow, String payloadNow,
                                 String host, String process, int pid, String state, String stack) {
        StringBuilder b = new StringBuilder();
        b.append("Godnotic uncaught exception\n")
                .append("when: ").append(when(whenMs)).append(" (").append(whenMs).append(")\n")
                .append("thread: ").append(oneLine(thread)).append('\n')
                .append("app: ").append(oneLine(versionNow)).append('\n')
                .append("payload: ").append(oneLine(payloadNow)).append('\n')
                .append("host: ").append(oneLine(host)).append('\n')
                .append("process: ").append(oneLine(process)).append(" (pid ").append(pid).append(")\n")
                .append("state: ").append(state == null || state.isEmpty() ? "(none)" : oneLine(state)).append('\n')
                .append("--- stack ---\n")
                .append(stack == null || stack.isEmpty() ? "(none)\n" : stack);
        return b.toString();
    }

    /** One line of text: a report's header lines are key: value, and the trail goes below. */
    static String oneLine(String value) {
        if (value == null) {
            return "";
        }
        String line = value.trim().replaceAll("\\s+", " ");
        return line.length() > 400 ? line.substring(0, 400) + "…" : line;
    }
}
