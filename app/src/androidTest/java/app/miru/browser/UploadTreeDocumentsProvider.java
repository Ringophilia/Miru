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
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** A static read-only tree in the separate test APK. Only real DocumentsUI can grant access. */
public final class UploadTreeDocumentsProvider extends DocumentsProvider {
    public static final String AUTHORITY = "app.miru.browser.test.upload-trees";
    public static final String ROOT_ID = "full-root";
    public static final String EMPTY_ID = "empty-root";
    public static final String ROOT_LABEL = "Miru tree fixtures";
    public static final String EMPTY_LABEL = "Miru empty tree";
    public static final String ROOT_NAME = "MiruTree";
    public static final String EMPTY_NAME = "EmptyRoot";
    public static final String MIME_ROOT_ID = "mime-root";
    public static final String MIME_ROOT_LABEL = "Miru explicit MIME tree";
    private static final Map<String, Node> NODES = new LinkedHashMap<>();
    static {
        dir(ROOT_ID, ROOT_NAME, null);
        dir(EMPTY_ID, EMPTY_NAME, null);
        dir(MIME_ROOT_ID, "ExplicitMimeRoot", null);
        file("extensionless", "picture-without-extension", MIME_ROOT_ID, "image/png", UploadFixtureDocumentsProvider.PNG_BYTES);
        file("top", "root.txt", ROOT_ID, "text/plain", text("Miru root bytes\n"));
        dir("left", "left", ROOT_ID);
        dir("right", "right", ROOT_ID);
        file("left-file", "same.txt", "left", "text/plain", text("Left original\n"));
        file("right-file", "same.txt", "right", "text/plain", text("Right distinct original bytes\n"));
        dir("unicode", "图片 与 空格", ROOT_ID);
        file("png", "图.png", "unicode", "image/png", UploadFixtureDocumentsProvider.PNG_BYTES);
        file("zero", "zero.txt", ROOT_ID, "text/plain", new byte[0]);
        dir("empty-child", "empty-subdir", ROOT_ID);
        String parent = ROOT_ID;
        for(int depth = 1; depth <= 8; depth++) {
            String id = "deep-" + depth;
            dir(id, "d" + depth, parent);
            parent = id;
        }
        file("deep-file", "deep.bin", parent, "application/octet-stream", new byte[] {0, -1, 4, 0, 9});
    }

    private static final String[] ROOT_COLUMNS = { Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID,
        Root.COLUMN_TITLE, Root.COLUMN_FLAGS, Root.COLUMN_MIME_TYPES, Root.COLUMN_ICON };
    private static final String[] DOC_COLUMNS = { Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED };

    @Override public boolean onCreate() { return true; }

    @Override public Cursor queryRoots(String[] projection) {
        MatrixCursor cursor = new MatrixCursor(projection != null ? projection : ROOT_COLUMNS);
        root(cursor, ROOT_ID, ROOT_LABEL);
        root(cursor, EMPTY_ID, EMPTY_LABEL);
        root(cursor, MIME_ROOT_ID, MIME_ROOT_LABEL);
        return cursor;
    }

    @Override public Cursor queryDocument(String id, String[] projection) throws FileNotFoundException {
        MatrixCursor cursor = new MatrixCursor(projection != null ? projection : DOC_COLUMNS);
        document(cursor, node(id));
        return cursor;
    }

    @Override public Cursor queryChildDocuments(String parent, String[] projection, String sortOrder) throws FileNotFoundException {
        if(node(parent).bytes != null) throw new FileNotFoundException("Not a synthetic directory");
        MatrixCursor cursor = new MatrixCursor(projection != null ? projection : DOC_COLUMNS);
        for(Node node : NODES.values()) if(parent.equals(node.parent)) document(cursor, node);
        return cursor;
    }

    @Override public boolean isChildDocument(String parent, String child) {
        Node current = NODES.get(child);
        for(int remaining = NODES.size(); current != null && current.parent != null && remaining > 0; remaining--) {
            if(parent.equals(current.parent)) return true;
            current = NODES.get(current.parent);
        }
        return false;
    }

