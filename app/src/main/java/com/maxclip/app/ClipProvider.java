package com.maxclip.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Serves stored clips as content:// streams. Large clips go on the system clipboard as a URI
 * instead of raw text, which sidesteps Android's ~1 MB Binder limit: the pasting app streams the
 * full text from here (TextView/EditText paste does this automatically via coerceToText).
 */
public class ClipProvider extends ContentProvider {
    static final String AUTHORITY = "com.maxclip.app.clips";

    static Uri uriFor(File f) {
        return Uri.parse("content://" + AUTHORITY + "/" + f.getName());
    }

    private File fileFor(Uri uri) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        if (name == null || name.contains("/") || name.contains("..") || !name.endsWith(".txt")) {
            throw new FileNotFoundException("Bad clip uri");
        }
        File f = new File(Store.dir(getContext()), name);
        if (!f.exists()) throw new FileNotFoundException("Clip no longer exists");
        return f;
    }

    @Override public boolean onCreate() { return true; }

    @Override public String getType(Uri uri) { return "text/plain"; }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Read-only");
        return ParcelFileDescriptor.open(fileFor(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String sel, String[] args, String sort) {
        File f;
        try {
            f = fileFor(uri);
        } catch (FileNotFoundException e) {
            return null;
        }
        String[] cols = projection != null ? projection
                : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        Object[] row = new Object[cols.length];
        for (int i = 0; i < cols.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) row[i] = "clip-" + f.getName();
            else if (OpenableColumns.SIZE.equals(cols[i])) row[i] = f.length();
        }
        MatrixCursor c = new MatrixCursor(cols, 1);
        c.addRow(row);
        return c;
    }

    @Override public Uri insert(Uri uri, ContentValues v) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String s, String[] a) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException(); }
}
