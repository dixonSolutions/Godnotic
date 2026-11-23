package io.github.dixonsolutions.godnotic;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

/**
 * How a staged release reaches the running app (LiveApply): plain JVM. The fixture is the real
 * arm64 channel (update.json, sequence 151): 1.2.293 -> 1.2.294, the kind of release the owner
 * pressed Apply live on. Its dex changed, so the host reported native_live false and the app took
 * the restart path, which closed the app.
 */
public class LiveApplyTest {
    private static final String[] R_1_2_293 = {
            "andysu-payload.json", "d28d1e4cb4338095",
            "assets/dexopt/baseline.prof", "47d190374a2b991e",
            "assets/dexopt/baseline.profm", "7678f8bab820ba1a",
            "assets/fe/home.json", "e5648a4d5c241ba5",
            "assets/fe/pause.json", "084bae489909c29a",
            "assets/godnotic-data-full-url.txt", "8f51d3e9f46e5b8d",
            "assets/godnotic-data-url.txt", "2344513ccadc7494",
            "assets/xonotic-slim-data.zip", "0765d8e958b0e549",
            "assets/xt/godnotic-banner.png", "6c524dd636ff8a53",
            "assets/xt/strings.xml", "3ca618a54e299c78",
            "dex/classes.dex", "d33eafa0658fbff5",
            "dex/resources.jar", "f834242a1fb2c8c2",
            "lib/arm64-v8a/libSDL2.so", "157d7ccabe5900eb",
            "lib/arm64-v8a/libandysu_xonotic_loader.so", "2564a794d8f6b7aa",
            "lib/arm64-v8a/libcurl.so", "3b098fee4b67c6b3",
            "lib/arm64-v8a/libd0_blind_id.so", "d4ec3fff8ec43a0c",
            "lib/arm64-v8a/libd0_rijndael.so", "466403791938e107",
            "lib/arm64-v8a/libgodnotic_android.so", "633010a16d62eba8",
            "lib/arm64-v8a/libmain.so", "df6ded3fbf823fa3",
            "lib/arm64-v8a/libnativeui.so", "e044cc67ebc77ae5",
            "lib/arm64-v8a/libpng16.so", "a7746cd071d6b127",
    };
    private static final String[] R_1_2_294 = {
            "andysu-payload.json", "e16857634b02abdf",
            "assets/dexopt/baseline.prof", "48f3594a4a9a49f2",
            "assets/dexopt/baseline.profm", "5ff39175985fa0fa",
            "assets/fe/home.json", "e5648a4d5c241ba5",
            "assets/fe/pause.json", "084bae489909c29a",
            "assets/godnotic-data-full-url.txt", "8f51d3e9f46e5b8d",
            "assets/godnotic-data-url.txt", "2344513ccadc7494",
            "assets/xonotic-slim-data.zip", "158ee40fdade3b14",
            "assets/xt/godnotic-banner.png", "6c524dd636ff8a53",
            "assets/xt/strings.xml", "3ca618a54e299c78",
            "dex/classes.dex", "a39a405aaeccb083",
            "dex/resources.jar", "f834242a1fb2c8c2",
            "lib/arm64-v8a/libSDL2.so", "157d7ccabe5900eb",
            "lib/arm64-v8a/libandysu_xonotic_loader.so", "d16dc9c9b95d2d24",
            "lib/arm64-v8a/libcurl.so", "3b098fee4b67c6b3",
            "lib/arm64-v8a/libd0_blind_id.so", "d4ec3fff8ec43a0c",
            "lib/arm64-v8a/libd0_rijndael.so", "466403791938e107",
            "lib/arm64-v8a/libgodnotic_android.so", "633010a16d62eba8",
            "lib/arm64-v8a/libmain.so", "9692c812dac8fb81",
            "lib/arm64-v8a/libnativeui.so", "61ec774fc624202f",
            "lib/arm64-v8a/libpng16.so", "a7746cd071d6b127",
    };

