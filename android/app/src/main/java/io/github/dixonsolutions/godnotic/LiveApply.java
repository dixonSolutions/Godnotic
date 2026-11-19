package io.github.dixonsolutions.godnotic;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * How a staged AndySU release gets into the running app, decided from what it changes. Plain JVM
 * (no Android classes), tested by LiveApplyTest.
 *
 * <p>The host (AndySU, transparent mode) swaps a release in-process only when it is
 * {@code native_live}: nothing under {@code dex/} differs from the running slot. Nearly every
 * Godnotic release changes the Java/Kotlin code, so the host called every one of them
 * "not live", and Apply live fell through to the restart path, which finishes the screens and asks
 * the host to relaunch the process: the app closed. The engine and its game data do not depend on
 * the Java code's version, only on the small contract the loader checks ({@code java_abi}), so they
 * can be swapped in the running process even when the Java code changed: the new engine and data
 * run at once, and the release's Java/Kotlin code (and the libraries Java loads) take over at the
 * next start, when the host promotes the staged slot as usual.
 *
 * <p>A restart is never chosen silently: {@link How#RESTART} is shown to the player as such, with
 * the reason, and only the player's explicit Restart now carries it out.
 */
final class LiveApply {
    private LiveApply() {}

    /** What a payload file is to a running app. */
    enum Part {
        /** The swappable engine (libmain) and the libraries only it loads (curl, png, d0). */
        ENGINE,
        /** The game data bundle the engine runs on. */
        DATA,
        /** Java/Kotlin code: the dex, its resources and its baseline profile. */
        JAVA,
        /** Native libraries loaded by Java or resident for the process: they change with Java. */
        RESIDENT,
        /** Other assets, read by the Java side: they change with Java. */
        OTHER,
        /** The payload descriptor, which always differs (its version). */
        IGNORED,
    }

    /** Libraries that Java loads (System.loadLibrary) or that stay resident across engine swaps. */
    private static final Set<String> RESIDENT_LIBS = new HashSet<>(java.util.Arrays.asList(
            "libandysu_xonotic_loader", "libSDL2", "libnativeui", "libgodnotic_android"));

    static final String DATA_BUNDLE = "assets/xonotic-slim-data.zip";

    static Part classify(String path) {
        if (path == null || path.isEmpty() || "andysu-payload.json".equals(path)) return Part.IGNORED;
        if (path.startsWith("dex/") || path.startsWith("assets/dexopt/")) return Part.JAVA;
        if (DATA_BUNDLE.equals(path)) return Part.DATA;
        if (path.startsWith("lib/") && path.endsWith(".so")) {
            String name = path.substring(path.lastIndexOf('/') + 1);
            name = name.substring(0, name.length() - 3);
            int dot = name.indexOf('.'); // libmain.<hash8>.so after a rename
            if (dot > 0) name = name.substring(0, dot);
            if ("libmain".equals(name)) return Part.ENGINE;
            return RESIDENT_LIBS.contains(name) ? Part.RESIDENT : Part.ENGINE;
        }
        return Part.OTHER;
    }

    /** What differs between the running slot and the staged one. */
    static final class Changes {
        boolean engine, data, java, resident, other;
        /** False when the running slot's index could not be read: everything counts as changed. */
        boolean known = true;

        static Changes unknown() {
            Changes c = new Changes();
            c.known = false;
            c.engine = c.data = c.java = c.resident = c.other = true;
            return c;
        }

        /** Both maps: payload path to sha256, from each slot's {@code .andysu-slot.json}. */
        static Changes between(Map<String, String> running, Map<String, String> staged) {
            if (running == null || staged == null || running.isEmpty() || staged.isEmpty()) return unknown();
            Changes c = new Changes();
            Set<String> paths = new HashSet<>(running.keySet());
            paths.addAll(staged.keySet());
            for (String path : paths) {
                String a = running.get(path), b = staged.get(path);
                if (a != null && a.equals(b)) continue;
                c.mark(classify(path));
            }
            return c;
        }

        void mark(Part part) {
            switch (part) {
                case ENGINE: engine = true; break;
                case DATA: data = true; break;
                case JAVA: java = true; break;
                case RESIDENT: resident = true; break;
                case OTHER: other = true; break;
                default: break;
            }
        }

        /** Something only a new process picks up: Java code, Java-loaded libraries, Java's assets. */
        boolean needsStart() {
            return java || resident || other;
        }

        /** Something the running process can swap in: the engine or its game data. */
        boolean swappable() {
            return engine || data;
        }
    }

    enum How {
        /** Nothing staged. */
        NONE,
        /** The host APK itself must be replaced (Android's installer asks the player). */
        INSTALL,
        /** Native/content only: the host's own live swap (its trial, commit and rollback). */
        HOST_LIVE,
        /** The engine and data swap in this process now; the Java part waits for the next start. */
        ENGINE_LIVE,
        /** The release's engine and data already run in this process; the Java part is waiting. */
        ENGINE_DONE,
        /** Nothing in it can be swapped in this process: next start, or the player's Restart now. */
        RESTART,
    }

    /**
     * @param staged        the staged release, "" when none
     * @param shellUpdate   the host reports that its APK must be replaced first
     * @param liveEnabled   Settings &gt; Updates, Apply live
     * @param nativeLive    the core says the host can swap it itself (no dex change)
     * @param hostEntry     the host's in-process apply can be reached without its restart fallback
     * @param changes       what differs from the running slot
     * @param engineRelease the release whose engine and data this process already swapped in, or ""
     */
    static How how(String staged, boolean shellUpdate, boolean liveEnabled, boolean nativeLive,
                   boolean hostEntry, Changes changes, String engineRelease) {
        if (shellUpdate) return How.INSTALL;
        if (staged == null || staged.isEmpty()) return How.NONE;
        if (staged.equals(engineRelease)) return How.ENGINE_DONE;
        if (!liveEnabled) return How.RESTART;
        if (nativeLive && hostEntry) return How.HOST_LIVE;
        if (changes == null) changes = Changes.unknown();
        // native_live without the host's entry point is still an engine/data swap, from the app.
        if (nativeLive || changes.swappable()) return How.ENGINE_LIVE;
        return How.RESTART;
    }

    /** Whether Apply live may swap the release in this process (never a restart). */
    static boolean isLive(How how) {
        return how == How.HOST_LIVE || how == How.ENGINE_LIVE;
    }

    /** The token the Updates screen reads (status extra {@code apply_how}). */
    static String token(How how) {
        switch (how) {
            case HOST_LIVE: return "live";
            case ENGINE_LIVE: return "engine";
            case ENGINE_DONE: return "done";
            case RESTART: return "restart";
            case INSTALL: return "install";
            default: return "";
        }
    }

    /**
     * One line for the Updates screen under the staged release.
     *
     * @param blocked why a live swap cannot run now (EngineSwap.liveBlockedReason), or null
     */
    static String why(How how, Changes changes, String blocked, boolean liveEnabled) {
        switch (how) {
            case HOST_LIVE:
                return blocked != null ? "Live apply is waiting: " + blocked + "."
                        : "It swaps in while you stay where you are.";
            case ENGINE_LIVE:
                if (blocked != null) return "Live apply is waiting: " + blocked + ".";
                return changes != null && changes.needsStart()
                        ? "The game and its data swap in while you stay where you are; the app's own screens update the next time Godnotic starts."
                        : "It swaps in while you stay where you are.";
            case ENGINE_DONE:
                return "The new game and data are running. The app's own screens update the next time Godnotic starts.";
            case RESTART:
                if (!liveEnabled) return "Apply live is off: it applies the next time Godnotic starts.";
                return "This update only changes the app itself, which cannot be swapped in while it runs. "
                        + "It applies the next time Godnotic starts.";
            default:
                return "";
        }
    }

    /**
     * What the app does when a live swap that was possible a moment ago is refused at the last step
     * (the loader's check, the host, a game that started meanwhile): say why and wait, never restart.
     */
    static String refused(String reason) {
        String r = reason == null || reason.trim().isEmpty() ? "it was refused" : reason.trim();
        return "Could not apply the update live (" + r + "). It applies the next time Godnotic starts, or use Restart now.";
    }
}
