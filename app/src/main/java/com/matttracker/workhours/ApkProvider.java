package com.matttracker.workhours;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Minimal content provider that serves the downloaded update APK from the
 * app cache to the system package installer. This project has no AndroidX,
 * so FileProvider is not available — this is the same job in ~40 lines:
 * it only ever serves files inside getCacheDir()/updates/, and refuses
 * anything that would escape that directory.
 */
public class ApkProvider extends ContentProvider {

    public static Uri getUri(android.app.Activity activity, File file) {
        return new Uri.Builder()
                .scheme("content")
                .authority(activity.getPackageName() + ".fileprovider")
                .appendPath(file.getName())
                .build();
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        try {
            File dir = new File(getContext().getCacheDir(), "updates");
            File wanted = new File(dir, uri.getLastPathSegment()).getCanonicalFile();
            if (!wanted.getPath().startsWith(dir.getCanonicalPath() + File.separator)) {
                throw new FileNotFoundException("outside updates dir");
            }
            if (!wanted.isFile()) throw new FileNotFoundException("no such file");
            return ParcelFileDescriptor.open(wanted, ParcelFileDescriptor.MODE_READ_ONLY);
        } catch (FileNotFoundException e) {
            throw e;
        } catch (Exception e) {
            throw new FileNotFoundException("cannot open file");
        }
    }

    @Override
    public String getType(Uri uri) {
        return "application/vnd.android.package-archive";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
