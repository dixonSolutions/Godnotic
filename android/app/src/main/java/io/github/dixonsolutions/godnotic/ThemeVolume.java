package io.github.dixonsolutions.godnotic;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

/**
 * The volume the menu theme should use on the boot screen, read from the same
 * {@code config.cfg} the engine will read. The engine multiplies master,
 * music and CD volume; the boot player does the same (clamped to 0..1). A
 * missing or unreadable config means the defaults (full volume).
 */
final class ThemeVolume {

    private ThemeVolume() {
    }

    /** The product of the volume cvars in {@code text}; 1 for no settings. */
    static float parse(String text) {
        float master = 1, music = 1, cd = 1;
        if (text == null) {
            return 1;
        }
        for (String raw : text.split("\n")) {
            int comment = raw.indexOf("//");
            if (comment >= 0) {
                raw = raw.substring(0, comment);
            }
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            String[] parts = line.split("\\s+");
            int at = 0;
            if ("set".equals(parts[0]) || "seta".equals(parts[0])) {
                at = 1;
            }
            if (parts.length < at + 2) {
                continue;
            }
            String name = parts[at];
            String value = parts[at + 1].replace("\"", "");
            float v;
            try {
                v = Float.parseFloat(value);
            } catch (NumberFormatException e) {
                continue;
            }
            v = Math.max(0, Math.min(1, v));
            if ("mastervolume".equals(name)) {
                master = v;
            } else if ("bgmvolume".equals(name)) {
                music = v;
            } else if ("cdvolume".equals(name)) {
                cd = v;
            }
        }
        return Math.max(0, Math.min(1, master * music * cd));
    }

    /** The last value wins, as it does when the engine execs the config. */
    static float of(File... files) {
        StringBuilder all = new StringBuilder();
        for (File file : files) {
            if (file == null || !file.isFile()) {
                continue;
            }
            try (FileInputStream in = new FileInputStream(file)) {
                byte[] buf = new byte[64 * 1024];
                int n = in.read(buf);
                if (n > 0) {
                    all.append(new String(buf, 0, n, java.nio.charset.StandardCharsets.UTF_8)).append('\n');
                }
            } catch (IOException | RuntimeException e) {
                // A config that cannot be read is not a reason to be loud.
            }
        }
        return parse(all.toString());
    }
}
