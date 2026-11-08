package com.matttracker.workhours;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.webkit.GeolocationPermissions;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public class MainActivity extends Activity {
    private WebView webView;
    private NativeBridge bridge;
    private static final int REQ_GEO = 2001;
    private static final int REQ_CAMERA_SCAN = 2002;
    private static final int REQ_NOTIF = 2003;
    private static WebView sWebView;
    private boolean pendingNotifClockOut = false;
    private boolean pendingOpenTimer = false;
    private boolean pendingOpenReminders = false;
    private GeolocationPermissions.Callback pendingGeoCallback;
    private String pendingGeoOrigin;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Status bar (8.3.14): edge-to-edge with transparent system bars.
        // The WebView draws under the status bar and the web page's own
        // background shows through, so the bar always matches the theme
        // (including accent changes) with no native colour to keep in sync.
        // The page pads its content with env(safe-area-inset-*) and the
        // viewport already has viewport-fit=cover, so nothing is obstructed.
        enableEdgeToEdge();

        // Theming (8.0): start every session with the icon set reconciled.
        NativeBridge.reconcileIconTheme(this);

        // Theming (8.0): tint the window behind the WebView with the deep
        // shade of the saved accent, matching the in-app background. This is
        // only the launch flash behind the WebView — the status and nav bars
        // themselves are transparent (see enableEdgeToEdge) so the page's own
        // background shows through them.
        try {
            String accent = getSharedPreferences("wht_store", MODE_PRIVATE).getString("wh_accent", null);
            if (accent != null && accent.length() == 7) {
                int rgb = Integer.parseInt(accent.substring(1), 16);
                float[] hsv = new float[3];
                android.graphics.Color.RGBToHSV((rgb >> 16) & 255, (rgb >> 8) & 255, rgb & 255, hsv);
                int deep = android.graphics.Color.HSVToColor(new float[]{hsv[0], 0.26f, 0.12f});
                getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(deep));
            }
        } catch (Exception e) {
            // The static theme colours remain the fallback.
        }

        webView = new WebView(this);
        setContentView(webView);
        applySystemBarTheme();

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(true);

        bridge = new NativeBridge(this, webView);
        webView.addJavascriptInterface(bridge, "NativeBridge");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                if (pendingNotifClockOut) {
                    pendingNotifClockOut = false;
                    clockOutInWebView();
                }
                if (pendingOpenTimer || pendingOpenReminders) {
                    evaluateOpenTab();
                }
            }
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("file://")) {
                    return false;
                }
                // e.g. geo: links from "Open in Maps" -> hand to a maps app
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url));
                    MainActivity.this.startActivity(intent);
                } catch (Exception e) { /* no app to handle it */ }
                return true;
            }
        });
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                    callback.invoke(origin, true, false);
                } else {
                    pendingGeoCallback = callback;
                    pendingGeoOrigin = origin;
                    requestPermissions(new String[]{ Manifest.permission.ACCESS_FINE_LOCATION }, REQ_GEO);
                }
            }
        });

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState);
        } else {
            webView.loadUrl("file:///android_asset/www/index.html");
        }
        sWebView = webView;

        // Ask for notification permission on launch (Android 13+)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{ Manifest.permission.POST_NOTIFICATIONS }, REQ_NOTIF);
            }
        }
        handleNotifIntent(getIntent());
    }

    /** Status bar (8.3.14): draw the WebView edge-to-edge under the system
     *  bars and make the bars transparent, so the page's own background
     *  shows through them. The icon colour is set separately by
     *  applySystemBarTheme (it needs the decor view, so it runs after
     *  setContentView). */
    private void enableEdgeToEdge() {
        try {
            android.view.Window w = getWindow();
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                w.setDecorFitsSystemWindows(false);
            } else {
                w.getDecorView().setSystemUiVisibility(
                        android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
            }
            w.setStatusBarColor(android.graphics.Color.TRANSPARENT);
            w.setNavigationBarColor(android.graphics.Color.TRANSPARENT);
        } catch (Exception ignored) {}
    }

    /** Status/nav bar icon colour follows the saved web theme (dark -> light icons). */
    private void applySystemBarTheme() {
        try {
            String theme = getSharedPreferences("wht_store", MODE_PRIVATE).getString("wh_theme", "dark");
            setSystemBarIconsDark(!"light".equals(theme));
        } catch (Exception ignored) {}
    }

    /** isDark = the app theme is dark, so the system icons must be LIGHT.
     *  (The flag names are from the system's point of view: LIGHT bars take DARK icons.)
     *  Called from the web app (via NativeBridge) when the theme toggles, and
     *  from onCreate via applySystemBarTheme. */
    void setSystemBarIconsDark(boolean isDark) {
        try {
            android.view.Window w = getWindow();
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                android.view.WindowInsetsController c = w.getInsetsController();
                if (c == null) return;
                int bars = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                        | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
                if (isDark) c.setSystemBarsAppearance(0, bars);
                else c.setSystemBarsAppearance(bars, bars);
            } else {
                int vis = w.getDecorView().getSystemUiVisibility();
                int light = android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                        | android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
                vis |= android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION;
                if (isDark) vis &= ~light;
                else vis |= light;
                w.getDecorView().setSystemUiVisibility(vis);
            }
        } catch (Exception ignored) {}
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleNotifIntent(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Re-post the shift notification if clocked in (it may have been dismissed).
        if (sWebView != null) {
            sWebView.evaluateJavascript(
                "(function(){ try { if (window.restoreNotifIfClockedIn) window.restoreNotifIfClockedIn(); } catch(e){} })()", null);
        }
    }

    /** Reminder notification taps: route to the timer page or the Reminders tab. */
    private void evaluateOpenTab() {
        final String js;
        if (pendingOpenTimer) {
            js = "(function(){ try { if (window.__openTimerTab) { window.__openTimerTab(); return true; } } catch(e){} return false; })()";
        } else if (pendingOpenReminders) {
            js = "(function(){ try { if (window.__openRemindersTab) { window.__openRemindersTab(); return true; } } catch(e){} return false; })()";
        } else {
            return;
        }
        webView.evaluateJavascript(js, value -> {
            if ("true".equals(value)) {
                pendingOpenTimer = false;
                pendingOpenReminders = false;
            }
        });
    }

    private void handleNotifIntent(Intent intent) {
        if (intent != null && intent.getBooleanExtra("open_timer", false)) {
            intent.removeExtra("open_timer");
            pendingOpenTimer = true;
            evaluateOpenTab();
        }
        if (intent != null && intent.getBooleanExtra("open_reminders", false)) {
            intent.removeExtra("open_reminders");
            pendingOpenReminders = true;
            evaluateOpenTab();
        }
        if (intent != null && intent.getBooleanExtra("notif_clock_out", false)) {
            intent.removeExtra("notif_clock_out");
            // Defer until the page has loaded (onPageFinished), else the JS hook may not exist yet.
            pendingNotifClockOut = true;
            // If the page is already loaded (e.g. activity was running), fire now.
            webView.evaluateJavascript(
                "(function(){return !!(window.notifClockOut);})()",
                value -> {
                    if ("true".equals(value) && pendingNotifClockOut) {
                        pendingNotifClockOut = false;
                        clockOutInWebView();
                    }
                });
        }
    }

    /** Called by TimerActionReceiver when the notification Stop button is tapped. */
    public static void requestClockOutFromNotification() {
        clockOutInWebView();
    }

    private static void clockOutInWebView() {
        final WebView wv = sWebView;
        if (wv == null) return;
        wv.post(new Runnable() {
            @Override public void run() {
                wv.evaluateJavascript(
                    "(function(){ try { if (window.notifClockOut) window.notifClockOut(); } catch(e){} })()", null);
            }
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_GEO && pendingGeoCallback != null) {
            boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            pendingGeoCallback.invoke(pendingGeoOrigin, granted, false);
            pendingGeoCallback = null;
            pendingGeoOrigin = null;
        } else if (requestCode == REQ_CAMERA_SCAN) {
            boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (granted) {
                bridge.launchScanCamera();
            } else {
                bridge.deliverScanDenied();
            }
        }
    }

    /** Scan tab: ensure CAMERA permission, then open the camera. Called from NativeBridge. */
    void requestCameraForScan() {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            bridge.launchScanCamera();
        } else {
            requestPermissions(new String[]{ Manifest.permission.CAMERA }, REQ_CAMERA_SCAN);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        webView.saveState(outState);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == NativeBridge.PICK_BACKUP) {
            bridge.onBackupPicked(resultCode == RESULT_OK ? data : null);
        } else if (requestCode == NativeBridge.PICK_SCAN_IMAGE) {
            bridge.onScanImagePicked(resultCode, data);
        }
    }

    @Override
    public void onBackPressed() {
        // if an in-page overlay is open (progress tracker, scan zoom, add
        // dialog, pickers, location editor...), back closes it instead of
        // the app; the page reports whether it handled the press
        webView.evaluateJavascript(
            "(function(){return !!(window.__handleBackPress && window.__handleBackPress());})()",
            value -> {
                if ("true".equals(value)) {
                    // handled inside the page
                } else if (webView.canGoBack()) {
                    webView.goBack();
                } else {
                    MainActivity.super.onBackPressed();
                }
            });
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Theming (8.0): finish a deferred launcher-icon swap now the app
        // has left the foreground (see NativeBridge.setIconTheme).
        try { if (bridge != null) bridge.runPendingIconCleanup(); } catch (Exception ignored) {}
    }

    protected void onDestroy() {
        if (sWebView == webView) sWebView = null;
        if (webView != null) {
            webView.destroy();
        }
        super.onDestroy();
    }
}
