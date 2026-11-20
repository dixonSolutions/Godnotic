package io.github.dixonsolutions.godnotic;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** CrashReport: which deaths count, how many are kept, what a report says. Plain JVM. */
public class CrashReportTest {
    /**
     * The owner's tablet after 1.2.267 -> 1.2.270 applied: the old process leaving for the
     * handover, reported as a crash. Kept verbatim so the test proves this exact report is not one.
     */
    private static final String UPDATE_HANDOVER_REPORT =
            "Godnotic crash report\n"
            + "when: 2026-11-18 07:42:50 (1794948170117)\n"
            + "reason: exit-self (1)\n"
            + "app: 1.2.270\n"
            + "payload: 1.2.270\n"
            + "previous version: 1.2.267\n"
            + "host: api=3 mode=download_only status={\"trigger\":\"open\",\"state\":\"checking\"}\n"
            + "process: io.github.dixonsolutions.godnotic (pid 8281)\n"
            + "state: app=1.2.267 payload=1.2.267 host=present:download_only stage=game engine=1 phase=quiet\n"
            + "importance: 100\n"
            + "pss: unknown\n"
            + "--- trace ---\n"
            + "(none)\n";

    @Test public void crashesAndAnrsAlwaysCount() {
        for (int reason : new int[] { CrashReport.REASON_CRASH, CrashReport.REASON_CRASH_NATIVE,
                CrashReport.REASON_ANR, CrashReport.REASON_INITIALIZATION_FAILURE }) {
            assertTrue(CrashReport.reasonName(reason), CrashReport.interesting(reason, false));
            assertTrue(CrashReport.reasonName(reason), CrashReport.interesting(reason, true));
        }
    }

    @Test public void aKillAroundAnUpdateCountsAndOtherwiseDoesNot() {
        // The host relaunches the app to apply a live update: the old process is signalled or
        // exits by itself. That is only a report when the version or the payload changed.
        for (int reason : new int[] { CrashReport.REASON_SIGNALED, CrashReport.REASON_EXIT_SELF }) {
            assertFalse(CrashReport.reasonName(reason), CrashReport.interesting(reason, false));
            assertTrue(CrashReport.reasonName(reason), CrashReport.interesting(reason, true));
        }
        // Being swiped away, stopped by the system, or killed for memory is not a bug.
        for (int reason : new int[] { CrashReport.REASON_USER_REQUESTED, CrashReport.REASON_USER_STOPPED,
                CrashReport.REASON_LOW_MEMORY, CrashReport.REASON_FREEZER, CrashReport.REASON_OTHER,
                CrashReport.REASON_PACKAGE_UPDATED }) {
            assertFalse(CrashReport.reasonName(reason), CrashReport.interesting(reason, true));
        }
    }

    @Test public void anUpdateHandoverIsNotReportedAsACrash() {
        String report = UPDATE_HANDOVER_REPORT;
        String state = header(report, "state:");
        assertEquals("1.2.270", header(report, "app:"));
        assertEquals("1.2.267", header(report, "previous version:"));
        assertEquals("quiet", CrashReport.phase(state));
        // The fixture is the old process leaving on purpose: exit-self, phase=quiet, and the
        // version changed from the one it died in to the one reading the report.
        assertFalse(CrashReport.shouldReport(CrashReport.REASON_EXIT_SELF, true, state, true));
        // The same exit outside an update is not interesting either.
        assertFalse(CrashReport.shouldReport(CrashReport.REASON_EXIT_SELF, false, state, false));
        // A real crash in the same quiet phase is still reported: the filter is only for the
        // process leaving for the handover.
        assertTrue(CrashReport.shouldReport(CrashReport.REASON_CRASH, true, state, true));
        assertTrue(CrashReport.shouldReport(CrashReport.REASON_CRASH_NATIVE, true, state, true));
        assertTrue(CrashReport.shouldReport(CrashReport.REASON_ANR, true, state, true));
        assertTrue(CrashReport.shouldReport(CrashReport.REASON_CRASH, true,
                "app=1.2.270 stage=game engine=0 phase=replacing", true));
        // A self-exit in another phase, with no version change, is still the signal it was.
        assertTrue(CrashReport.shouldReport(CrashReport.REASON_EXIT_SELF, true,
                "app=1.2.270 stage=game engine=1 phase=checking", false));
    }

