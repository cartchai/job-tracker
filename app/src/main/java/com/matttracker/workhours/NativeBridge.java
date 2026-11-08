package com.matttracker.workhours;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Bridge between the web app and durable Android storage.
 *
 * Key/value data lives in app-private SharedPreferences
 * ( /data/data/com.matttracker.workhours/shared_prefs/ ).
 * "Clear cache" only wipes the cache dir, so shifts, locations,
 * the employee name and the theme survive it. Only a full
 * "Clear storage" or uninstall removes them — and the in-app
 * backup export/import covers that case.
 */
public class NativeBridge {
    static final int PICK_BACKUP = 1001;
    static final int PICK_SCAN_IMAGE = 1002;
    private Uri pendingCameraOutput;

    private final Activity activity;
    private final WebView webView;
    private final SharedPreferences prefs;

    NativeBridge(Activity activity, WebView webView) {
        this.activity = activity;
        this.webView = webView;
        this.prefs = activity.getSharedPreferences("wht_store", Activity.MODE_PRIVATE);
    }

    @JavascriptInterface
    public String get(String key) {
        return prefs.getString(key, null);
    }

    @JavascriptInterface
    public void set(String key, String value) {
        prefs.edit().putString(key, value).apply();
    }

    @JavascriptInterface
    public void remove(String key) {
        prefs.edit().remove(key).apply();
    }

    /** Shift timer started: show the persistent notification. startMs = JS clock-in epoch ms. */
    @JavascriptInterface
    public void timerStarted(long startMs) {
        // The JS->Java bridge sometimes delivers 0 here; fall back to now.
        if (startMs < 1000000000000L) {
            startMs = System.currentTimeMillis();
        }
        TimerNotifier.markActive(activity, startMs);
        TimerNotifier.post(activity, startMs);
    }

    /** Shift timer stopped: remove the persistent notification. */
    @JavascriptInterface
    public void timerStopped() {
        TimerNotifier.markInactive(activity);
        TimerNotifier.cancel(activity);
    }

    /** Shift reminders (beta): arm one reminder alarm. Returns a status string. */
    @JavascriptInterface
    public String scheduleReminder(String id, long atMs, String title, String body, String target) {
        return ReminderNotifier.schedule(activity, id, atMs, title, body, target);
    }

    @JavascriptInterface
    public void cancelReminder(String id) {
        ReminderNotifier.cancel(activity, id);
    }

    @JavascriptInterface
    public void clearReminders() {
        ReminderNotifier.clearAll(activity);
    }

    /** Theming (8.0.8): setIconTheme only RECORDS the picked colour. The
     *  actual swap runs as one atomic pass in MainActivity.onStop
     *  (runPendingIconCleanup): the target is enabled and every other alias
     *  disabled in a single step while the app is backgrounded. The earlier
     *  foreground flow had to leave the session's launch alias enabled until
     *  exit, so One UI observed two live launcher icons and kept entries
     *  for both. */
    @JavascriptInterface
    public void setIconTheme(String key) {
        String target = iconAliasFor(key);
        prefs.edit().putString("icon_theme_target", target)
                .putString("icon_cleanup_pending", "1").apply();
        snapshotIconDebug(target);
    }

    /** Read-only state snapshot so the Theming card's check line stays
     *  truthful between picks (no component is touched). */
    void snapshotIconDebug(String target) {
        try {
            String[] aliases = {"IconBlue", "IconPurple", "IconPink", "IconRed",
                    "IconOrange", "IconGold", "IconGreen", "IconTeal"};
            String[] shorts = {"Blu", "Pur", "Pin", "Red", "Ora", "Gol", "Grn", "Tea"};
            android.content.pm.PackageManager pm = activity.getPackageManager();
            String pkg = activity.getPackageName();
            StringBuilder dbg = new StringBuilder();
            dbg.append("T:").append(target.replace("Icon", "")).append(" L:-");
            for (int i = 0; i < aliases.length; i++) {
                int st;
                try {
                    st = pm.getComponentEnabledSetting(new android.content.ComponentName(pkg, pkg + "." + aliases[i]));
                } catch (Exception e) { st = -1; }
                dbg.append(" ").append(shorts[i]).append(":").append(st);
            }
            dbg.append(" ERR:-");
            prefs.edit().putString("icon_debug", dbg.toString()).apply();
        } catch (Exception ignored) {}
    }

