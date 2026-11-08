package com.matttracker.workhours;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;

/** Single place that builds/posts/cancels the shift notification. */
public class TimerNotifier {
    public static final String CHANNEL_ID = "work_ts_timer_v3";
    public static final int NOTIF_ID = 1001;
    private static final String PREFS = "wht_timer";
    private static final String KEY_ACTIVE = "timer_active";
    private static final String KEY_START = "timer_start_ms";

    public static void markActive(Context ctx, long startMs) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ACTIVE, true).putLong(KEY_START, startMs).apply();
    }

    public static void markInactive(Context ctx) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ACTIVE, false).apply();
    }

    public static boolean isActive(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ACTIVE, false);
    }

    public static long startMs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_START, System.currentTimeMillis());
    }

    public static void post(Context ctx, long startMs) {
        try {
            NotificationManager nm =
                (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "Shift timer", NotificationManager.IMPORTANCE_DEFAULT);
                ch.setDescription("Shows while you are clocked in");
                nm.createNotificationChannel(ch);
            }
            // Tap body -> open the app
            Intent openApp = new Intent(ctx, MainActivity.class);
            openApp.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent openPi = PendingIntent.getActivity(
                ctx, 0, openApp,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            // Stop button -> receiver clocks out
            Intent stopIntent = new Intent(ctx, TimerActionReceiver.class);
            stopIntent.setAction(TimerActionReceiver.ACTION_STOP);
            PendingIntent stopPi = PendingIntent.getBroadcast(
                ctx, 1, stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            Notification.Builder b;
            if (Build.VERSION.SDK_INT >= 26) {
                b = new Notification.Builder(ctx, CHANNEL_ID);
            } else {
                b = new Notification.Builder(ctx);
            }
            b.setContentTitle("On shift \u2014 Job Tracker")
             .setContentText("Tap to open \u2022 Stop to clock out")
             .setSmallIcon(R.drawable.ic_stat_pocketwatch)
             .setContentIntent(openPi)
             .setOngoing(true)
             .setUsesChronometer(true)
             .setChronometerCountDown(false)
             .setWhen(startMs);
            if (Build.VERSION.SDK_INT >= 23) {
                b.addAction(new Notification.Action.Builder(
                    android.R.drawable.ic_dialog_info, "Stop timer", stopPi).build());
            }
            nm.notify(NOTIF_ID, b.build());
        } catch (Exception e) { /* ignore */ }
    }

    public static void cancel(Context ctx) {
        try {
            NotificationManager nm =
                (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(NOTIF_ID);
        } catch (Exception e) { /* ignore */ }
    }
}
