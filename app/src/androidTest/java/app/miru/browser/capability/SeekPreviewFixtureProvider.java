// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser.capability;

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
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Test APK only, one public synthetic read-only video. Framework/Java-only because
 * this provider runs in the standalone test APK process, without main's Kotlin runtime.
 */
public final class SeekPreviewFixtureProvider extends ContentProvider {
    public static final Uri URI = Uri.parse("content://app.miru.browser.test.seek-preview/colors.mp4");

    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) { return URI.equals(uri) ? "video/mp4" : null; }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!URI.equals(uri) || !"r".equals(mode)) throw new FileNotFoundException("Unknown read-only fixture");
        Context owner = getContext();
        if (owner == null) throw new FileNotFoundException("Missing fixture context");
        File file = new File(owner.getCacheDir(), "seek-preview-colors.mp4");
        synchronized (SeekPreviewFixtureProvider.class) {
            File fresh = null;
            try {
                // Atomically refresh to avoid install -r reusing an older fixture.
                fresh = File.createTempFile("seek-preview-", ".mp4", owner.getCacheDir());
                try (InputStream input = owner.getAssets().open("media/seek-preview/colors.mp4");
                     FileOutputStream output = new FileOutputStream(fresh)) {
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
                }
                if (!fresh.renameTo(file)) throw new IOException("Could not refresh synthetic content fixture");
            } catch (IOException error) {
                FileNotFoundException failure = new FileNotFoundException("Synthetic content fixture unavailable");
                failure.initCause(error);
                throw failure;
            } finally { if (fresh != null) fresh.delete(); }
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        if (!URI.equals(uri)) return null;
        String[] columns = projection != null ? projection : new String[] { OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE };
        MatrixCursor cursor = new MatrixCursor(columns);
        Object[] row = new Object[columns.length];
        for (int index = 0; index < columns.length; index++) {
            if (OpenableColumns.DISPLAY_NAME.equals(columns[index])) row[index] = "colors.mp4";
            // ContentDataSource obtains its actual length from the file descriptor.
            if (OpenableColumns.SIZE.equals(columns[index])) row[index] = null;
        }
        cursor.addRow(row);
        return cursor;
    }

    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
