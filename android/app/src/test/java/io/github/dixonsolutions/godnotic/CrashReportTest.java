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
                "java.lang.NullPointerException: boom\n\tat a.b(C.java:1)");
        assertTrue(report.contains("reason: crash (4)"));
        assertTrue(report.contains("app: 1.2.259"));
        assertTrue(report.contains("previous version: 1.2.258"));
        assertTrue(report.contains("payload: 1.2.259"));
        assertTrue(report.contains("host: api=3 mode=auto"));
        assertTrue(report.contains("pss: 512 MB"));
        assertTrue(report.contains("description: NullPointerException"));
        assertTrue(report.contains("NullPointerException: boom"));
        // A trace the system did not keep says so instead of leaving the section empty.
        assertTrue(CrashReport.format(1L, CrashReport.REASON_ANR, "", 0, 0, "v", "p", "", "",
                "").contains("--- trace ---\n(none)"));
    }

    @Test public void aLongTraceIsCut() {
        StringBuilder huge = new StringBuilder();
        for (int i = 0; i < CrashReport.TRACE_LIMIT + 64; i++) {
            huge.append('x');
        }
        String report = CrashReport.format(1L, CrashReport.REASON_CRASH_NATIVE, "", 0, 0, "v", "p", "", "",
                huge.toString());
        assertTrue(report.length() < CrashReport.TRACE_LIMIT + 4096);
        assertTrue(report.contains("[cut at " + CrashReport.TRACE_LIMIT + " bytes]"));
    }

    @Test public void anUncaughtExceptionNamesItsThreadAndVersion() {
        String text = CrashReport.formatUncaught(11L, "main", "1.2.259", "1.2.259", "api=1",
                CrashReport.stack(new IllegalStateException("from a test")));
        assertTrue(text.contains("thread: main"));
        assertTrue(text.contains("app: 1.2.259"));
        assertTrue(text.contains("IllegalStateException: from a test"));
        assertTrue(text.contains("at io.github.dixonsolutions.godnotic.CrashReportTest"));
        assertEquals("", CrashReport.stack(null));
    }

    @Test public void headerValuesStayOnOneLine() {
        assertEquals("a b c", CrashReport.oneLine(" a\nb\r c "));
        assertEquals("", CrashReport.oneLine(null));
        assertTrue(CrashReport.oneLine(new String(new char[500]).replace('\0', 'y')).endsWith("…"));
    }
}
