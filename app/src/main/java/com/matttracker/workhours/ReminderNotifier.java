package com.matttracker.workhours;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * One-off shift reminders (beta). The web app works out what should fire;
 * this class arms Android alarms for it and posts the notifications.
 * Specs live in app-private prefs so a reboot can put future ones back.
 */
public class ReminderNotifier {
    public static final String CHANNEL_ID = "shift_reminders";
    public static final String ACTION_FIRE = "com.matttracker.workhours.REMINDER_FIRE";
    public static final String EXTRA_ID = "rem_id";
    public static final String EXTRA_TITLE = "rem_title";
    public static final String EXTRA_BODY = "rem_body";
    public static final String EXTRA_TARGET = "rem_target";
    private static final String PREFS = "wht_reminders";
    private static final String KEY_SPECS = "specs";

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static JSONArray readSpecs(Context ctx) {
        try {
            String raw = prefs(ctx).getString(KEY_SPECS, "[]");
            return new JSONArray(raw == null ? "[]" : raw);
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private static void writeSpecs(Context ctx, JSONArray arr) {
        prefs(ctx).edit().putString(KEY_SPECS, arr.toString()).apply();
    }

    private static int requestCode(String id) {
        return 31000 + Math.abs(id.hashCode() % 100000);
    }

    private static int notifId(String id) {
        return 5000 + Math.abs(id.hashCode() % 100000);
    }

    private static PendingIntent alarmIntent(Context ctx, JSONObject spec) throws Exception {
        Intent i = new Intent(ctx, ReminderReceiver.class);
        i.setAction(ACTION_FIRE);
        i.putExtra(EXTRA_ID, spec.getString("id"));
        i.putExtra(EXTRA_TITLE, spec.optString("title", "Job Tracker"));
        i.putExtra(EXTRA_BODY, spec.optString("body", ""));
        i.putExtra(EXTRA_TARGET, spec.optString("target", "reminders"));
        return PendingIntent.getBroadcast(ctx, requestCode(spec.getString("id")), i,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static void arm(Context ctx, JSONObject spec) throws Exception {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        if (am == null) return;
        PendingIntent pi = alarmIntent(ctx, spec);
        long at = spec.getLong("at");
        if (Build.VERSION.SDK_INT >= 31 && am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        } else if (Build.VERSION.SDK_INT >= 23) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
        } else {
            am.set(AlarmManager.RTC_WAKEUP, at, pi);
        }
    }

    /** Arm (or replace) one reminder. Returns "scheduled", "past", or "error:...". */
    public static String schedule(Context ctx, String id, long atMs, String title, String body, String target) {
        try {
            if (id == null || id.length() == 0) return "error:no id";
            if (atMs <= System.currentTimeMillis()) return "past";
            JSONObject spec = new JSONObject();
            spec.put("id", id);
            spec.put("at", atMs);
            spec.put("title", title == null ? "" : title);
            spec.put("body", body == null ? "" : body);
            spec.put("target", target == null ? "reminders" : target);
            JSONArray arr = readSpecs(ctx);
            JSONArray kept = new JSONArray();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null && !id.equals(o.optString("id"))) kept.put(o);
            }
            kept.put(spec);
            writeSpecs(ctx, kept);
            arm(ctx, spec);
            return "scheduled";
        } catch (Exception e) {
            return "error:" + (e.getMessage() != null ? e.getMessage() : "unknown");
        }
    }

    public static void cancel(Context ctx, String id) {
        try {
            JSONArray arr = readSpecs(ctx);
            JSONArray kept = new JSONArray();
            JSONObject found = null;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                if (id.equals(o.optString("id"))) found = o; else kept.put(o);
            }
            writeSpecs(ctx, kept);
            if (found != null) {
                AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
                if (am != null) am.cancel(alarmIntent(ctx, found));
            }
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.cancel(notifId(id));
        } catch (Exception e) { /* ignore */ }
    }

    public static void clearAll(Context ctx) {
        try {
            JSONArray arr = readSpecs(ctx);
            List<String> ids = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) ids.add(o.optString("id"));
            }
            for (String id : ids) cancel(ctx, id);
            writeSpecs(ctx, new JSONArray());
        } catch (Exception e) { /* ignore */ }
    }

    /** After a reboot: re-arm every stored reminder still in the future. */
    public static void rescheduleStored(Context ctx) {
        try {
            JSONArray arr = readSpecs(ctx);
            JSONArray kept = new JSONArray();
            long now = System.currentTimeMillis();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                if (o.optLong("at", 0) > now) {
                    arm(ctx, o);
                    kept.put(o);
                }
            }
            writeSpecs(ctx, kept);
        } catch (Exception e) { /* ignore */ }
    }

    /** A reminder fired: drop its spec so status stays truthful. */
    public static void forget(Context ctx, String id) {
        try {
            JSONArray arr = readSpecs(ctx);
            JSONArray kept = new JSONArray();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null && !id.equals(o.optString("id"))) kept.put(o);
            }
            writeSpecs(ctx, kept);
        } catch (Exception e) { /* ignore */ }
    }

    public static String statusJson(Context ctx) {
        JSONObject out = new JSONObject();
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            out.put("notifications", nm != null && nm.areNotificationsEnabled());
            boolean exact = true;
            if (Build.VERSION.SDK_INT >= 31) {
                AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
                exact = am != null && am.canScheduleExactAlarms();
            }
            out.put("exact", exact);
            JSONArray arr = readSpecs(ctx);
            JSONArray list = new JSONArray();
            long now = System.currentTimeMillis();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                JSONObject s = new JSONObject();
                s.put("id", o.optString("id"));
                s.put("at", o.optLong("at", 0));
                s.put("title", o.optString("title", ""));
                s.put("future", o.optLong("at", 0) > now);
                list.put(s);
            }
            out.put("scheduled", list);
        } catch (Exception e) { /* return what we have */ }
        return out.toString();
    }

    public static void post(Context ctx, String id, String title, String body, String target) {
        try {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel ch = new NotificationChannel(
                        CHANNEL_ID, "Shift reminders", NotificationManager.IMPORTANCE_DEFAULT);
                ch.setDescription("Shift start, schedule and clock-out reminders");
                nm.createNotificationChannel(ch);
            }
            Intent open = new Intent(ctx, MainActivity.class);
            open.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            if ("timer".equals(target)) {
                open.putExtra("open_timer", true);
            } else {
                open.putExtra("open_reminders", true);
            }
            PendingIntent pi = PendingIntent.getActivity(ctx, requestCode(id), open,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification.Builder b;
            if (Build.VERSION.SDK_INT >= 26) {
                b = new Notification.Builder(ctx, CHANNEL_ID);
            } else {
                b = new Notification.Builder(ctx);
            }
            String t = (title == null || title.length() == 0) ? "Job Tracker" : title;
            String text = body == null ? "" : body;
            b.setContentTitle(t)
             .setContentText(text)
             .setStyle(new Notification.BigTextStyle().bigText(text))
             .setSmallIcon(R.drawable.ic_stat_pocketwatch)
             .setContentIntent(pi)
             .setAutoCancel(true);
            nm.notify(notifId(id), b.build());
        } catch (Exception e) { /* ignore */ }
    }
}