    @Test public void theHostRestartingToApplyAStagedReleaseIsNotACrash() {
        // Owner's tablet 2026-11-20 17:08: app 1.2.289 on an API 3 host, payload 1.2.298 with
        // 1.2.301 staged; the host killed the process once the player left, the next start ran
        // 1.2.301 and reported "exit-self (1)", importance 300.
        String state = "app=1.2.289 payload=1.2.298 host=present:auto stage=none engine=0 phase=staged";
        assertTrue("before: reported as a crash",
                CrashReport.shouldReport(CrashReport.REASON_EXIT_SELF, true, state, false, null));
        assertFalse(CrashReport.shouldReport(CrashReport.REASON_EXIT_SELF, true, state, false, null,
                "1.2.301", 300));
        assertFalse(CrashReport.shouldReport(CrashReport.REASON_SIGNALED, true, state, false, null,
                "1.2.301", 300));
        // On screen with the engine down (host Phoenix relaunch) it is the same handover.
        assertFalse(CrashReport.shouldReport(CrashReport.REASON_EXIT_SELF, true, state, false, null,
                "1.2.301", 100));
        // The release was not promoted: the same payload runs again, so it stays a report.
        assertTrue(CrashReport.shouldReport(CrashReport.REASON_EXIT_SELF, true, state, false, null,
                "1.2.298", 300));
        // A crash or ANR with a release staged is always a report.
        assertTrue(CrashReport.shouldReport(CrashReport.REASON_CRASH, true, state, false, null,
                "1.2.301", 300));
        assertTrue(CrashReport.shouldReport(CrashReport.REASON_ANR, true, state, false, null,
                "1.2.301", 300));
        // The engine exiting in a match on screen while an update waited is still reported.
        String playing = "app=1.2.289 payload=1.2.298 host=present:auto stage=game engine=1 phase=staged";
        assertTrue(CrashReport.shouldReport(CrashReport.REASON_EXIT_SELF, true, playing, false, null,
                "1.2.301", 100));
        assertFalse(CrashReport.shouldReport(CrashReport.REASON_EXIT_SELF, true, playing, false, null,
                "1.2.301", 300));
        // Another phase, or no payload in the state, is not this handover.
        assertTrue(CrashReport.shouldReport(CrashReport.REASON_EXIT_SELF, true,
                state.replace("phase=staged", "phase=checking"), false, null, "1.2.301", 300));
        assertTrue(CrashReport.shouldReport(CrashReport.REASON_EXIT_SELF, true,
                state.replace("payload=1.2.298", "payload=-"), false, null, "1.2.301", 300));
        assertEquals("1.2.298", CrashReport.field(state, "payload"));
        assertEquals("", CrashReport.field(null, "payload"));
    }

    @Test public void swipingTheGameAwayIsNotACrash() {
        // Owner's tablet 2026-11-20: app 1.2.289, payload 1.2.296 -> 1.2.297 (around an update,
        // app version unchanged), "exit-self (1)", description "[REMOVE TASK] remove task".
        String state = "app=1.2.289 payload=1.2.296 host=present:auto stage=none engine=0 phase=uptodate";
        assertTrue("before: reported as a crash",
                CrashReport.shouldReport(CrashReport.REASON_EXIT_SELF, true, state, false));
        assertFalse(CrashReport.shouldReport(CrashReport.REASON_EXIT_SELF, true, state, false,
                "[REMOVE TASK] remove task"));
        assertFalse(CrashReport.shouldReport(CrashReport.REASON_SIGNALED, true, state, false,
                "remove task"));
        assertTrue(CrashReport.closedByPlayer("[REMOVE TASK] remove task"));
        assertFalse(CrashReport.closedByPlayer(null));
        assertFalse(CrashReport.closedByPlayer("crash"));
        // A real crash or ANR stays a report whatever the description says.
        assertTrue(CrashReport.shouldReport(CrashReport.REASON_CRASH, true, state, false,
                "[REMOVE TASK] remove task"));
        assertTrue(CrashReport.shouldReport(CrashReport.REASON_ANR, false, state, false,
                "[REMOVE TASK] remove task"));
        // Another self-exit around an update still is.
        assertTrue(CrashReport.shouldReport(CrashReport.REASON_EXIT_SELF, true, state, false, "exit"));
    }