    @Override public String getDocumentType(String id) throws FileNotFoundException { return node(id).mime; }

    @Override public ParcelFileDescriptor openDocument(String id, String mode, CancellationSignal signal) throws FileNotFoundException {
        if(!"r".equals(mode)) throw new FileNotFoundException("Synthetic tree is read-only");
        if(signal != null) signal.throwIfCanceled();
        byte[] bytes = node(id).bytes;
        if(bytes == null) throw new FileNotFoundException("A directory has no file stream");
        final ParcelFileDescriptor[] pipe;
        try { pipe = ParcelFileDescriptor.createPipe(); }
        catch(IOException failure) { throw new FileNotFoundException("Synthetic tree pipe unavailable"); }
        Thread writer = new Thread(() -> {
            try(ParcelFileDescriptor.AutoCloseOutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) { out.write(bytes); }
            catch(IOException cancelled) { /* The reader may cancel; no source names or contents are logged. */ }
        }, "miru-tree-fixture-pipe");
        writer.setDaemon(true); writer.start();
        return pipe[0];
    }

    /** Original bytes keyed by actual webkitRelativePath, used only to check real website POSTs. */
    public static Map<String, byte[]> expectedFiles() {
        Map<String, byte[]> result = new LinkedHashMap<>();
        for(Node node : NODES.values()) if(node.bytes != null) {
            String path = node.name;
            Node parent = NODES.get(node.parent);
            while(parent != null) { path = parent.name + "/" + path; parent = NODES.get(parent.parent); }
            if(path.startsWith(ROOT_NAME + "/")) result.put(path, node.bytes.clone());
        }
        return result;
    }

    private static void root(MatrixCursor cursor, String id, String label) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put(Root.COLUMN_ROOT_ID, id); row.put(Root.COLUMN_DOCUMENT_ID, id);
        row.put(Root.COLUMN_TITLE, label); row.put(Root.COLUMN_FLAGS, Root.FLAG_LOCAL_ONLY | Root.FLAG_SUPPORTS_IS_CHILD);
        row.put(Root.COLUMN_MIME_TYPES, "*/*"); row.put(Root.COLUMN_ICON, android.R.drawable.ic_menu_upload);
        append(cursor, row);
    }
    private static void document(MatrixCursor cursor, Node node) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put(Document.COLUMN_DOCUMENT_ID, node.id); row.put(Document.COLUMN_DISPLAY_NAME, node.name);
        row.put(Document.COLUMN_MIME_TYPE, node.mime); row.put(Document.COLUMN_FLAGS, 0);
        row.put(Document.COLUMN_SIZE, node.bytes == null ? null : (long) node.bytes.length);
        row.put(Document.COLUMN_LAST_MODIFIED, 1_700_000_000_000L); append(cursor, row);
    }
    private static void append(MatrixCursor cursor, Map<String, Object> values) {
        MatrixCursor.RowBuilder row = cursor.newRow();
        for(String column : cursor.getColumnNames()) row.add(values.get(column));
    }
    private static Node node(String id) throws FileNotFoundException {
        Node result = NODES.get(id);
        if(result == null) throw new FileNotFoundException("Unknown synthetic tree entry");
        return result;
    }
    private static byte[] text(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static void dir(String id, String name, String parent) { NODES.put(id, new Node(id, name, parent, Document.MIME_TYPE_DIR, null)); }
    private static void file(String id, String name, String parent, String mime, byte[] bytes) { NODES.put(id, new Node(id, name, parent, mime, bytes)); }
    private static final class Node {
        final String id, name, parent, mime;
        final byte[] bytes;
        Node(String id, String name, String parent, String mime, byte[] bytes) {
            this.id = id; this.name = name; this.parent = parent; this.mime = mime; this.bytes = bytes;
        }
    }
}