    /** Called from MainActivity.onCreate: whatever state the launcher is
     *  in, an app start leaves at most the target icon plus the entry the
     *  user just came in on (the exit cleanup removes that one too). */
    static void reconcileIconTheme(android.app.Activity act) {
        try {
            android.content.SharedPreferences p =
                    act.getSharedPreferences("wht_store", android.app.Activity.MODE_PRIVATE);
            String target = p.getString("icon_theme_target", "IconBlue");
            android.content.ComponentName launch = null;
            try {
                if (act.getIntent() != null) launch = act.getIntent().getComponent();
            } catch (Exception ignored) {}
            applyIconTheme(act, p, target, launch, false);
        } catch (Exception ignored) {}
    }

    static String iconAliasFor(String key) {
        String[] aliases = {"IconBlue", "IconPurple", "IconPink", "IconRed",
                "IconOrange", "IconGold", "IconGreen", "IconTeal"};
        if (key != null) {
            for (String a : aliases) {
                if (a.equalsIgnoreCase("Icon" + key.trim())) return a;
            }
        }
        return "IconBlue";
    }

    /** Finish a deferred icon swap once the app is in the background. */
    void runPendingIconCleanup() {
        if (prefs.getString("icon_cleanup_pending", null) == null) return;
        applyIconTheme(prefs.getString("icon_theme_target", "IconBlue"), true);
    }

    void applyIconTheme(String target, boolean cleanup) {
        android.content.ComponentName launch = null;
        if (!cleanup) {
            try {
                if (activity.getIntent() != null) launch = activity.getIntent().getComponent();
            } catch (Exception ignored) {}
        }
        applyIconTheme(activity, prefs, target, launch, cleanup);
    }

    static void applyIconTheme(android.app.Activity act, android.content.SharedPreferences prefs,
                               String target, android.content.ComponentName launch, boolean cleanup) {
        String[] aliases = {"IconBlue", "IconPurple", "IconPink", "IconRed",
                "IconOrange", "IconGold", "IconGreen", "IconTeal"};
        String[] shorts = {"Blu", "Pur", "Pin", "Red", "Ora", "Gol", "Grn", "Tea"};
        android.content.pm.PackageManager pm = act.getPackageManager();
        String pkg = act.getPackageName();
        StringBuilder dbg = new StringBuilder();
        String err = "-";
        dbg.append("T:").append(target.replace("Icon", ""));
        dbg.append(" L:").append(launch == null ? "-" : launch.getClassName().replace(pkg + ".Icon", ""));
        boolean deferred = false;
        // Enable the target (isolated: one failure must not block the rest)
        try {
            pm.setComponentEnabledSetting(
                    new android.content.ComponentName(pkg, pkg + "." + target),
                    android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    android.content.pm.PackageManager.DONT_KILL_APP);
        } catch (Exception e) { err = "EN:" + e.getClass().getSimpleName(); }
        for (String a : aliases) {
            if (a.equals(target)) continue;
            if (!cleanup && launch != null && (pkg + "." + a).equals(launch.getClassName())) {
                deferred = true;
                continue;
            }
            try {
                pm.setComponentEnabledSetting(
                        new android.content.ComponentName(pkg, pkg + "." + a),
                        android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        android.content.pm.PackageManager.DONT_KILL_APP);
            } catch (Exception e) { err = "DIS:" + e.getClass().getSimpleName(); }
        }
        // Read the states back so the Theming card can show the truth
        for (int i = 0; i < aliases.length; i++) {
            int st;
            try {
                st = pm.getComponentEnabledSetting(new android.content.ComponentName(pkg, pkg + "." + aliases[i]));
            } catch (Exception e) { st = -1; }
            dbg.append(" ").append(shorts[i]).append(":").append(st);
        }
        dbg.append(" ERR:").append(err);
        try {
            prefs.edit().putString("icon_theme_applied", target).apply();
            prefs.edit().putString("icon_debug", dbg.toString()).apply();
            if (deferred) prefs.edit().putString("icon_cleanup_pending", "1").apply();
            else prefs.edit().remove("icon_cleanup_pending").apply();
        } catch (Exception ignored) {}
    }

