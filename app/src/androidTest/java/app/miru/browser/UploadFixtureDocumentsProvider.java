// Copyright (c) 2026 Ringophilia and xiaomao
// SPDX-License-Identifier: MPL-2.0
package app.miru.browser;

import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import android.util.Base64;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Static synthetic documents in the test APK, selected through the real DocumentsUI.
 *
 * <p>Android/Java dependencies only: DocumentsUI starts this provider in the separate test-package
 * process, which cannot assume the target APK's Kotlin runtime is on its classpath. No control IPC,
 * user files, logging, or fabricated ActivityResults are provided.
 */
public final class UploadFixtureDocumentsProvider extends DocumentsProvider {
    public static final String AUTHORITY = "app.miru.browser.test.upload-fixtures";
    public static final String ROOT_ID = "upload-fixture-root";
    public static final String ROOT_LABEL = "Miru upload fixtures";
    public static final String SINGLE_NAME = "single.txt";
    public static final String SAME_NAME = "same.txt";
    public static final byte[] SINGLE_BYTES =
            "Miru synthetic single upload\nsecond fixture line\n".getBytes(StandardCharsets.UTF_8);
    public static final byte[] SAME_A_BYTES =
            "Miru duplicate basename A\n".getBytes(StandardCharsets.UTF_8);
    public static final byte[] SAME_B_BYTES =
            "Miru duplicate basename B has distinct bytes and length\n".getBytes(StandardCharsets.UTF_8);
    // One grayscale/alpha pixel. PNG chunk CRCs and the decompressed scanline were checked statically.
    public static final byte[] PNG_BYTES = Base64.decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+ip1sAAAAASUVORK5CYII=",
            Base64.DEFAULT);

    private static final SyntheticFile[] FILES = {
        new SyntheticFile("single", SINGLE_NAME, "text/plain", SINGLE_BYTES),
        new SyntheticFile("same-a", SAME_NAME, "text/plain", SAME_A_BYTES),
        new SyntheticFile("same-b", SAME_NAME, "text/plain", SAME_B_BYTES),
        new SyntheticFile("image", "fixture.png", "image/png", PNG_BYTES),
    };
    private static final String[] ROOT_COLUMNS = {
        Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_SUMMARY,
        Root.COLUMN_FLAGS, Root.COLUMN_MIME_TYPES, Root.COLUMN_AVAILABLE_BYTES, Root.COLUMN_ICON,
    };
    private static final String[] DOCUMENT_COLUMNS = {
        Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
        Document.COLUMN_FLAGS, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED,
    };

    @Override public boolean onCreate() { return true; }

    @Override public Cursor queryRoots(String[] projection) {
        MatrixCursor cursor = new MatrixCursor(projection != null ? projection : ROOT_COLUMNS);
        Map<String, Object> row = new HashMap<>();
        row.put(Root.COLUMN_ROOT_ID, ROOT_ID);
        row.put(Root.COLUMN_DOCUMENT_ID, ROOT_ID);
        row.put(Root.COLUMN_TITLE, ROOT_LABEL);
        row.put(Root.COLUMN_SUMMARY, "Synthetic upload integration files");
        row.put(Root.COLUMN_FLAGS, Root.FLAG_LOCAL_ONLY | Root.FLAG_SUPPORTS_IS_CHILD);
        row.put(Root.COLUMN_MIME_TYPES, "text/plain\nimage/png");
        row.put(Root.COLUMN_AVAILABLE_BYTES, 1024L * 1024);
        row.put(Root.COLUMN_ICON, android.R.drawable.ic_menu_upload);
        appendValues(cursor, row);
        return cursor;
    }

    @Override public Cursor queryDocument(String documentId, String[] projection)
            throws FileNotFoundException {
        MatrixCursor cursor = new MatrixCursor(projection != null ? projection : DOCUMENT_COLUMNS);
        appendDocument(cursor, documentId);
        return cursor;
    }

    @Override public Cursor queryChildDocuments(
            String parentDocumentId, String[] projection, String sortOrder) throws FileNotFoundException {
        if (!ROOT_ID.equals(parentDocumentId)) throw new FileNotFoundException("Unknown synthetic directory");
        MatrixCursor cursor = new MatrixCursor(projection != null ? projection : DOCUMENT_COLUMNS);
        for (SyntheticFile item : FILES) appendDocument(cursor, item.id);
        return cursor;
    }

    @Override public boolean isChildDocument(String parentDocumentId, String documentId) {
        if (!ROOT_ID.equals(parentDocumentId)) return false;
        for (SyntheticFile item : FILES) if (item.id.equals(documentId)) return true;
        return false;
    }

    @Override public String getDocumentType(String documentId) throws FileNotFoundException {
        return ROOT_ID.equals(documentId) ? Document.MIME_TYPE_DIR : fixture(documentId).mime;
    }

    @Override public ParcelFileDescriptor openDocument(
            String documentId, String mode, CancellationSignal signal) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Synthetic documents are read-only");
        if (signal != null) signal.throwIfCanceled();
        byte[] bytes = fixture(documentId).bytes;
        final ParcelFileDescriptor[] pipe;
        try { pipe = ParcelFileDescriptor.createPipe(); }
        catch (IOException unavailable) { throw new FileNotFoundException("Synthetic document pipe unavailable"); }
        Thread writer = new Thread(() -> {
            try (ParcelFileDescriptor.AutoCloseOutputStream output =
                         new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                output.write(bytes);
            } catch (IOException cancelledRead) {
                // A picker or cancelled upload may close its read end. Do not log its inputs.
            }
        }, "miru-upload-fixture-pipe");
        writer.setDaemon(true);
        writer.start();
        return pipe[0];
    }

    private static void appendDocument(MatrixCursor cursor, String id) throws FileNotFoundException {
        SyntheticFile item = ROOT_ID.equals(id) ? null : fixture(id);
        Map<String, Object> row = new HashMap<>();
        row.put(Document.COLUMN_DOCUMENT_ID, id);
        row.put(Document.COLUMN_DISPLAY_NAME, item != null ? item.name : ROOT_LABEL);
        row.put(Document.COLUMN_MIME_TYPE, item != null ? item.mime : Document.MIME_TYPE_DIR);
        row.put(Document.COLUMN_FLAGS, 0);
        row.put(Document.COLUMN_SIZE, item != null ? (long) item.bytes.length : null);
        row.put(Document.COLUMN_LAST_MODIFIED, 1_700_000_000_000L);
        appendValues(cursor, row);
    }

    private static void appendValues(MatrixCursor cursor, Map<String, Object> values) {
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : cursor.getColumnNames()) row.add(values.get(column));
    }

    private static SyntheticFile fixture(String id) throws FileNotFoundException {
        for (SyntheticFile item : FILES) if (item.id.equals(id)) return item;
        throw new FileNotFoundException("Unknown synthetic document");
    }

    private static final class SyntheticFile {
        final String id;
        final String name;
        final String mime;
        final byte[] bytes;
        SyntheticFile(String id, String name, String mime, byte[] bytes) {
            this.id = id;
            this.name = name;
            this.mime = mime;
            this.bytes = bytes;
        }
    }
}
