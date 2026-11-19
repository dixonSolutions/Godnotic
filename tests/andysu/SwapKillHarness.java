package io.github.dixonsolutions.godnotic;

import java.io.File;

/** Exercise the production durable trial record across a real process kill. */
public final class SwapKillHarness {
    public static void main(String[] args) throws Exception {
        File dir = new File(args[1]);
        if ("begin".equals(args[0])) {
            if (!SwapTrial.begin(dir, "device")) throw new AssertionError("trial write failed");
            System.out.println("trial durable");
            System.out.flush();
            Thread.sleep(60000);
        } else {
            if (!SwapTrial.trial(dir, "device")) throw new AssertionError("trial lost on kill");
            SwapTrial.failed(dir, "device", 1000, "v2");
            if (SwapTrial.trial(dir, "device") || SwapTrial.attempts(dir, "device") != 1
                    || SwapTrial.retryAfter(dir, "device", 1000) <= 0)
                throw new AssertionError("failed trial not retained");
            if (CrashReport.shouldReport(CrashReport.REASON_SIGNALED, true, "phase=replacing", true))
                throw new AssertionError("handover signal reported as crash");
            if (!CrashReport.shouldReport(CrashReport.REASON_CRASH_NATIVE, true, "phase=replacing", true))
                throw new AssertionError("native crash suppressed");
            System.out.println("kill recovery passed");
        }
    }
}