    @Test public void aSignaledReplaceIsNotReportedButARealSignalIs() {
        // The owner's 1.2.290 -> 1.2.292 banner: signaled (2), phase=replacing, same app version.
        String replacing = "app=1.2.289 payload=1.2.290 host=present:check_only stage=game engine=0 phase=replacing";
        assertFalse(CrashReport.shouldReport(CrashReport.REASON_SIGNALED, true, replacing, false));
        assertFalse(CrashReport.shouldReport(CrashReport.REASON_SIGNALED, true,
                "app=1.2.289 stage=game phase=quiet", false));
        // Killed in any other phase around an update is still a report.
        assertTrue(CrashReport.shouldReport(CrashReport.REASON_SIGNALED, true,
                "app=1.2.289 stage=game engine=1 phase=checking", false));
        assertTrue(CrashReport.shouldReport(CrashReport.REASON_SIGNALED, true, "", true));
        // A crash while replacing is still a crash.
        assertTrue(CrashReport.shouldReport(CrashReport.REASON_CRASH, true, replacing, false));
    }

    @Test public void thePhaseComesOutOfTheStateLine() {
        assertEquals("quiet", CrashReport.phase("app=1.2.267 host=present stage=game phase=quiet"));
        assertEquals("replacing", CrashReport.phase(" phase=replacing "));
        assertEquals("", CrashReport.phase("app=1.2.267 stage=game"));
        assertEquals("", CrashReport.phase("(none)"));
        assertEquals("", CrashReport.phase(null));
    }

    @Test public void reasonNamesAreStableForFileNames() {
        assertEquals("crash", CrashReport.reasonName(CrashReport.REASON_CRASH));
        assertEquals("crash-native", CrashReport.reasonName(CrashReport.REASON_CRASH_NATIVE));
        assertEquals("anr", CrashReport.reasonName(CrashReport.REASON_ANR));
        assertEquals("signaled", CrashReport.reasonName(CrashReport.REASON_SIGNALED));
        assertEquals("reason-99", CrashReport.reasonName(99));
        assertTrue(CrashReport.fileName(1234L, CrashReport.REASON_ANR).matches("report-1234-anr\\.txt"));
    }

    @Test public void onlyTheNewestFiveAreKept() {
        List<String> names = new ArrayList<>(Arrays.asList(
                "report-300-crash.txt", "report-100-crash.txt", "report-500-anr.txt",
                "report-400-uncaught.txt", "report-200-crash-native.txt", "report-600-crash.txt"));
        List<String> kept = CrashReport.keepNewest(names, CrashReport.KEEP);
        assertEquals(5, kept.size());
        assertEquals("report-600-crash.txt", kept.get(0));
        assertEquals("report-500-anr.txt", kept.get(1));
        assertEquals("report-400-uncaught.txt", kept.get(2));
        assertEquals("report-300-crash.txt", kept.get(3));
        assertEquals("report-200-crash-native.txt", kept.get(4));
        // A name of another kind, or a half-written one, is not ours to keep.
        assertEquals(Arrays.asList("report-9-anr.txt"),
                CrashReport.keepNewest(Arrays.asList("notes.txt", "report-x-anr.txt", "report-9-anr.txt"), 5));
        assertTrue(CrashReport.keepNewest(new ArrayList<>(), 5).isEmpty());
    }

    @Test public void timestampsComeOutOfTheFileName() {
        assertEquals(1723456789012L, CrashReport.fileStamp("report-1723456789012-crash.txt"));
        assertEquals(-1, CrashReport.fileStamp("report-.txt"));
        assertEquals(-1, CrashReport.fileStamp("report.txt"));
        assertEquals(-1, CrashReport.fileStamp(null));
    }

