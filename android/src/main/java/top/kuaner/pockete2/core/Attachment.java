// Copyright 2026 OpenDuo
// SPDX-License-Identifier: FSL-1.1-Apache-2.0

package top.kuaner.pockete2.core;

import org.json.JSONException;
import org.json.JSONObject;

/** An attachment before or after channel upload. */
public final class Attachment {
    public final String name;
    public final String mime;
    public final String localPath;
    public final String sha256;
    public final String remotePath;
    public final long size;
    public final int state;

    public static final int UPLOADING = 0, UPLOADED = 1, FAILED = 2;

    public Attachment(
            String name,
            String mime,
            String localPath,
            String sha256,
            String remotePath,
            long size,
            int state) {
        this.name = name == null ? "附件" : name;
        this.mime = mime == null ? "application/octet-stream" : mime;
        this.localPath = localPath == null ? "" : localPath;
        this.sha256 = sha256 == null ? "" : sha256;
        this.remotePath = remotePath == null ? "" : remotePath;
        this.size = Math.max(0, size);
        this.state = state;
    }

    public Attachment withUpload(String sha, String path) {
        return new Attachment(name, mime, localPath, sha, path, size, UPLOADED);
    }

    public Attachment failed() {
        return new Attachment(name, mime, localPath, sha256, remotePath, size, FAILED);
    }

    public boolean inlineImage() {
        return "image/png".equals(mime)
                || "image/jpeg".equals(mime)
                || "image/gif".equals(mime)
                || "image/webp".equals(mime);
    }

    public JSONObject json(boolean includeLocal) throws JSONException {
        JSONObject o = new JSONObject().put("name", name).put("mime", mime);
        if (!sha256.isEmpty()) o.put("sha256", sha256);
        if (!remotePath.isEmpty()) o.put("path", remotePath);
        if (includeLocal && !localPath.isEmpty()) o.put("localPath", localPath);
        o.put("size", size).put("state", state);
        return o;
    }

    public static Attachment from(JSONObject o) {
        if (o == null) return null;
        return new Attachment(
                o.optString("name", "附件"),
                o.optString("mime", "application/octet-stream"),
                o.optString("localPath", ""),
                o.optString("sha256", ""),
                o.optString("path", ""),
                o.optLong("size", 0),
                o.optInt("state", UPLOADED));
    }
}
