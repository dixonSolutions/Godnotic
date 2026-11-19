package io.github.dixonsolutions.godnotic;

import java.io.File;

/** Exercise the production durable trial record across a real process kill. */
public final class SwapKillHarness {
    public static void main(String[] args) throws Exception {
        File dir = new File(args[1]);
        if ("begin".equals(args[0])) {
            // A staged release waits through a match and its busy hold.
            if (AndySUState.safeToApplyNow("game", true, "busy", true, 0)
                    || AndySUState.shouldApplyLive("staged", true, "live", true)
                    || !"next_start".equals(AndySUState.desiredPolicy(true, false, 20000, 1000)))
                throw new AssertionError("update allowed during match or busy hold");
            if (!AndySUState.safeToApplyNow("game", false, "safe", true, 31000)
                    || !AndySUState.shouldApplyLive("staged", false, "live", true)
                    || !"live".equals(AndySUState.desiredPolicy(true, false, 31001, 1000)))
                throw new AssertionError("staged update did not become eligible after match");
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
