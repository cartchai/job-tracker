package com.matttracker.workhours;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Fired by the notification's Stop button: clock out inside the WebView. */
public class TimerActionReceiver extends BroadcastReceiver {
    public static final String ACTION_STOP = "com.matttracker.workhours.STOP_TIMER";
    public static final String ACTION_REPOST = "com.matttracker.workhours.REPOST_TIMER";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (ACTION_REPOST.equals(intent.getAction())) {
            // Notification was dismissed: bring it straight back if still clocked in.
            if (TimerNotifier.isActive(context)) {
                TimerNotifier.post(context, TimerNotifier.startMs(context));
            }
            return;
        }
        if (ACTION_STOP.equals(intent.getAction())) {
            // Dismiss the notification promptly.
            try {
                android.app.NotificationManager nm =
                    (android.app.NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null) nm.cancel(1001);
            } catch (Exception e) { /* ignore */ }
            // Ask the running activity (if any) to clock out via JS.
            MainActivity.requestClockOutFromNotification();
            // If the app isn't running, bring it up so the clock-out lands.
            Intent open = new Intent(context, MainActivity.class);
            open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            open.putExtra("notif_clock_out", true);
            context.startActivity(open);
        }
    }
}