    @Test public void theBannerIsForAReportThePlayerHasNotSeen() {
        assertTrue(CrashReport.shouldBanner(2000, 1000));
        assertTrue(CrashReport.shouldBanner(2000, 0));
        assertFalse(CrashReport.shouldBanner(1000, 1000)); // shared or put away already
        assertFalse(CrashReport.shouldBanner(0, 0));
    }

    @Test public void aReportCarriesWhatTheApplyPathNeeds() {
        String report = CrashReport.format(1723456789012L, CrashReport.REASON_CRASH, "NullPointerException",
                100, 512 * 1024, "1.2.259", "1.2.259", "api=3 mode=auto status={state:staged}", "1.2.258",
                "io.github.dixonsolutions.godnotic", 4242, "app=1.2.259 stage=game engine=1 phase=install",
                "java.lang.NullPointerException: boom\n\tat a.b(C.java:1)");
        assertTrue(report.contains("reason: crash (4)"));
        assertTrue(report.contains("app: 1.2.259"));
        assertTrue(report.contains("previous version: 1.2.258"));
        assertTrue(report.contains("payload: 1.2.259"));
        assertTrue(report.contains("host: api=3 mode=auto"));
        // Which process died and what it was doing: read from the dead process, unlike the
        // versions above, which are the ones writing this report.
        assertTrue(report.contains("process: io.github.dixonsolutions.godnotic (pid 4242)"));
        assertTrue(report.contains("state: app=1.2.259 stage=game engine=1 phase=install"));
        assertTrue(report.contains("pss: 512 MB"));
        assertTrue(report.contains("description: NullPointerException"));
        assertTrue(report.contains("NullPointerException: boom"));
        // A trace the system did not keep says so instead of leaving the section empty.
        assertTrue(CrashReport.format(1L, CrashReport.REASON_ANR, "", 0, 0, "v", "p", "", "",
                "", 0, null, "").contains("--- trace ---\n(none)"));
    }

