package io.github.dixonsolutions.godnotic;

import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The line over the main menu after a crash: "Godnotic crashed — Share report".
 *
 * <p>A plain view added over whatever the activity shows (the engine's surface or the native
 * interface), because the payload cannot add a screen to the AndySU host's manifest: a new
 * Activity class would not be launchable there. Tapping it opens the share sheet with the newest
 * report; the × puts it away until another report appears. It shows only in the main menu, for a report of
 * this version, and leaves after eight seconds.
 */
final class CrashBanner {
    private static final String TAG = "Godnotic";

    private CrashBanner() {}

    private static volatile View shown;

    /**
     * Add the banner when the rule allows it ({@link CrashReport#shouldShowBanner}); it puts itself
     * away after {@link CrashReport#BANNER_MS}. Call on the UI thread, only while the game is in the
     * menu.
     */
    static void showIfNeeded(final Activity activity) {
        if (shown != null) {
            return;
        }
        boolean due;
        try {
            due = CrashStore.bannerDue(activity, true);
        } catch (RuntimeException | LinkageError e) {
            Log.w(TAG, "Could not look for crash reports", e);
            return;
        }
        if (!due) {
            return;
        }
        try {
            final LinearLayout bar = build(activity);
            activity.addContentView(bar, layout(activity));
            shown = bar;
            bar.postDelayed(() -> hide(), CrashReport.BANNER_MS);
        } catch (RuntimeException | LinkageError e) {
            Log.w(TAG, "Could not show the crash banner", e);
        }
    }

    /** Take the banner away (a match started, or its time is up). UI thread. */
    static void hide() {
        View view = shown;
        shown = null;
        if (view != null) {
            dismiss(view);
        }
    }

    static boolean isShown() {
        return shown != null;
    }

    private static LinearLayout build(final Activity activity) {
        float dp = activity.getResources().getDisplayMetrics().density;
        LinearLayout bar = new LinearLayout(activity);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        int pad = Math.round(14 * dp);
        bar.setPadding(pad, Math.round(8 * dp), Math.round(8 * dp), Math.round(8 * dp));
        GradientDrawable pill = new GradientDrawable();
        pill.setCornerRadius(22 * dp);
        pill.setColor(0xF02B1B1B);
        pill.setStroke(Math.round(1 * dp), 0xFFE0603C);
        bar.setBackground(pill);
        bar.setElevation(Math.round(6 * dp));

        TextView message = new TextView(activity);
        String headline;
        try {
            headline = CrashStore.bannerText(activity);
        } catch (RuntimeException e) {
            headline = HostRes.string(activity, "crash_banner");
        }
        message.setText(HostRes.string(activity, "crash_banner_with", headline));
        message.setTextColor(0xFFF2F4F8);
        message.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        message.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        message.setPadding(0, 0, Math.round(12 * dp), 0);
        bar.addView(message, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView share = new TextView(activity);
        share.setText(HostRes.string(activity, "crash_share"));
        share.setTextColor(0xFFFFFFFF);
        share.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        share.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        share.setPadding(Math.round(14 * dp), Math.round(6 * dp), Math.round(14 * dp), Math.round(6 * dp));
        GradientDrawable button = new GradientDrawable();
        button.setCornerRadius(18 * dp);
        button.setColor(0xFF3584E4);
        share.setBackground(button);
        share.setOnClickListener(v -> {
            try {
                CrashStore.shareNewest(activity);
            } catch (RuntimeException e) {
                Log.w(TAG, "Could not share the crash report", e);
            }
            dismiss(v);
        });
        bar.addView(share, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView close = new TextView(activity);
        close.setText("×");
        close.setTextColor(0xFFB4BAC6);
        close.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        close.setPadding(Math.round(12 * dp), Math.round(2 * dp), Math.round(10 * dp), Math.round(6 * dp));
        close.setOnClickListener(v -> {
            // Away for this report only: the next one shows it again.
            CrashStore.markSeen(activity);
            dismiss(v);
        });
        bar.addView(close, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return bar;
    }

    private static void dismiss(View view) {
        if (view == shown) {
            shown = null;
        }
        if (view.getParent() instanceof android.view.ViewGroup) {
            ((android.view.ViewGroup) view.getParent()).removeView(view);
        }
    }

    /** A small pill at the top of the screen, inside the cutout and the status bar. */
    private static FrameLayout.LayoutParams layout(Activity activity) {
        float dp = activity.getResources().getDisplayMetrics().density;
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        params.topMargin = Math.round(12 * dp);
        params.setMarginStart(Math.round(12 * dp));
        params.setMarginEnd(Math.round(12 * dp));
        return params;
    }
}