    private static Map<String, String> index(String[] pairs) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) m.put(pairs[i], pairs[i + 1]);
        return m;
    }

    private static Map<String, String> with(String[] pairs, String path, String sha) {
        Map<String, String> m = index(pairs);
        m.put(path, sha);
        return m;
    }

    @Test public void classifiesPayloadFiles() {
        assertEquals(LiveApply.Part.ENGINE, LiveApply.classify("lib/arm64-v8a/libmain.so"));
        assertEquals(LiveApply.Part.ENGINE, LiveApply.classify("lib/arm64-v8a/libmain.0a1b2c3d.so"));
        assertEquals(LiveApply.Part.ENGINE, LiveApply.classify("lib/arm64-v8a/libcurl.so"));
        assertEquals(LiveApply.Part.ENGINE, LiveApply.classify("lib/x86_64/libd0_rijndael.so"));
        assertEquals(LiveApply.Part.RESIDENT, LiveApply.classify("lib/arm64-v8a/libnativeui.so"));
        assertEquals(LiveApply.Part.RESIDENT, LiveApply.classify("lib/arm64-v8a/libandysu_xonotic_loader.so"));
        assertEquals(LiveApply.Part.RESIDENT, LiveApply.classify("lib/arm64-v8a/libSDL2.so"));
        assertEquals(LiveApply.Part.RESIDENT, LiveApply.classify("lib/arm64-v8a/libgodnotic_android.so"));
        assertEquals(LiveApply.Part.DATA, LiveApply.classify("assets/xonotic-slim-data.zip"));
        assertEquals(LiveApply.Part.JAVA, LiveApply.classify("dex/classes.dex"));
        assertEquals(LiveApply.Part.JAVA, LiveApply.classify("dex/resources.jar"));
        assertEquals(LiveApply.Part.JAVA, LiveApply.classify("assets/dexopt/baseline.prof"));
        assertEquals(LiveApply.Part.OTHER, LiveApply.classify("assets/fe/home.json"));
        assertEquals(LiveApply.Part.IGNORED, LiveApply.classify("andysu-payload.json"));
    }

    @Test public void release294ChangesEngineDataAndJava() {
        LiveApply.Changes c = LiveApply.Changes.between(index(R_1_2_293), index(R_1_2_294));
        assertTrue(c.known);
        assertTrue(c.engine);
        assertTrue(c.data);
        assertTrue(c.java);
        assertTrue(c.resident); // loader and libnativeui
        assertFalse(c.other);
        assertTrue(c.swappable());
        assertTrue(c.needsStart());
    }

    /** The owner's case: dex changed (not native_live), and Apply live must stay in the process. */
    @Test public void dexChangeStillSwapsEngineAndDataLive() {
        LiveApply.Changes c = LiveApply.Changes.between(index(R_1_2_293), index(R_1_2_294));
        LiveApply.How how = LiveApply.how("1.2.294", false, true, false, true, c, "");
        assertEquals(LiveApply.How.ENGINE_LIVE, how);
        assertTrue(LiveApply.isLive(how));
        assertEquals("engine", LiveApply.token(how));
        assertTrue(LiveApply.why(how, c, null, true).contains("next time Godnotic starts"));
    }

    /**
     * The bridge's gate for the automatic apply and Apply live, fed what the bridge now feeds it.
     * Before this change it was fed the host's native_live (false for this release), so the
     * release went to the restart path.
     */
    @Test public void ownersReleasePassesTheBridgeGate() {
        LiveApply.Changes c = LiveApply.Changes.between(index(R_1_2_293), index(R_1_2_294));
        boolean nativeLive = false; // the core's verdict for 1.2.293 -> 1.2.294 (dex changed)
        boolean swappable = LiveApply.isLive(LiveApply.how("1.2.294", false, true, nativeLive, true, c, ""));
        assertTrue(AndySUState.shouldApplyLive("staged", false, "live", swappable));
        assertFalse("never in a match", AndySUState.shouldApplyLive("staged", true, "live", swappable));
    }

    @Test public void nativeOnlyReleaseUsesTheHostsOwnLiveSwap() {
        Map<String, String> next = with(R_1_2_293, "lib/arm64-v8a/libmain.so", "ffff");
        LiveApply.Changes c = LiveApply.Changes.between(index(R_1_2_293), next);
        assertFalse(c.needsStart());
        assertEquals(LiveApply.How.HOST_LIVE, LiveApply.how("1.2.295", false, true, true, true, c, ""));
        assertEquals("It swaps in while you stay where you are.",
                LiveApply.why(LiveApply.How.HOST_LIVE, c, null, true));
        // Without a way into the host's live apply that cannot fall back to a relaunch, the app
        // swaps the engine itself rather than restarting.
        assertEquals(LiveApply.How.ENGINE_LIVE, LiveApply.how("1.2.295", false, true, true, false, c, ""));
    }

    @Test public void dataOnlyReleaseSwapsLive() {
        Map<String, String> next = with(R_1_2_293, "assets/xonotic-slim-data.zip", "eeee");
        LiveApply.Changes c = LiveApply.Changes.between(index(R_1_2_293), next);
        assertTrue(c.data);
        assertFalse(c.engine);
        assertEquals(LiveApply.How.ENGINE_LIVE, LiveApply.how("1.2.295", false, true, false, true, c, ""));
    }

    /** Only the Java side changed: nothing to swap, so it says so and waits; it never restarts by itself. */
    @Test public void javaOnlyReleaseIsARestartTheUiNames() {
        Map<String, String> next = with(R_1_2_293, "dex/classes.dex", "dddd");
        next.put("lib/arm64-v8a/libnativeui.so", "cccc");
        next.put("assets/fe/home.json", "bbbb");
        LiveApply.Changes c = LiveApply.Changes.between(index(R_1_2_293), next);
        assertFalse(c.swappable());
        LiveApply.How how = LiveApply.how("1.2.295", false, true, false, true, c, "");
        assertEquals(LiveApply.How.RESTART, how);
        assertFalse(LiveApply.isLive(how));
        assertEquals("restart", LiveApply.token(how));
        assertTrue(LiveApply.why(how, c, null, true).contains("cannot be swapped in while it runs"));
    }

    @Test public void engineAlreadySwappedWaitsForTheJavaPart() {
        LiveApply.Changes c = LiveApply.Changes.between(index(R_1_2_293), index(R_1_2_294));
        LiveApply.How how = LiveApply.how("1.2.294", false, true, false, true, c, "1.2.294");
        assertEquals(LiveApply.How.ENGINE_DONE, how);
        assertFalse(LiveApply.isLive(how)); // never swapped twice
        // a newer release staged behind it swaps again
        assertEquals(LiveApply.How.ENGINE_LIVE, LiveApply.how("1.2.295", false, true, false, true, c, "1.2.294"));
    }

    @Test public void liveOffAndShellUpdate() {
        LiveApply.Changes c = LiveApply.Changes.between(index(R_1_2_293), index(R_1_2_294));
        assertEquals(LiveApply.How.RESTART, LiveApply.how("1.2.294", false, false, false, true, c, ""));
        assertTrue(LiveApply.why(LiveApply.How.RESTART, c, null, false).startsWith("Apply live is off"));
        assertEquals(LiveApply.How.INSTALL, LiveApply.how("1.2.294", true, true, false, true, c, ""));
        assertEquals(LiveApply.How.NONE, LiveApply.how("", false, true, false, true, c, ""));
    }

    /** No running index (the host's built-in payload): treat everything as changed, swap what can be. */
    @Test public void unknownRunningSlotSwapsEngineAndWaitsForJava() {
        LiveApply.Changes c = LiveApply.Changes.between(null, index(R_1_2_294));
        assertFalse(c.known);
        assertEquals(LiveApply.How.ENGINE_LIVE, LiveApply.how("1.2.294", false, true, false, true, c, ""));
    }

    @Test public void blockedLiveSaysWhy() {
        LiveApply.Changes c = LiveApply.Changes.between(index(R_1_2_293), index(R_1_2_294));
        assertEquals("Live apply is waiting: the game is connected to a server.",
                LiveApply.why(LiveApply.How.ENGINE_LIVE, c, "the game is connected to a server", true));
        assertTrue(LiveApply.refused("java_abi 2 != running 3").contains("java_abi 2 != running 3"));
        assertTrue(LiveApply.refused(null).contains("Restart now"));
    }

    @Test
    public void engineUpIgnoresTheScreenStage() {
        // In the menu after the launcher icon passed the boot screen: still swappable.
        assertTrue(LiveApply.engineUp(true, true, true));
        assertFalse(LiveApply.engineUp(false, true, true));
        assertFalse(LiveApply.engineUp(true, false, true));
        // The package is being replaced (ReplaceGuard cleared the engine flag): no swap.
        assertFalse(LiveApply.engineUp(true, true, false));
    }
}