    /** User-invoked repair: force exactly one icon, even if it restarts the app. */
    @JavascriptInterface
    public void forceIconTheme() {
        String target = prefs.getString("icon_theme_target", "IconBlue");
        applyIconTheme(activity, prefs, target, null, true);
    }


    /** System bar insets in CSS px (dp): {"top":<status bar>, "bottom":<nav bar>}.
     *  The WebView's env(safe-area-inset-*) reads 0 on this phone, so the page
     *  asks native for the real values and applies them as --sat/--sab.
     *  Top is the larger of the status bar and the display cutout; bottom is
     *  the larger of the nav bar and the cutout. Falls back to the framework
     *  status/nav bar height resources when the window insets are not ready. */
    @JavascriptInterface
    public String getSystemBarInsets() {
        try {
            android.view.View decor = activity.getWindow().getDecorView();
            android.util.DisplayMetrics dm = activity.getResources().getDisplayMetrics();
            float density = dm.density > 0 ? dm.density : 1f;
            int topPx = 0, bottomPx = 0;
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                try {
                    android.view.WindowInsets insets = decor.getRootWindowInsets();
                    if (insets != null) {
                        android.graphics.Insets sb = insets.getInsets(android.view.WindowInsets.Type.statusBars());
                        android.graphics.Insets nb = insets.getInsets(android.view.WindowInsets.Type.navigationBars());
                        android.graphics.Insets cut = insets.getInsets(android.view.WindowInsets.Type.displayCutout());
                        topPx = Math.max(sb.top, cut.top);
                        bottomPx = Math.max(nb.bottom, cut.bottom);
                    }
                } catch (Exception ignored) {}
            }
            if (topPx <= 0) {
                int id = activity.getResources().getIdentifier("status_bar_height", "dimen", "android");
                if (id > 0) topPx = activity.getResources().getDimensionPixelSize(id);
            }
            if (bottomPx <= 0) {
                int id = activity.getResources().getIdentifier("navigation_bar_height", "dimen", "android");
                if (id > 0) bottomPx = activity.getResources().getDimensionPixelSize(id);
            }
            int top = Math.round(topPx / density);
            int bottom = Math.round(bottomPx / density);
            return "{\"top\":" + top + ",\"bottom\":" + bottom + "}";
        } catch (Exception e) {
            return "{\"top\":0,\"bottom\":0}";
        }
    }

    /** The web theme changed (dark/light): match the status + nav bar icon colour.
     *  Dark theme -> light (white) system icons; light theme -> dark icons.
     *  Called from the web app's toggleTheme() and on load. */
    @JavascriptInterface
    public void setSystemBarDark(final boolean isDark) {
        activity.runOnUiThread(() -> {
            try {
                if (activity instanceof MainActivity) ((MainActivity) activity).setSystemBarIconsDark(isDark);
            } catch (Exception ignored) {}
        });
    }

    /** JSON: notification permission, exact-alarm permission, armed reminders. */
    @JavascriptInterface
    public String reminderStatus() {
        return ReminderNotifier.statusJson(activity);
    }

    /** Open Android's per-app "Alarms & reminders" settings (Android 12+). */
    @JavascriptInterface
    public void openExactAlarmSettings() {
        activity.runOnUiThread(() -> {
            try {
                if (android.os.Build.VERSION.SDK_INT >= 31) {
                    Intent i = new Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM);
                    i.setData(android.net.Uri.parse("package:" + activity.getPackageName()));
                    activity.startActivity(i);
                }
            } catch (Exception e) { /* ignore */ }
        });
    }

    @JavascriptInterface
    public String exportBackup(String name, String json) {
        try {
            ContentResolver cr = activity.getContentResolver();
            ContentValues v = new ContentValues();
            v.put(MediaStore.Downloads.DISPLAY_NAME, name);
            v.put(MediaStore.Downloads.MIME_TYPE, "application/json");
            v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
            Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) {
                return "could not create file";
            }
            try (OutputStream out = cr.openOutputStream(uri)) {
                if (out == null) {
                    return "could not open file";
                }
                out.write(json.getBytes(StandardCharsets.UTF_8));
            }
            return "ok";
        } catch (Exception e) {
            return e.getMessage() != null ? e.getMessage() : "unknown error";
        }
    }

    /** Save a generated .docx (base64) into the public Downloads folder. Returns "ok" or an error. */
    @JavascriptInterface
    public String saveDocx(String name, String base64) {
        try {
            byte[] bytes = android.util.Base64.decode(base64, android.util.Base64.DEFAULT);
            ContentResolver cr = activity.getContentResolver();
            ContentValues v = new ContentValues();
            v.put(MediaStore.Downloads.DISPLAY_NAME, name);
            v.put(MediaStore.Downloads.MIME_TYPE, "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
            v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
            Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) {
                return "could not create file";
            }
            try (OutputStream out = cr.openOutputStream(uri)) {
                if (out == null) {
                    return "could not open file";
                }
                out.write(bytes);
            }
            return "ok";
        } catch (Exception e) {
            return e.getMessage() != null ? e.getMessage() : "unknown error";
        }
    }

    /** Open the Android system print dialog for the current page (lets the user save as PDF). */
    @JavascriptInterface
    public void printPage() {
        activity.runOnUiThread(() -> {
            android.print.PrintManager pm =
                    (android.print.PrintManager) activity.getSystemService(android.content.Context.PRINT_SERVICE);
            if (pm == null) {
                return;
            }
            String jobName = "Job Tracker Timesheet";
            pm.print(jobName, webView.createPrintDocumentAdapter(jobName),
                    new android.print.PrintAttributes.Builder().build());
        });
    }

    /** Share a JS-generated timesheet PDF, or save it as JPEG(s).
     *  Why the PDF comes from JS, not from the system print dialog:
     *  the system "Save as PDF" file belongs to the print service, not
     *  to this app, so it cannot be shared or converted from here. And
     *  driving the WebView print adapter by hand is not possible in this
     *  SDK: PrintDocumentAdapter's result callbacks cannot be subclassed
     *  outside android.print (their constructors are package-private),
     *  so that route does not even compile. Instead JS builds the PDF
     *  (same rows, order and night rule as the print export, totals
     *  blank for sign-off, Letter, 0.5in margins) and hands it here as
     *  base64 — the same pattern as the Word export's saveDocx.
     *  sharePdf saves it to Downloads and opens Android's share menu
     *  with it attached; savePdfAsJpeg renders its pages with PdfRenderer
     *  and saves one JPEG per page in Pictures/JobTracker. The result
     *  reaches JS through window.__onTimesheetExport(result): "shared",
     *  "saved: ...", or an error message. No FileProvider is needed:
     *  the MediaStore Downloads Uri is what the share sheet receives. */
    @JavascriptInterface
    public void sharePdf(final String baseName, final String base64Pdf) {
        new Thread(() -> {
            try {
                byte[] bytes = android.util.Base64.decode(base64Pdf, android.util.Base64.DEFAULT);
                java.io.File pdfFile = new java.io.File(activity.getCacheDir(), "timesheet.pdf");
                try (java.io.FileOutputStream out = new java.io.FileOutputStream(pdfFile)) {
                    out.write(bytes);
                }
                Uri uri = savePdfToDownloads(pdfFile, baseName);
                if (uri == null) {
                    timesheetExportDone("PDF could not be saved to Downloads");
                    return;
                }
                final Uri shareUri = uri;
                activity.runOnUiThread(() -> {
                    try {
                        Intent send = new Intent(Intent.ACTION_SEND);
                        send.setType("application/pdf");
                        send.putExtra(Intent.EXTRA_STREAM, shareUri);
                        send.putExtra(Intent.EXTRA_SUBJECT, baseName);
                        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        activity.startActivity(Intent.createChooser(send, "Share timesheet PDF"));
                        timesheetExportDone("shared");
                    } catch (Exception e) {
                        timesheetExportDone(e.getMessage() != null ? e.getMessage() : "Share sheet could not be opened");
                    }
                });
            } catch (Exception e) {
                timesheetExportDone(e.getMessage() != null ? e.getMessage() : "PDF share failed");
            }
        }).start();
    }

    @JavascriptInterface
    public void savePdfAsJpeg(final String baseName, final String base64Pdf) {
        new Thread(() -> {
            try {
                byte[] bytes = android.util.Base64.decode(base64Pdf, android.util.Base64.DEFAULT);
                java.io.File pdfFile = new java.io.File(activity.getCacheDir(), "timesheet.pdf");
                try (java.io.FileOutputStream out = new java.io.FileOutputStream(pdfFile)) {
                    out.write(bytes);
                }
                timesheetExportDone(saveTimesheetJpegs(pdfFile, baseName));
            } catch (Exception e) {
                timesheetExportDone(e.getMessage() != null ? e.getMessage() : "JPEG export failed");
            }
        }).start();
    }

    private Uri savePdfToDownloads(java.io.File pdfFile, String baseName) {
        try {
            ContentResolver cr = activity.getContentResolver();
            ContentValues v = new ContentValues();
            v.put(MediaStore.Downloads.DISPLAY_NAME, baseName + ".pdf");
            v.put(MediaStore.Downloads.MIME_TYPE, "application/pdf");
            v.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
            Uri uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
            if (uri == null) return null;
            try (java.io.InputStream in = new java.io.FileInputStream(pdfFile);
                 OutputStream out = cr.openOutputStream(uri)) {
                if (out == null) return null;
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            }
            return uri;
        } catch (Exception e) {
            return null;
        }
    }

    private String saveTimesheetJpegs(java.io.File pdfFile, String baseName) {
        PdfRenderer renderer = null;
        try {
            ParcelFileDescriptor pfd = ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY);
            renderer = new PdfRenderer(pfd);
            int count = renderer.getPageCount();
            if (count <= 0) return "PDF had no pages to save as JPEG";
            for (int i = 0; i < count; i++) {
                PdfRenderer.Page page = renderer.openPage(i);
                try {
                    Bitmap bmp = Bitmap.createBitmap(page.getWidth() * 2, page.getHeight() * 2, Bitmap.Config.ARGB_8888);
                    // 8.1.27 fix: a fresh ARGB bitmap is transparent, and
                    // JPEG has no alpha, so it came out black — black text
                    // on a black page, with only the grey header row
                    // visible. Fill white first, then render on top.
                    bmp.eraseColor(android.graphics.Color.WHITE);
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                    String name = baseName + (count > 1 ? " p" + (i + 1) : "") + ".jpg";
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.Images.Media.DISPLAY_NAME, name);
                    v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                    v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/JobTracker");
                    Uri uri = activity.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
                    if (uri == null) return "JPEG could not be created";
                    try (OutputStream out = activity.getContentResolver().openOutputStream(uri)) {
                        if (out == null) return "JPEG could not be written";
                        bmp.compress(Bitmap.CompressFormat.JPEG, 90, out);
                    }
                    bmp.recycle();
                } finally {
                    page.close();
                }
            }
            return "saved: " + count + (count == 1 ? " JPEG" : " JPEGs") + " in Pictures/JobTracker";
        } catch (Exception e) {
            return e.getMessage() != null ? e.getMessage() : "JPEG export failed";
        } finally {
            try { if (renderer != null) renderer.close(); } catch (Exception ignored) {}
        }
    }

    private void timesheetExportDone(final String result) {
        final String safe = (result == null ? "" : result).replace("\\", "\\\\").replace("'", "\\'");
        activity.runOnUiThread(() -> webView.evaluateJavascript(
                "window.__onTimesheetExport && window.__onTimesheetExport('" + safe + "')", null));
    }

    /** Content of the last picked backup file, pulled by JS via takeBackupText(). */
    private volatile String pendingBackupText = null;

    /** JS pulls the picked backup's full text (can be several MB with saved scans). */
    @JavascriptInterface
    public String takeBackupText() {
        String t = pendingBackupText;
        pendingBackupText = null;
        return t;
    }

    /** Open the system file picker so the user can choose a backup JSON to restore. */
    @JavascriptInterface
    public void pickBackup() {
        activity.runOnUiThread(() -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("application/json");
            activity.startActivityForResult(i, PICK_BACKUP);
        });
    }

    /** Called from MainActivity.onActivityResult; delivers the file content back to JS. */
    void onBackupPicked(Intent data) {
        activity.runOnUiThread(() -> {
            if (data != null && data.getData() != null) {
                try {
                    Uri uri = data.getData();
                    StringBuilder sb = new StringBuilder();
                    try (InputStream in = activity.getContentResolver().openInputStream(uri)) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) != -1) {
                            sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                        }
                    }
                    pendingBackupText = sb.toString();
                    webView.evaluateJavascript(
                        "window.__onBackupPickedReady && window.__onBackupPickedReady()", null);
                    return;
                } catch (Exception e) { /* fall through to failure signal */ }
            }
            webView.evaluateJavascript(
                "window.__onBackupPicked && window.__onBackupPicked(null)", null);
        });
    }

    /** Open the camera or the system photo picker for the Scan tab. */
    @JavascriptInterface
    public void pickScanImage(String source) {
        activity.runOnUiThread(() -> {
            if ("camera".equals(source)) {
                ((MainActivity) activity).requestCameraForScan();
            } else {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("image/*");
                activity.startActivityForResult(i, PICK_SCAN_IMAGE);
            }
        });
    }

    /** Called once the CAMERA runtime permission is granted (or if already granted). */
    void launchScanCamera() {
        try {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Images.Media.DISPLAY_NAME, "scan_" + System.currentTimeMillis() + ".jpg");
            v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/JobTracker");
            Uri uri = activity.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
            if (uri == null) {
                deliverScanImage(null, null);
                return;
            }
            pendingCameraOutput = uri;
            Intent i = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
            i.putExtra(MediaStore.EXTRA_OUTPUT, uri);
            i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            activity.startActivityForResult(i, PICK_SCAN_IMAGE);
        } catch (Exception e) {
            deliverScanImage(null, null);
        }
    }

    /** Called from MainActivity.onActivityResult for the Scan tab image. */
    void onScanImagePicked(int resultCode, Intent data) {
        Uri uri = null;
        Uri deleteAfter = null;
        if (resultCode == Activity.RESULT_OK) {
            if (data != null && data.getData() != null) {
                uri = data.getData(); // gallery
            } else if (pendingCameraOutput != null) {
                uri = pendingCameraOutput; // camera (EXTRA_OUTPUT, data is null)
                deleteAfter = pendingCameraOutput;
            }
        } else if (pendingCameraOutput != null) {
            // user cancelled the camera: remove the empty MediaStore entry
            deleteAfter = pendingCameraOutput;
        }
        pendingCameraOutput = null;
        deliverScanImage(uri, deleteAfter);
    }

    /** Tell the web app the camera permission was denied. */
    void deliverScanDenied() {
        activity.runOnUiThread(() ->
            webView.evaluateJavascript("window.__onScanImageDenied && window.__onScanImageDenied()", null));
    }

    /** Downscale the image to <= 3200px, base64 it to JS, clean up any camera temp file. */
    private void deliverScanImage(Uri uri, Uri deleteAfter) {
        new Thread(() -> {
            String b64 = null;
            try {
                if (uri != null) {
                    Bitmap bmp = decodeScanSampled(uri);
                    if (bmp != null) {
                        ByteArrayOutputStream bos = new ByteArrayOutputStream();
                        bmp.compress(Bitmap.CompressFormat.JPEG, 93, bos);
                        bmp.recycle();
                        b64 = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
                    }
                }
            } catch (Exception e) {
                b64 = null;
            }
            if (deleteAfter != null) {
                try { activity.getContentResolver().delete(deleteAfter, null, null); } catch (Exception e) { /* ignore */ }
            }
            final String f = b64;
            activity.runOnUiThread(() -> {
                String js = "window.__onScanImagePicked && window.__onScanImagePicked(" +
                        (f == null ? "null" : "'" + f + "'") + ")";
                webView.evaluateJavascript(js, null);
            });
        }).start();
    }

    private Bitmap decodeScanSampled(Uri uri) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = activity.getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(in, null, bounds);
        }
        int longSide = Math.max(bounds.outWidth, bounds.outHeight);
        // decode at up to 2x target, then smooth-scale down for max text sharpness
        int sample = 1;
        while (longSide / (sample * 2) >= 6400) sample *= 2;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        Bitmap bmp;
        try (InputStream in = activity.getContentResolver().openInputStream(uri)) {
            bmp = BitmapFactory.decodeStream(in, null, o);
        }
        if (bmp == null) return null;
        int bw = bmp.getWidth(), bh = bmp.getHeight();
        if (Math.max(bw, bh) > 3200) {
            float s = 3200f / Math.max(bw, bh);
            Bitmap scaled = Bitmap.createScaledBitmap(bmp, Math.round(bw * s), Math.round(bh * s), true);
            if (scaled != bmp) bmp.recycle();
            return scaled;
        }
        return bmp;
    }

    /** --- APP UPDATES (8.5) --- */

    /** Installed version (from the package) for the Settings "Current version" line. */
    @JavascriptInterface
    public String getAppVersion() {
        try {
            return activity.getPackageManager()
                    .getPackageInfo(activity.getPackageName(), 0).versionName;
        } catch (Exception e) {
            return "unknown";
        }
    }

    /** Ask GitHub for the latest release of cartchai/job-tracker.
     *  No auth (public repo). Result reaches JS as
     *  window.__onUpdateChecked(json): {"tag":"v8.5","url":"<apk asset url>"}
     *  or {"error":"..."} — the JS side decides whether it is newer. */
    @JavascriptInterface
    public void checkForUpdate() {
        new Thread(() -> {
            try {
                java.net.URL url = new java.net.URL(
                        "https://api.github.com/repos/cartchai/job-tracker/releases/latest");
                java.net.HttpURLConnection c = (java.net.HttpURLConnection) url.openConnection();
                c.setConnectTimeout(10000);
                c.setReadTimeout(15000);
                c.setRequestProperty("User-Agent", "JobTracker-Updater");
                c.setRequestProperty("Accept", "application/vnd.github+json");
                int code = c.getResponseCode();
                if (code != 200) {
                    updateChecked("{\"error\":\"GitHub answered " + code + "\"}");
                    return;
                }
                StringBuilder sb = new StringBuilder();
                try (InputStream in = c.getInputStream()) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) != -1) sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                }
                org.json.JSONObject rel = new org.json.JSONObject(sb.toString());
                String tag = rel.optString("tag_name", "");
                String apkUrl = "";
                org.json.JSONArray assets = rel.optJSONArray("assets");
                if (assets != null) {
                    for (int i = 0; i < assets.length(); i++) {
                        org.json.JSONObject a = assets.optJSONObject(i);
                        if (a == null) continue;
                        String name = a.optString("name", "");
                        if (name.toLowerCase().endsWith(".apk")) {
                            apkUrl = a.optString("browser_download_url", "");
                            break;
                        }
                    }
                }
                if (tag.isEmpty() || apkUrl.isEmpty()) {
                    updateChecked("{\"error\":\"no installable release found\"}");
                    return;
                }
                updateChecked("{\"tag\":\"" + tag.replace("\"", "") + "\",\"url\":\"" + apkUrl.replace("\"", "") + "\"}");
            } catch (Exception e) {
                updateChecked("{\"error\":\"" + (e.getMessage() != null ? e.getMessage().replace("\"", "'") : "network error") + "\"}");
            }
        }).start();
    }

    private void updateChecked(final String json) {
        final String safe = json.replace("\\", "\\\\").replace("'", "\\'");
        activity.runOnUiThread(() -> webView.evaluateJavascript(
                "window.__onUpdateChecked && window.__onUpdateChecked('" + safe + "')", null));
    }

    /** Download the release APK and open the system installer for it.
     *  Result reaches JS as window.__onUpdateInstall(result): "ok",
     *  "need-permission" (Install unknown apps is off for this app), or an
     *  error message. The APK is served from the app cache through
     *  ApkProvider — there is no AndroidX FileProvider in this project. */
    @JavascriptInterface
    public void installUpdate(final String apkUrl) {
        new Thread(() -> {
            try {
                java.io.File dir = new java.io.File(activity.getCacheDir(), "updates");
                if (!dir.exists()) dir.mkdirs();
                java.io.File apk = new java.io.File(dir, "job-tracker-update.apk");
                java.net.URL url = new java.net.URL(apkUrl);
                java.net.HttpURLConnection c = (java.net.HttpURLConnection) url.openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(60000);
                c.setRequestProperty("User-Agent", "JobTracker-Updater");
                c.setInstanceFollowRedirects(true);
                int code = c.getResponseCode();
                if (code != 200) {
                    updateInstalled("download failed (HTTP " + code + ")");
                    return;
                }
                try (InputStream in = c.getInputStream();
                     java.io.FileOutputStream out = new java.io.FileOutputStream(apk)) {
                    byte[] buf = new byte[16384];
                    int n;
                    while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                }
                if (apk.length() < 1000000) { // sanity: a real build is ~8 MB
                    updateInstalled("downloaded file looks too small to be the app");
                    return;
                }
                final java.io.File apkFile = apk;
                activity.runOnUiThread(() -> {
                    try {
                        if (!activity.getPackageManager().canRequestPackageInstalls()) {
                            updateInstalled("need-permission");
                            return;
                        }
                        Uri uri = ApkProvider.getUri(activity, apkFile);
                        Intent i = new Intent(Intent.ACTION_VIEW);
                        i.setDataAndType(uri, "application/vnd.android.package-archive");
                        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        activity.startActivity(i);
                        updateInstalled("ok");
                    } catch (Exception e) {
                        updateInstalled(e.getMessage() != null ? e.getMessage() : "installer could not be opened");
                    }
                });
            } catch (Exception e) {
                updateInstalled(e.getMessage() != null ? e.getMessage() : "download failed");
            }
        }).start();
    }

    private void updateInstalled(final String result) {
        final String safe = (result == null ? "" : result).replace("\\", "\\\\").replace("'", "\\'");
        activity.runOnUiThread(() -> webView.evaluateJavascript(
                "window.__onUpdateInstall && window.__onUpdateInstall('" + safe + "')", null));
    }

    /** Open Android's per-app "Install unknown apps" setting for this app. */
    @JavascriptInterface
    public void openInstallSettings() {
        activity.runOnUiThread(() -> {
            try {
                Intent i = new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
                i.setData(Uri.parse("package:" + activity.getPackageName()));
                activity.startActivity(i);
            } catch (Exception ignored) {}
        });
    }
}
