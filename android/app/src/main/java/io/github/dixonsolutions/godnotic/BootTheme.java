package io.github.dixonsolutions.godnotic;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Comparator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Plays the menu theme on the boot/download screen while no engine is running.
 *
 * <p>The theme is extracted from the installed music pk3 into the app cache and
 * played with a looping {@link MediaPlayer}. {@link BootActivity} starts this
 * only while the download screen is visible, refreshes it as packs land, and
 * stops it just before the engine takes over. Once the engine is alive,
 * {@code TouchMusic_Update} owns the theme; this class never plays beside it.
 *
 * <p>No audio is started when the engine's own config mutes music. A missing
 * pack, a pack without the theme, or a decoder that refuses the file simply
 * leaves the screen silent; it must never break the download.
 */
final class BootTheme {

    private static final String TAG = "Godnotic";
    private static final String THEME = "sound/cdtracks/rising-of-the-phoenix";
    private static final String[] EXTENSIONS = { "ogg", "opus" };

    private final Context context;
    private final File baseDir;
    private final File dataDir;
    private final Handler main = new Handler(Looper.getMainLooper());

    private MediaPlayer player;
    private boolean wanted;
    private boolean loading;
    private long nextTry;

    BootTheme(Context context, File baseDir, File dataDir) {
        this.context = context.getApplicationContext();
        this.baseDir = baseDir;
        this.dataDir = dataDir;
    }

    /** Show the screen (or the download start): play as soon as the pack is there. */
    void start() {
        wanted = true;
        ensure();
    }

    /** A progress tick: the music pack may have just been renamed into place. */
    void refresh() {
        if (wanted) {
            ensure();
        }
    }

    /** Leaving the screen, or handing over to the engine. */
    void stop() {
        wanted = false;
        releasePlayer();
    }

    void release() {
        stop();
    }

    private void ensure() {
        if (!wanted || player != null || loading) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (now < nextTry) {
            return;
        }
        nextTry = now + 1000;
        loading = true;
        new Thread(() -> {
            File theme = extract();
            main.post(() -> {
                loading = false;
                if (!wanted || theme == null) {
                    return;
                }
                play(theme);
            });
        }, "godnotic-boot-music").start();
    }

    /** The theme file from the newest installed music pk3, or null. */
    private File extract() {
        File[] packs = dataDir.listFiles((dir, name) ->
                name.endsWith("-music.pk3") && new File(dir, name).isFile());
        if (packs == null || packs.length == 0) {
            return null;
        }
        Arrays.sort(packs, Comparator.comparingLong(File::lastModified).reversed());
        for (File pack : packs) {
            for (String extension : EXTENSIONS) {
                File out = extract(pack, extension);
                if (out != null) {
                    return out;
                }
            }
        }
        return null;
    }

    private File extract(File pack, String extension) {
        String name = THEME + "." + extension;
        try (ZipFile zip = new ZipFile(pack)) {
            ZipEntry entry = zip.getEntry(name);
            if (entry == null || entry.isDirectory()) {
                return null;
            }
            File cacheDir = context.getCacheDir();
            if (cacheDir == null) {
                return null;
            }
            if (!cacheDir.isDirectory() && !cacheDir.mkdirs()) {
                return null;
            }
            File out = new File(cacheDir, "boot-theme-" + pack.length() + "-"
                    + pack.lastModified() + "." + extension);
            if (out.isFile() && out.length() == entry.getSize()) {
                return out;
            }
            File part = new File(out.getPath() + ".part");
            try (InputStream in = zip.getInputStream(entry);
                 FileOutputStream sink = new FileOutputStream(part)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    sink.write(buf, 0, n);
                }
            }
            if ((entry.getSize() >= 0 && part.length() != entry.getSize()) || !part.renameTo(out)) {
                part.delete();
                return null;
            }
            return out;
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "Could not read the boot theme from " + pack.getName(), e);
            return null;
        }
    }

    private void play(File file) {
        if (!wanted) {
            return;
        }
        float volume = ThemeVolume.of(
                new File(new File(GameData.userDir(baseDir), "data"), "config.cfg"),
                new File(new File(baseDir, "data"), "config.cfg"));
        if (volume <= 0f) {
            return; // the player muted music: no silent MediaPlayer to leak
        }
        MediaPlayer mp = new MediaPlayer();
        try {
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
            mp.setDataSource(file.getAbsolutePath());
            mp.setLooping(true);
            mp.setVolume(volume, volume);
            mp.setOnErrorListener((ignored, what, extra) -> {
                releasePlayer();
                return true;
            });
            mp.prepare();
            if (!wanted) {
                mp.release();
                return;
            }
            mp.start();
            player = mp;
            Log.i(TAG, "Boot theme playing from " + file.getName() + " at " + volume);
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "Could not play the boot theme", e);
            mp.release();
            nextTry = SystemClock.elapsedRealtime() + 10_000;
        }
    }

    private void releasePlayer() {
        MediaPlayer mp = player;
        player = null;
        if (mp != null) {
            try {
                mp.stop();
            } catch (RuntimeException ignored) {
                // already stopped or never started
            }
            mp.release();
        }
    }
}
