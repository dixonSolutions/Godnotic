package io.github.dixonsolutions.godnotic;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** The boot theme must obey the same settings as the engine's music. */
public class ThemeVolumeTest {

    @Test
    public void defaultsToFullVolume() {
        assertEquals(1f, ThemeVolume.parse(""), 0.0001f);
        assertEquals(1f, ThemeVolume.parse(null), 0.0001f);
    }

    @Test
    public void readsEngineCvarsAndLastValueWins() {
        assertEquals(0.25f, ThemeVolume.parse(
                "seta bgmvolume \"0.5\"\n"
                        + "seta mastervolume \"1\"\n"
                        + "seta cdvolume \"0.5\"\n"), 0.0001f);
        assertEquals(0.4f, ThemeVolume.parse(
                "bgmvolume 0.2\n"
                        + "bgmvolume \"0.4\"\n"), 0.0001f);
        assertEquals(0f, ThemeVolume.parse("seta bgmvolume \"0\"\n"), 0.0001f);
        assertEquals(1f, ThemeVolume.parse("seta bgmvolume 2\n"), 0.0001f);
        assertEquals(0f, ThemeVolume.parse("seta mastervolume -1\n"), 0.0001f);
    }

    @Test
    public void ignoresCommentsAndBrokenNumbers() {
        assertEquals(0.75f, ThemeVolume.parse(
                "// seta bgmvolume 0\n"
                        + "seta bgmvolume \"0.75\" // archived\n"
                        + "seta bgmvolume nope\n"), 0.0001f);
    }
}
