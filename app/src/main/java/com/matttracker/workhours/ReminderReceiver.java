package com.matttracker.workhours;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Fires a scheduled shift reminder; also re-arms reminders after a reboot. */
public class ReminderReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            ReminderNotifier.rescheduleStored(context);
            return;
        }
        if (ReminderNotifier.ACTION_FIRE.equals(intent.getAction())) {
            String id = intent.getStringExtra(ReminderNotifier.EXTRA_ID);
            String title = intent.getStringExtra(ReminderNotifier.EXTRA_TITLE);
            String body = intent.getStringExtra(ReminderNotifier.EXTRA_BODY);
            String target = intent.getStringExtra(ReminderNotifier.EXTRA_TARGET);
            if (id != null) ReminderNotifier.forget(context, id);
            ReminderNotifier.post(context, id == null ? "rem" : id, title, body, target);
        }
    }
}
