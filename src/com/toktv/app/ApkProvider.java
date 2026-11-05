package com.toktv.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Serves only the downloaded update (cache/update/TokTV.apk) to the system installer, read-only,
 * through a temporary URI grant. Not exported. Stands in for AndroidX FileProvider.
 */
public final class ApkProvider extends ContentProvider {
    static String authority(Context c) { return c.getPackageName() + ".apk"; }

    static Uri uriFor(Context c, File apk) {
        return Uri.parse("content://" + authority(c) + "/" + apk.getName());
    }

    private File fileFor(Uri uri) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        if (!Updater.APK_NAME.equals(name)) throw new FileNotFoundException(String.valueOf(name));
        File f = new File(Updater.dir(getContext()), name);
        if (!f.isFile()) throw new FileNotFoundException(name);
        return f;
    }

    @Override
    public boolean onCreate() { return true; }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (mode != null && mode.contains("w")) throw new FileNotFoundException("read-only");
        return ParcelFileDescriptor.open(fileFor(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public String getType(Uri uri) { return "application/vnd.android.package-archive"; }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        try {
            File f = fileFor(uri);
            MatrixCursor c = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
            c.addRow(new Object[]{f.getName(), f.length()});
            return c;
        } catch (FileNotFoundException e) {
            return null;
        }
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) { return null; }

    @Override
    public int delete(Uri uri, String selection, String[] args) { return 0; }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
}
