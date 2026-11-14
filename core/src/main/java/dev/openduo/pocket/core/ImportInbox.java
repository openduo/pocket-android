// Copyright 2026 pocket-e2 contributors
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package dev.openduo.pocket.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;

/**
 * File I/O only, on one owner queue. A directory rename commits the manifest and its copied files
 * together. Admitted data stays here until draft/outbox cleanup; only the manifest is acknowledged.
 */
public final class ImportInbox {
    private static final String MANIFEST = "share.json";
    private static final long MANIFEST_MAX_BYTES = 8L * 1024 * 1024;
    private final File root;
    private long lastAt;

    public interface Copier {
        Attachment copy(int index, File destination) throws IOException;
    }

    public interface Guard {
        void check() throws IOException;
    }

    public static final class Scan {
        public final List<ImportBatch> batches;
        public final int unreadable;

        private Scan(List<ImportBatch> batches, int unreadable) {
            this.batches = Collections.unmodifiableList(batches);
            this.unreadable = unreadable;
        }
    }

    public ImportInbox(File root) {
        this.root = root;
    }

    public ImportBatch prepare(String id, String scope, String text, int count, Copier copier)
            throws IOException {
        return prepare(id, scope, text, "", count, copier);
    }

    public ImportBatch prepare(
            String id, String scope, String text, String ownedPhoto, int count, Copier copier)
            throws IOException {
        return prepare(id, scope, text, ownedPhoto, count, copier, () -> {});
    }

