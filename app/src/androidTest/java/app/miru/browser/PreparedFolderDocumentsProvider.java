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
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Static Java-only source metadata and bytes; real DocumentsUI grants all target-app access. */
public final class PreparedFolderDocumentsProvider extends DocumentsProvider {
    public static final String AUTHORITY = "app.miru.browser.test.prepared-folders";
    public static final String ROOT_ID = "prepared-root";
    public static final String ROOT_LABEL = "Miru prepared metadata tree";
    public static final String ROOT_NAME = "PreparedRoot";
    public static final String REJECT_ID = "unconvertible-root";
    public static final String REJECT_LABEL = "Miru virtual rejection tree";
    public static final String REJECT_NAME = "UnconvertibleRoot";
    public static final String UNKNOWN_TIME_NAME = "unknown-time.txt";
    private static final long ORDINARY_TIME = 1_700_000_000_000L;
    private static final Map<String, Node> NODES = new LinkedHashMap<>();
    static {
        dir(ROOT_ID, ROOT_NAME, null);
        dir(REJECT_ID, REJECT_NAME, null);
        file("extensionless-png", "picture-without-extension", ROOT_ID, "image/png", 0L,
                UploadFixtureDocumentsProvider.PNG_BYTES);
        file("conflicting-extension", "declared.bin", ROOT_ID, "text/plain", ORDINARY_TIME,
                text("The provider explicitly declares this ordinary text as text/plain.\n"));
        file("same-first", "same.txt", ROOT_ID, "text/plain", -1L, text("First same-path source\n"));
        file("same-second", "same.txt", ROOT_ID, "text/plain", ORDINARY_TIME + 123L,
                text("Second same-path source has independent original bytes and length\n"));
        dir("same-directory-first", "same-dir", ROOT_ID);
        dir("same-directory-second", "same-dir", ROOT_ID);
        file("inside-first", "inside.txt", "same-directory-first", "text/plain", 0L,
                text("First duplicate-directory branch\n"));
        file("inside-second", "inside.txt", "same-directory-second", "text/plain", ORDINARY_TIME + 456L,
                text("Second duplicate-directory branch contains different bytes\n"));
        file("clashing-file", "clash", ROOT_ID, "text/plain", ORDINARY_TIME,
                text("A file sharing its display name with a sibling directory\n"));
        dir("clashing-directory", "clash", ROOT_ID);
        file("clash-child", "leaf.txt", "clashing-directory", "text/plain", ORDINARY_TIME,
                text("The sibling directory's independent leaf\n"));
        dir("unicode-directory", "图片 与 空格", ROOT_ID);
        file("unicode-png", "图.png", "unicode-directory", "image/png", ORDINARY_TIME,
                UploadFixtureDocumentsProvider.PNG_BYTES);
        file("zero-file", "zero.txt", ROOT_ID, "text/plain", 0L, new byte[0]);
        file("unknown-time", UNKNOWN_TIME_NAME, ROOT_ID, "text/plain", null,
                text("No source last-modified value is declared for this file.\n"));
        String parent = ROOT_ID;
        for(int depth = 1; depth <= 9; depth++) {
            String id = "deep-" + depth;
            dir(id, "d" + depth, parent);
            parent = id;
        }
        file("deep-binary", "deep.bin", parent, "application/octet-stream", ORDINARY_TIME,
                new byte[] {0, -1, 5, 0, 9, -128, 10});
        // A normal readable item is deliberately mixed with an unconvertible virtual item.
        file("rejection-normal", "safe.txt", REJECT_ID, "text/plain", ORDINARY_TIME,
                text("This normal file must never be partially delivered.\n"));
        NODES.put("rejection-virtual", new Node("rejection-virtual", "source.virtual", REJECT_ID,
                "application/vnd.miru.virtual", ORDINARY_TIME, null, true));
    }

    private static final String[] ROOT_COLUMNS = { Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID,
        Root.COLUMN_TITLE, Root.COLUMN_FLAGS, Root.COLUMN_MIME_TYPES, Root.COLUMN_ICON };
    private static final String[] DOCUMENT_COLUMNS = { Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED };

    @Override public boolean onCreate() { return true; }

    @Override public Cursor queryRoots(String[] projection) {
        MatrixCursor cursor = new MatrixCursor(projection != null ? projection : ROOT_COLUMNS);
        root(cursor, ROOT_ID, ROOT_LABEL);
        root(cursor, REJECT_ID, REJECT_LABEL);
        return cursor;
    }

