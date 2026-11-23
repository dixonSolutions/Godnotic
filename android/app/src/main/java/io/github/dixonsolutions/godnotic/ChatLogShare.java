package io.github.dixonsolutions.godnotic;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.FileObserver;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * The chat log's export icon (engine/darkplaces/touch_chatlog.c, command
 * touch_chatlog_export) writes the log as text to touch_chatlog.txt in the
 * engine's data directory. This notices the finished write, saves a copy in
 * Downloads (MediaStore on Android 10+; older versions have no scoped storage
 * and the file stays in the data directory) and opens the system share sheet
 * with the text. Colour codes are already stripped and timestamps added by the
 * engine.
 *
 * Start it once from the activity: ChatLogShare.start(this, GameData.userDir(base)).
 */
final class ChatLogShare {
    // touch_chatlog_export writes NAME (share and download); the separate share and
    // download icons write one of the others (touch_chatlog_copy never writes a file:
    // the engine puts the text on the clipboard itself).
    private static final String NAME = "touch_chatlog.txt";
    private static final String NAME_SHARE = "touch_chatlog_share.txt";
    private static final String NAME_DOWNLOAD = "touch_chatlog_download.txt";
    private static FileObserver observer; // held: a FileObserver stops when collected

    private ChatLogShare() {}

    static void start(final Activity activity, File userDir) {
        final File dir = new File(userDir, "data");
        final File file = new File(dir, NAME);
        final Handler main = new Handler(Looper.getMainLooper());
        observer = new FileObserver(dir.getAbsolutePath(), FileObserver.CLOSE_WRITE | FileObserver.MOVED_TO) {
            @Override
            public void onEvent(int event, String path) {
                if (NAME.equals(path)) {
                    main.post(() -> export(activity, file, true, true));
                } else if (NAME_SHARE.equals(path)) {
                    main.post(() -> export(activity, new File(dir, NAME_SHARE), false, true));
                } else if (NAME_DOWNLOAD.equals(path)) {
                    main.post(() -> export(activity, new File(dir, NAME_DOWNLOAD), true, false));
                }
            }
        };
        observer.startWatching();
    }

    static String readText(File file) throws IOException {
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] bytes = new byte[(int) Math.min(file.length(), 1 << 20)];
            int n = 0;
            while (n < bytes.length) {
                int r = in.read(bytes, n, bytes.length - n);
                if (r < 0) break;
                n += r;
            }
            return new String(bytes, 0, n, StandardCharsets.UTF_8);
        }
    }

    static void export(Activity activity, File file, boolean download, boolean share) {
        final String text;
        try {
            text = readText(file);
        } catch (IOException e) {
            Log.w("Godnotic", "Chat log not read", e);
            return;
        }
        if (text.isEmpty()) {
            return;
        }
        if (download) {
            saveToDownloads(activity, text);
        }
        if (!share) {
            return;
        }
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT, "Godnotic chat log");
        send.putExtra(Intent.EXTRA_TEXT, text);
        activity.startActivity(Intent.createChooser(send, "Share chat log"));
    }

    private static void saveToDownloads(Activity activity, String text) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return;
        }
        String name = "godnotic-chat-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".txt";
        ContentResolver resolver = activity.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, name);
        values.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
        Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) {
            return;
        }
        try (OutputStream out = resolver.openOutputStream(uri)) {
            if (out != null) {
                out.write(text.getBytes(StandardCharsets.UTF_8));
            }
            Toast.makeText(activity, "Saved " + name + " to Downloads", Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            Log.w("Godnotic", "Chat log not saved to Downloads", e);
        }
    }
}
