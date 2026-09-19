package com.shaterguy.chatgptselfrun;

import java.io.File;
import java.io.IOException;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import org.json.JSONArray;
import org.json.JSONObject;

/** Task-owned cumulative journal and upload cursor, independent of the execution ledger. */
final class SelfRunDebugLogArchive {
    private static final Object STATE_LOCK = new Object();
    private final File directory;
    static final class Binding {
        final String taskId, accountId, baseFolderId, folderId;
        Binding(String taskId, String accountId, String baseFolderId, String folderId) {
            this.taskId = taskId; this.accountId = accountId;
            this.baseFolderId = baseFolderId; this.folderId = folderId;
        }
    }
    static final class Snapshot {
        Binding binding;
        String documentId = "", trigger = "";
        long uploadedSequence, requestedSequence, requestedBytes;
        boolean createAttempted;
        JSONArray waitingTurns = new JSONArray();
    }
    /** Only a definite rejection permits another create; timeouts and lost responses do not. */
    static final class CreateRejected extends IOException {
        CreateRejected(Throwable cause) { super("debug document create rejected", cause); }
    }
    interface Remote {
        void validate(Binding binding) throws Exception;
        String find(Binding binding) throws Exception;
        String create(Binding binding) throws Exception;
        void write(Binding binding, String documentId, long sequence, String text) throws Exception;
    }
    SelfRunDebugLogArchive(File directory) { this.directory = directory; }