    public ImportBatch prepare(
            String id,
            String scope,
            String text,
            String ownedPhoto,
            int count,
            Copier copier,
            Guard guard)
            throws IOException {
        guard.check();
        ImportBatch.requireId(id);
        ImportBatch.requirePhotoName(ownedPhoto);
        if (count < 0) throw new IllegalArgumentException("Invalid import file count");
        ImportBatch existing = find(id);
        if (existing != null) return existing;
        ensure(root);
        File staged = directory(id + ".partial"), committed = directory(id);
        if (committed.exists()) throw new IOException("Delivery was already admitted");
        deleteTree(staged);
        ensure(staged);
        boolean complete = false;
        try {
            JSONArray entries = new JSONArray();
            for (int i = 0; i < count; i++) {
                guard.check();
                File destination = new File(staged, "file-" + i);
                Attachment a = copier.copy(i, destination);
                if (!destination.isFile()
                        || !destination
                                .getCanonicalPath()
                                .equals(new File(a.localPath).getCanonicalPath())
                        || a.size != destination.length())
                    throw new IOException("Incomplete imported file");
                entries.put(
                        new JSONObject()
                                .put("file", destination.getName())
                                .put("name", a.name)
                                .put("mime", a.mime)
                                .put("size", a.size));
            }
            guard.check();
            JSONObject manifest =
                    new JSONObject()
                            .put("version", 1)
                            .put("id", id)
                            .put("scope", scope)
                            .put("at", lastAt = Math.max(System.currentTimeMillis(), lastAt + 1))
                            .put("text", text == null ? "" : text)
                            .put("photo", ownedPhoto)
                            .put("files", entries);
            byte[] bytes = manifest.toString().getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MANIFEST_MAX_BYTES)
                throw new IOException("Import manifest too large");
            try (FileOutputStream out = new FileOutputStream(new File(staged, MANIFEST))) {
                out.write(bytes);
                out.getFD().sync();
            }
            guard.check();
            if (!staged.renameTo(committed)) throw new IOException("Cannot commit import delivery");
            complete = true;
            return find(id);
        } catch (JSONException failure) {
            throw new IOException("Invalid import manifest", failure);
        } finally {
            if (!complete) deleteTree(staged);
        }
    }

    public ImportBatch find(String id) throws IOException {
        return read(id, true);
    }

    private ImportBatch read(String id, boolean includeFiles) throws IOException {
        ImportBatch.requireId(id);
        File dir = directory(id), manifest = new File(dir, MANIFEST);
        if (!manifest.isFile()) return null;
        if (manifest.length() > MANIFEST_MAX_BYTES)
            throw new IOException("Import manifest too large");
        try {
            JSONObject json =
                    new JSONObject(
                            new String(
                                    Files.readAllBytes(manifest.toPath()), StandardCharsets.UTF_8));
            if (json.getInt("version") != 1 || !id.equals(json.getString("id")))
                throw new IOException("Invalid import version or identity");
            JSONArray entries = includeFiles ? json.getJSONArray("files") : new JSONArray();
            List<Attachment> files = new ArrayList<>();
            Set<String> names = new HashSet<>();
            for (int i = 0; i < entries.length(); i++) {
                JSONObject entry = entries.getJSONObject(i);
                String name = entry.getString("file");
                if (!name.equals("file-" + i) || !names.add(name))
                    throw new IOException("Invalid import file order");
                File file = new File(dir, name).getCanonicalFile();
                long size = entry.getLong("size");
                if (!dir.equals(file.getParentFile()) || !file.isFile() || size != file.length())
                    throw new IOException("Missing imported file");
                files.add(
                        new Attachment(
                                entry.getString("name"),
                                entry.getString("mime"),
                                file.getPath(),
                                "",
                                "",
                                size,
                                Attachment.UPLOADING));
            }
            return new ImportBatch(
                    id,
                    json.getString("scope"),
                    json.getString("text"),
                    json.getLong("at"),
                    files,
                    json.optString("photo", ""));
        } catch (JSONException | IllegalArgumentException failure) {
            throw new IOException("Invalid import manifest", failure);
        }
    }

    public Scan pending() throws IOException {
        return pending(Collections.emptySet());
    }

    public Scan pending(Set<String> receipts) throws IOException {
        List<ImportBatch> batches = new ArrayList<>();
        int unreadable = 0;
        File[] directories = root.listFiles();
        if (directories != null)
            for (File dir : directories) {
                if (!new File(dir, MANIFEST).isFile()) continue;
                try {
                    ImportBatch batch = read(dir.getName(), !receipts.contains(dir.getName()));
                    if (batch != null) {
                        batches.add(batch);
                        lastAt = Math.max(lastAt, batch.at);
                    }
                } catch (IOException | IllegalArgumentException failure) {
                    unreadable++;
                }
            }
        batches.sort(Comparator.comparingLong((ImportBatch b) -> b.at).thenComparing(b -> b.id));
        return new Scan(batches, unreadable);
    }

    /** Call only at startup, before any prepare call can be active. */
    public void pruneIncomplete() throws IOException {
        File[] directories = root.listFiles();
        if (directories == null) return;
        for (File dir : directories) {
            if (!directory(dir.getName()).equals(dir.getCanonicalFile())) continue;
            if (dir.getName().endsWith(".partial")) deleteTree(dir);
            else {
                File[] children = dir.listFiles();
                if (children != null && children.length == 0) dir.delete();
            }
        }
    }

    public void acknowledge(ImportBatch batch) throws IOException {
        acknowledge(batch.id);
    }

    private void acknowledge(String id) throws IOException {
        ImportBatch.requireId(id);
        File dir = directory(id), manifest = new File(dir, MANIFEST);
        if (manifest.exists() && !manifest.delete())
            throw new IOException("Cannot acknowledge import");
        File[] children = dir.listFiles();
        if (children != null && children.length == 0) dir.delete();
    }

    public void discard(ImportBatch batch) throws IOException {
        deleteTree(directory(batch.id));
    }

    private File directory(String name) throws IOException {
        File dir = new File(root, name).getCanonicalFile();
        if (!root.getCanonicalFile().equals(dir.getParentFile()))
            throw new IOException("Import directory escapes inbox");
        return dir;
    }

    private static void ensure(File dir) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs())
            throw new IOException("Cannot create import directory");
    }

    private static void deleteTree(File dir) throws IOException {
        if (!dir.exists()) return;
        File[] children = dir.listFiles();
        if (children != null)
            for (File child : children) {
                if (!child.getCanonicalFile().getParentFile().equals(dir.getCanonicalFile()))
                    throw new IOException("Invalid import child");
                deleteTree(child);
            }
        if (!dir.delete() && dir.exists()) throw new IOException("Cannot remove incomplete import");
    }
}
