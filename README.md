# Job Tracker

Matt's shift clock-in and timesheet app. An Android WebView wrapper around a
single-file offline web app — the whole app is one HTML file
(`app/src/main/assets/www/index.html`, also kept at `Job_Tracker.html` in the
deliverables folder). Everything runs on-device: shifts, placement-sheet
scanning, timesheets, Word/PDF export, reminders, calendar, finance. The
only network touches are the location map tiles and the in-app update check
(when the user turns that on).

## Layout

- `app/src/main/java/com/matttracker/workhours/` — the Android wrapper
  (Java, no AndroidX, no Gradle daemon in this sandbox):
  - `MainActivity.java` — WebView host, icon-theme swap, camera/scan flow.
  - `NativeBridge.java` — the `NativeBridge` JS bridge: durable storage,
    timer notifications, reminders, exports, maps, and the app updater
    (GitHub release check, APK download, install).
  - `ApkProvider.java` — minimal content provider serving the downloaded
    update APK from the app cache to the system installer.
  - `TimerNotifier.java`, `ReminderNotifier.java`, `ReminderReceiver.java`,
    `TimerActionReceiver.java` — notifications and alarms.
- `app/src/main/assets/www/index.html` — the app itself. Edit the
  deliverable copy, then sync it here before building.
- `app/src/main/AndroidManifest.xml` — package `com.matttracker.workhours`,
  version in `versionCode`/`versionName` (kept in step with `app/build.gradle`).

## Building

Gradle is broken in this sandbox, so the APK is built by hand
(`build_apk.sh`, JDK 17, SDK 34):

```sh
./build_apk.sh
```

That runs aapt2 compile/link (`--java` for the generated R class), javac,
d8, zipalign and a debug sign, and writes `JobTracker.apk` (rename it to
`job tracker vX.Y.apk` for delivery; delete the unversioned copy after).
Keep old versioned APKs. Signing is debug-only.

## Updates

Settings → App updates checks
`https://api.github.com/repos/cartchai/job-tracker/releases/latest` for a
newer release tag, downloads the `.apk` asset and opens the system
installer. An optional "Check for updates on startup" switch (off by
default) checks once on launch and only says anything if an update is
ready. Every version gets a GitHub Release with the APK attached.

## Notes

- Storage keys live in `BACKUP_KEYS` in the HTML and in `wipeAllData` —
  a new key goes in both.
- The HTML has one extra `</div>` (582 opens / 583 closes). The browser
  tolerates it; do not fix it by guessing.
- Personal project — only the owner pushes to this repo.