    @Test public void aLongTraceIsCut() {
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < CrashReport.TRACE_LIMIT + 64; i++) {
            huge.append('x');
        }
        String report = CrashReport.format(1L, CrashReport.REASON_CRASH_NATIVE, "", 0, 0, "v", "p", "", "",
                "p", 1, "", huge.toString());
        assertTrue(report.length() < CrashReport.TRACE_LIMIT + 4096);
        assertTrue(report.contains("[cut at " + CrashReport.TRACE_LIMIT + " bytes]"));
    }

    @Test public void anUncaughtExceptionNamesItsThreadAndVersion() {
        String text = CrashReport.formatUncaught(11L, "main", "1.2.259", "1.2.259", "api=1",
                "io.github.dixonsolutions.godnotic", 99, "app=1.2.259 stage=game",
                CrashReport.stack(new IllegalStateException("from a test")));
        assertTrue(text.contains("process: io.github.dixonsolutions.godnotic (pid 99)"));
        assertTrue(text.contains("state: app=1.2.259 stage=game"));
        assertTrue(text.contains("thread: main"));
        assertTrue(text.contains("app: 1.2.259"));
        assertTrue(text.contains("IllegalStateException: from a test"));
        assertTrue(text.contains("at io.github.dixonsolutions.godnotic.CrashReportTest"));
        assertEquals("", CrashReport.stack(null));
    }

    @Test public void anUncaughtReportFindsTheExitReasonOfItsDeath() {
        // The handler names its file with the crash time and the pid; the next start matches it to
        // the system's exit reason for the same process.
        assertEquals("report-1723456789012-uncaught-4242.txt",
                CrashReport.uncaughtName(1723456789012L, 4242));
        assertEquals(4242, CrashReport.uncaughtPid("report-1723456789012-uncaught-4242.txt"));
        assertEquals(1723456789012L, CrashReport.fileStamp(CrashReport.uncaughtName(1723456789012L, 4242)));
        assertEquals(-1, CrashReport.uncaughtPid("report-1723456789012-crash.txt"));
        assertEquals(-1, CrashReport.uncaughtPid("report-1-uncaught-x.txt"));
        assertEquals(-1, CrashReport.uncaughtPid("report-1-uncaught-.txt"));
        assertEquals(-1, CrashReport.uncaughtPid(null));

        // Same pid and a timestamp inside the window: the same death.
        assertTrue(CrashReport.sameDeath(4242, 1723456789012L, 4242, 1723456789100L, 10_000));
        assertTrue(CrashReport.sameDeath(4242, 1723456789012L, 4242, 1723456799012L, 10_000));
        // Another process, another time, or no timestamp at all: not this death.
        assertFalse(CrashReport.sameDeath(4242, 1723456789012L, 4243, 1723456789012L, 10_000));
        assertFalse(CrashReport.sameDeath(4242, 1723456789012L, 4242, 1723456810000L, 10_000));
        assertFalse(CrashReport.sameDeath(0, 0, 0, 0, 10_000));
    }

    @Test public void theSameDeathIsNotReportedTwice() {
        String key = CrashReport.key(4242, 1723456789012L, CrashReport.REASON_CRASH, "crash");
        assertEquals(key, CrashReport.key(4242, 1723456789012L, CrashReport.REASON_CRASH, "crash"));
        assertFalse(key.equals(CrashReport.key(4242, 1723456789012L, CrashReport.REASON_CRASH, "other")));
        assertFalse(key.equals(CrashReport.key(4242, 1723456789013L, CrashReport.REASON_CRASH, "crash")));
        assertFalse(key.equals(CrashReport.key(4243, 1723456789012L, CrashReport.REASON_CRASH, "crash")));
    }

    /** One top-level line of a report, without the key. */
    private static String header(String report, String key) {
        for (String line : report.split("\n")) {
            if (line.startsWith(key)) {
                return line.substring(key.length()).trim();
            }
        }
        throw new AssertionError("no " + key + " line in the report");
    }

    @Test public void headerValuesStayOnOneLine() {
        assertEquals("a b c", CrashReport.oneLine(" a\nb\r c "));
        assertEquals("", CrashReport.oneLine(null));
        assertTrue(CrashReport.oneLine(new String(new char[500]).replace('\0', 'y')).endsWith("…"));
    }

    private static String report(String app, String payload, String diedApp) {
        return "Godnotic crash report\nwhen: x (1)\nreason: crash (4)\napp: " + app + "\npayload: " + payload
                + "\nstate: app=" + diedApp + " payload=" + diedApp + " phase=run\n--- trace ---\n(none)\n";
    }

    @Test public void bannerOnlyInTheMenuForANewCurrentVersionReport() {
        String now = report("1.2.286", "1.2.286", "1.2.286");
        assertTrue(CrashReport.shouldShowBanner(true, 500, 100, now, "1.2.286", "1.2.286"));
        // in a match: never
        assertFalse(CrashReport.shouldShowBanner(false, 500, 100, now, "1.2.286", "1.2.286"));
        // already dismissed
        assertFalse(CrashReport.shouldShowBanner(true, 500, 500, now, "1.2.286", "1.2.286"));
        assertFalse(CrashReport.shouldShowBanner(true, 0, 0, now, "1.2.286", "1.2.286"));
    }

    @Test public void aCrashOfAnOlderVersionIsNotShown() {
        String old = report("1.2.270", "1.2.270", "1.2.267");
        assertFalse(CrashReport.shouldShowBanner(true, 500, 100, old, "1.2.286", "1.2.286"));
        // died in the old version, read by the first start of the new one
        String after = report("1.2.286", "1.2.286", "1.2.285");
        assertTrue(CrashReport.currentVersion(after, "1.2.286", "1.2.286"));
        assertFalse(CrashReport.currentVersion(old, "", ""));
    }

    @Test public void bannerSitsBelowTheScreenHeader() {
        assertTrue(CrashReport.BANNER_TOP_DP >= CrashReport.HEADER_BOTTOM_DP);
    }

    @Test public void bannerLeavesAfterAboutEightSeconds() {
        assertEquals(8000, CrashReport.BANNER_MS);
    }
}