    void append(String taskId, String line) throws IOException {
        synchronized (STATE_LOCK) {
            ensureDirectory();
            try (FileOutputStream out = new FileOutputStream(file(taskId, ".jsonl"), true)) {
                out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    boolean request(Binding binding, String trigger, String turnId) throws IOException {
        synchronized (STATE_LOCK) {
            ensureDirectory();
            Snapshot s = state(binding.taskId);
            if (s == null) { s = new Snapshot(); s.binding = binding; }
            requireBinding(s.binding, binding);
            if ("WAIT".equals(trigger)) {
                for (int i = 0; i < s.waitingTurns.length(); i++)
                    if (turnId.equals(s.waitingTurns.optString(i))) return false;
                s.waitingTurns.put(turnId);
            }
            s.trigger = trigger;
            s.requestedSequence++;
            s.requestedBytes = file(binding.taskId, ".jsonl").length();
            save(s);
            return true;
        }
    }

    Snapshot state(String taskId) throws IOException {
        synchronized (STATE_LOCK) {
            File file = file(taskId, ".json");
            if (!file.exists()) return null;
            try {
                JSONObject j = new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
                Snapshot s = new Snapshot();
                s.binding = new Binding(j.getString("taskId"), j.getString("accountId"),
                        j.getString("baseFolderId"), j.getString("folderId"));
                if (!taskId.equals(s.binding.taskId)) throw new IOException("debug task mismatch");
                s.documentId = j.getString("documentId");
                s.trigger = j.getString("trigger");
                s.requestedSequence = j.getLong("requestedSequence");
                s.uploadedSequence = j.getLong("uploadedSequence");
                s.requestedBytes = j.getLong("requestedBytes");
                s.createAttempted = j.getBoolean("createAttempted");
                s.waitingTurns = j.getJSONArray("waitingTurns");
                return s;
            } catch (Exception invalid) { throw new IOException("debug state unreadable", invalid); }
        }
    }

    /** False means another uploader holds the lease; caller may reschedule this auxiliary work. */
    boolean upload(String taskId, Remote remote) throws Exception {
        ensureDirectory();
        try (RandomAccessFile lease = new RandomAccessFile(file(taskId, ".lock"), "rw")) {
            FileLock lock;
            try { lock = lease.getChannel().tryLock(); }
            catch (OverlappingFileLockException busy) { return false; }
            if (lock == null) return false;
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    Snapshot s = state(taskId);
                    if (s == null || s.uploadedSequence >= s.requestedSequence) return true;
                    remote.validate(s.binding);
                    if (s.documentId.isEmpty()) {
                        String id = remote.find(s.binding);
                        if (id.isEmpty()) {
                            if (s.createAttempted) throw new IOException("debug create outcome unresolved");
                            mutate(taskId, current -> current.createAttempted = true);
                            try { id = remote.create(s.binding); }
                            catch (CreateRejected rejected) {
                                mutate(taskId, current -> current.createAttempted = false);
                                throw rejected;
                            }
                        }
                        if (id == null || !id.matches("[A-Za-z0-9_-]{8,200}"))
                            throw new IOException("debug document identity missing");
                        final String pinned = id;
                        mutate(taskId, current -> {
                            if (!current.documentId.isEmpty() && !pinned.equals(current.documentId))
                                throw new IOException("debug document rebind forbidden");
                            current.documentId = pinned;
                        });
                        s.documentId = pinned;
                    }
                    // The immutable prefix is read outside STATE_LOCK. Requests never wait for network or a full read.
                    String text = "SelfRun debug log\nTask: " + taskId + "\nSequence: "
                            + s.requestedSequence + "\nTrigger: " + s.trigger + "\n\n" + readPrefix(taskId, s.requestedBytes);
                    remote.write(s.binding, s.documentId, s.requestedSequence, text);
                    final long uploaded = s.requestedSequence;
                    mutate(taskId, current -> current.uploadedSequence = Math.max(current.uploadedSequence, uploaded));
                    // Requests arriving during upload remain in the outbox and are drained before releasing the lease.
                }
                throw new IOException("debug upload interrupted");
            } finally { lock.release(); }
        }
    }

    private String readPrefix(String taskId, long length) throws IOException {
        if (length == 0) return "";
        // Skip the whole auxiliary attempt before large allocations; never truncate the archive.
        long headroom = Runtime.getRuntime().maxMemory() - Runtime.getRuntime().totalMemory()
                + Runtime.getRuntime().freeMemory();
        if (length < 0 || length > Math.min(16L * 1024 * 1024, headroom / 16))
            throw new IOException("debug snapshot exceeds auxiliary memory budget");
        byte[] bytes = new byte[(int) length];
        try (RandomAccessFile in = new RandomAccessFile(file(taskId, ".jsonl"), "r")) { in.readFully(bytes); }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private interface Mutation { void apply(Snapshot s) throws IOException; }
    private void mutate(String taskId, Mutation mutation) throws IOException {
        synchronized (STATE_LOCK) {
            Snapshot s = state(taskId);
            if (s == null) throw new IOException("debug binding missing");
            mutation.apply(s); save(s);
        }
    }

    private void save(Snapshot s) throws IOException {
        try {
            JSONObject j = new JSONObject().put("taskId", s.binding.taskId).put("accountId", s.binding.accountId)
                    .put("baseFolderId", s.binding.baseFolderId).put("folderId", s.binding.folderId)
                    .put("documentId", s.documentId).put("trigger", s.trigger)
                    .put("requestedSequence", s.requestedSequence).put("uploadedSequence", s.uploadedSequence)
                    .put("requestedBytes", s.requestedBytes).put("createAttempted", s.createAttempted)
                    .put("waitingTurns", s.waitingTurns);
            File pending = file(s.binding.taskId, ".pending");
            try (FileOutputStream out = new FileOutputStream(pending)) {
                out.write(j.toString().getBytes(StandardCharsets.UTF_8)); out.getFD().sync();
            }
            Files.move(pending.toPath(), file(s.binding.taskId, ".json").toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception invalid) { throw new IOException("debug state write failed", invalid); }
    }

    private void ensureDirectory() throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("debug directory unavailable");
    }
    private File file(String taskId, String suffix) throws IOException {
        if (taskId == null || !taskId.matches("[A-Za-z0-9._-]{1,80}") || "redacted".equals(taskId))
            throw new IOException("invalid debug task identity");
        return new File(directory, "task-" + taskId + suffix);
    }
    private static void requireBinding(Binding actual, Binding expected) throws IOException {
        if (!actual.taskId.equals(expected.taskId) || !actual.accountId.equals(expected.accountId)
                || !actual.baseFolderId.equals(expected.baseFolderId) || !actual.folderId.equals(expected.folderId))
            throw new IOException("debug task binding changed");
    }
}