    @Override public Cursor queryDocument(String id, String[] projection) throws FileNotFoundException {
        MatrixCursor cursor = new MatrixCursor(projection != null ? projection : DOCUMENT_COLUMNS);
        document(cursor, node(id));
        return cursor;
    }

    @Override public Cursor queryChildDocuments(String parent, String[] projection, String sortOrder) throws FileNotFoundException {
        if(!Document.MIME_TYPE_DIR.equals(node(parent).mime)) throw new FileNotFoundException("Not a fixture directory");
        MatrixCursor cursor = new MatrixCursor(projection != null ? projection : DOCUMENT_COLUMNS);
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
        if(!"r".equals(mode)) throw new FileNotFoundException("Fixture is read-only");
        if(signal != null) signal.throwIfCanceled();
        Node node = node(id);
        if(node.virtual || node.bytes == null) throw new FileNotFoundException("No faithful ordinary byte stream");
        final ParcelFileDescriptor[] pipe;
        try { pipe = ParcelFileDescriptor.createPipe(); }
        catch(IOException failure) { throw new FileNotFoundException("Fixture pipe unavailable"); }
        Thread writer = new Thread(() -> {
            try(ParcelFileDescriptor.AutoCloseOutputStream out = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                out.write(node.bytes);
            } catch(IOException cancelled) { /* Cancellation does not log source names or contents. */ }
        }, "miru-prepared-fixture-pipe");
        writer.setDaemon(true);
        writer.start();
        return pipe[0];
    }

    /** Source records form a list: duplicate logical relative paths must remain separate entries. */
    public static List<ExpectedFile> expectedFiles() {
        List<ExpectedFile> result = new ArrayList<>();
        for(Node node : NODES.values()) if(node.bytes != null) {
            String path = node.name;
            Node parent = NODES.get(node.parent);
            while(parent != null) { path = parent.name + "/" + path; parent = NODES.get(parent.parent); }
            if(path.startsWith(ROOT_NAME + "/"))
                result.add(new ExpectedFile(path, node.name, node.mime, node.lastModified, node.bytes.clone()));
        }
        return Collections.unmodifiableList(result);
    }

    public static final class ExpectedFile {
        public final String path;
        public final String name;
        public final String mime;
        public final Long lastModified;
        public final byte[] bytes;
        private ExpectedFile(String path, String name, String mime, Long lastModified, byte[] bytes) {
            this.path = path; this.name = name; this.mime = mime; this.lastModified = lastModified; this.bytes = bytes;
        }
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
        row.put(Document.COLUMN_MIME_TYPE, node.mime);
        row.put(Document.COLUMN_FLAGS, node.virtual ? Document.FLAG_VIRTUAL_DOCUMENT : 0);
        row.put(Document.COLUMN_SIZE, node.bytes == null ? null : (long) node.bytes.length);
        // A null column is genuinely unknown, distinct from both epoch zero and negative time.
        row.put(Document.COLUMN_LAST_MODIFIED, node.lastModified);
        append(cursor, row);
    }

    private static void append(MatrixCursor cursor, Map<String, Object> values) {
        MatrixCursor.RowBuilder row = cursor.newRow();
        for(String column : cursor.getColumnNames()) row.add(values.get(column));
    }

    private static Node node(String id) throws FileNotFoundException {
        Node node = NODES.get(id);
        if(node == null) throw new FileNotFoundException("Unknown fixture entry");
        return node;
    }
    private static byte[] text(String text) { return text.getBytes(StandardCharsets.UTF_8); }
    private static void dir(String id, String name, String parent) {
        NODES.put(id, new Node(id, name, parent, Document.MIME_TYPE_DIR, ORDINARY_TIME, null, false));
    }
    private static void file(String id, String name, String parent, String mime, Long lastModified, byte[] bytes) {
        NODES.put(id, new Node(id, name, parent, mime, lastModified, bytes.clone(), false));
    }
    private static final class Node {
        final String id, name, parent, mime;
        final Long lastModified;
        final byte[] bytes;
        final boolean virtual;
        Node(String id, String name, String parent, String mime, Long lastModified, byte[] bytes, boolean virtual) {
            this.id = id; this.name = name; this.parent = parent; this.mime = mime;
            this.lastModified = lastModified; this.bytes = bytes; this.virtual = virtual;
        }
    }
}
