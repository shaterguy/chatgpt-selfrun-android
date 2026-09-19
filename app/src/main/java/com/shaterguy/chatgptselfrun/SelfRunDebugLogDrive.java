package com.shaterguy.chatgptselfrun;

import java.io.IOException;
import java.util.function.BooleanSupplier;

/** Bound auxiliary Drive transport, with no execution-state or interactive-auth dependency. */
final class SelfRunDebugLogDrive implements SelfRunDebugLogArchive.Remote {
    private final String token;
    private final BooleanSupplier stopped;
    private final DriveApiClient api = new DriveApiClient();
    SelfRunDebugLogDrive(String token, BooleanSupplier stopped) { this.token = token; this.stopped = stopped; }

    public void validate(SelfRunDebugLogArchive.Binding b) throws Exception {
        checkpoint();
        if (!b.accountId.equals(api.getAccountPermissionId(token))) throw new IOException("debug account mismatch");
        checkpoint();
        DriveApiClient.Metadata folder = api.getMetadata(token, b.folderId);
        if (!b.folderId.equals(folder.id) || !b.baseFolderId.equals(folder.parentId)
                || !DriveApiClient.MIME_FOLDER.equals(folder.mimeType) || folder.trashed || folder.shared
                || !folder.driveId.isEmpty()
                || !folder.isAppAuthorized || !folder.canAddChildren
                || !b.taskId.equals(folder.appProperties.optString("job_id")))
            throw new IOException("debug folder boundary mismatch");
    }
    public String find(SelfRunDebugLogArchive.Binding b) throws Exception {
        checkpoint();
        String id = api.findDebugLogDocument(token, b.taskId, b.folderId);
        if (!id.isEmpty()) requireDocument(api.getMetadata(token, id), b, id);
        return id;
    }
    public String create(SelfRunDebugLogArchive.Binding b) throws Exception {
        checkpoint();
        try {
            DriveApiClient.Metadata created = api.createDebugLogDocument(token, b.taskId, b.folderId);
            requireDocument(created, b, created.id);
            return created.id;
        } catch (DriveApiClient.ApiException rejected) {
            if (rejected.status >= 400 && rejected.status < 500 && rejected.status != 408 && rejected.status != 429)
                throw new SelfRunDebugLogArchive.CreateRejected(rejected);
            throw rejected;
        }
    }
    public void write(SelfRunDebugLogArchive.Binding b, String id, long sequence, String text) throws Exception {
        checkpoint();
        requireDocument(api.getMetadata(token, id), b, id);
        DriveApiClient.DebugLogSnapshot current = api.readDebugLogSnapshot(token, id);
        requireExistingBody(current.text, b.taskId, sequence);
        if (!current.text.equals(text + "\n")) {
            checkpoint();
            api.replaceDebugLogDocument(token, id, current, text);
        }
        checkpoint();
        if (!api.readDebugLogSnapshot(token, id).text.equals(text + "\n"))
            throw new IOException("debug document readback mismatch");
    }
    static void requireDocument(DriveApiClient.Metadata m, SelfRunDebugLogArchive.Binding b, String id) throws IOException {
        if (!DriveApiClient.validFileId(id) || !id.equals(m.id) || !b.folderId.equals(m.parentId)
                || !(b.taskId + "-debug-log").equals(m.name) || !DriveApiClient.MIME_DOCUMENT.equals(m.mimeType)
                || m.trashed || m.shared || !m.driveId.isEmpty() || !m.isAppAuthorized
                || !b.taskId.equals(m.appProperties.optString("job_id"))
                || !"debug_log".equals(m.appProperties.optString("selfrun_kind")))
            throw new IOException("debug document boundary mismatch");
    }
    static void requireExistingBody(String text, String task, long sequence) throws IOException {
        if ("\n".equals(text) || text.isEmpty()) return;
        String prefix = "SelfRun debug log\nTask: " + task + "\nSequence: ";
        if (!text.startsWith(prefix)) throw new IOException("foreign debug document body");
        int end = text.indexOf('\n', prefix.length());
        try {
            long existing = Long.parseLong(text.substring(prefix.length(), end));
            if (existing < 1 || existing > sequence) throw new IOException("newer debug snapshot already exists");
        } catch (RuntimeException invalid) { throw new IOException("invalid debug snapshot sequence", invalid); }
    }
    private void checkpoint() throws IOException {
        if (stopped.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new IOException("debug worker stopped");
    }
}
