package com.shaterguy.chatgptselfrun;

import org.junit.Test;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Real disk/outbox tests; only the external Drive boundary is substituted. */
public final class SelfRunDebugLogArchiveTest {
    private final File dir = temporaryDirectory();
    private final SelfRunDebugLogArchive archive = new SelfRunDebugLogArchive(dir);
    private final SelfRunDebugLogArchive.Binding binding =
            new SelfRunDebugLogArchive.Binding("SR-test", "12345", "base123", "folder123");

    @Test public void definiteCreateRejectionCanRetryAtNextTurn() throws Exception {
        archive.request(binding, "WAIT", "turn1");
        FakeDrive remote = new FakeDrive() {
            boolean reject = true;
            public String create(SelfRunDebugLogArchive.Binding b) throws Exception {
                if (reject) { reject = false; throw new SelfRunDebugLogArchive.CreateRejected(new IOException("403")); }
                return super.create(b);
            }
        };
        try { archive.upload("SR-test", remote); fail(); } catch (SelfRunDebugLogArchive.CreateRejected expected) { }
        archive.append("SR-test", "after failure"); archive.request(binding, "WAIT", "turn2");
        archive.upload("SR-test", remote);
        assertEquals(1, remote.creates);
        assertTrue(remote.text.endsWith("after failure\n"));
    }

    @Test public void lostWriteResponseIsReplacedWithWholeNextSnapshot() throws Exception {
        archive.append("SR-test", "first"); archive.request(binding, "WAIT", "turn1");
        FakeDrive remote = new FakeDrive() {
            boolean lose = true;
            public void write(SelfRunDebugLogArchive.Binding b, String id, long sequence, String value) throws Exception {
                super.write(b, id, sequence, value);
                if (lose) { lose = false; throw new IOException("readback lost"); }
            }
        };
        try { archive.upload("SR-test", remote); fail(); } catch (IOException expected) { }
        assertEquals(0, archive.state("SR-test").uploadedSequence);
        archive.append("SR-test", "last"); archive.request(binding, "DONE", "turn1");
        archive.upload("SR-test", remote);
        assertTrue(remote.text.endsWith("first\nlast\n"));
        assertEquals(1, remote.creates);
        assertEquals(2, archive.state("SR-test").uploadedSequence);
    }

    @Test public void wholeArchiveSurvivesRotationSizeAndRecreation() throws Exception {
        archive.append("SR-test", "first");
        String row = "x".repeat(240);
        for (int i = 0; i < 5000; i++) archive.append("SR-test", i + row);
        archive.append("SR-test", "last");
        archive.request(binding, "WAIT", "turn1");
        FakeDrive remote = new FakeDrive();
        new SelfRunDebugLogArchive(dir).upload("SR-test", remote);
        assertTrue(remote.text.contains("\nfirst\n"));
        assertTrue(remote.text.endsWith("last\n"));
        assertTrue(remote.text.length() > 1024 * 1024);
        assertEquals(1, remote.creates);
    }

    @Test public void oversizedAttemptStaysPendingWithoutTruncationOrPartialRemoteWrite() throws Exception {
        archive.append("SR-test", "first");
        File journal = new File(dir, "task-SR-test.jsonl");
        try (java.io.RandomAccessFile file = new java.io.RandomAccessFile(journal, "rw")) {
            file.setLength(17L * 1024 * 1024);
        }
        archive.request(binding, "DONE", "turn1");
        FakeDrive remote = new FakeDrive();
        try { archive.upload("SR-test", remote); fail(); } catch (IOException expected) { }
        assertEquals(17L * 1024 * 1024, journal.length());
        assertEquals(0, archive.state("SR-test").uploadedSequence);
        assertEquals("", remote.text);
    }

    @Test public void lostCreateResponseIsRecoveredWithoutSecondDocument() throws Exception {
        archive.append("SR-test", "first");
        archive.request(binding, "WAIT", "turn1");
        FakeDrive remote = new FakeDrive(); remote.loseCreate = true;
        try { archive.upload("SR-test", remote); fail(); } catch (IOException expected) { }
        archive.append("SR-test", "second");
        archive.request(binding, "WAIT", "turn2");
        new SelfRunDebugLogArchive(dir).upload("SR-test", remote);
        assertEquals(1, remote.creates);
        assertTrue(remote.text.endsWith("first\nsecond\n"));
        assertEquals("document123", archive.state("SR-test").documentId);
    }

    @Test public void unknownCreateWithEmptyLookupNeverCreatesAgain() throws Exception {
        archive.append("SR-test", "event"); archive.request(binding, "WAIT", "turn1");
        FakeDrive remote = new FakeDrive(); remote.loseCreate = true;
        try { archive.upload("SR-test", remote); fail(); } catch (IOException expected) { }
        remote.hidden = true;
        archive.request(binding, "DONE", "turn1");
        try { archive.upload("SR-test", remote); fail(); } catch (IOException expected) { }
        assertEquals(1, remote.creates);
        assertEquals(0, archive.state("SR-test").uploadedSequence);
    }

    @Test public void failedWriteKeepsEverythingForNextTrigger() throws Exception {
        FakeDrive remote = new FakeDrive(); remote.failWrite = true;
        archive.append("SR-test", "before"); archive.request(binding, "WAIT", "turn1");
        try { archive.upload("SR-test", remote); fail(); } catch (IOException expected) { }
        archive.append("SR-test", "during failure"); archive.request(binding, "PAUSE", "turn1");
        remote.failWrite = false; archive.upload("SR-test", remote);
        assertTrue(remote.text.endsWith("before\nduring failure\n"));
        assertEquals(2, archive.state("SR-test").uploadedSequence);
        assertEquals(1, remote.creates);
    }

    @Test public void waitingDeduplicatesButFinalTriggersAdvance() throws Exception {
        assertTrue(archive.request(binding, "WAIT", "turn1"));
        assertFalse(archive.request(binding, "WAIT", "turn1"));
        assertTrue(archive.request(binding, "WAIT", "repair1"));
        assertTrue(archive.request(binding, "USER_INTERVENTION", "repair1"));
        assertTrue(archive.request(binding, "STOP", "repair1"));
        assertEquals(4, archive.state("SR-test").requestedSequence);
    }

    @Test public void changedAccountOrFolderCannotRebindTask() throws Exception {
        archive.request(binding, "WAIT", "turn1");
        for (SelfRunDebugLogArchive.Binding bad : new SelfRunDebugLogArchive.Binding[]{
                new SelfRunDebugLogArchive.Binding("SR-test", "67890", "base123", "folder123"),
                new SelfRunDebugLogArchive.Binding("SR-test", "12345", "base123", "folder456")}) {
            try { archive.request(bad, "DONE", "turn1"); fail(); }
            catch (IOException expected) { }
        }
        assertEquals("folder123", archive.state("SR-test").binding.folderId);
        assertEquals(1, archive.state("SR-test").requestedSequence);
    }

    @Test public void blockedUploadDoesNotLockRequestsAndDrainsFinalSnapshot() throws Exception {
        archive.append("SR-test", "start"); archive.request(binding, "WAIT", "turn1");
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        FakeDrive remote = new FakeDrive() {
            @Override public void write(SelfRunDebugLogArchive.Binding b, String id, long sequence, String text) throws Exception {
                if (sequence == 1) { entered.countDown(); assertTrue(release.await(10, TimeUnit.SECONDS)); }
                super.write(b, id, sequence, text);
            }
        };
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread upload = new Thread(() -> { try { archive.upload("SR-test", remote); } catch (Throwable e) { error.set(e); } });
        upload.start();
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            archive.append("SR-test", "final done");
            assertTrue(archive.request(binding, "DONE", "turn1"));
            assertFalse(new SelfRunDebugLogArchive(dir).upload("SR-test", remote));
            assertEquals(2, archive.state("SR-test").requestedSequence);
        } finally { release.countDown(); upload.join(10000); }
        assertFalse(upload.isAlive()); assertNull(error.get());
        assertTrue(remote.text.endsWith("start\nfinal done\n"));
        assertEquals(2, archive.state("SR-test").uploadedSequence);
        assertEquals(1, remote.creates);
    }

    @Test public void foreignRemoteBoundaryPreventsCreateAndWrite() throws Exception {
        archive.request(binding, "DONE", "turn1");
        FakeDrive remote = new FakeDrive() {
            @Override public void validate(SelfRunDebugLogArchive.Binding b) throws Exception { throw new IOException("account mismatch"); }
        };
        try { archive.upload("SR-test", remote); fail(); } catch (IOException expected) { }
        assertEquals(0, remote.creates); assertEquals("", remote.text);
    }

    private static File temporaryDirectory() {
        try { return Files.createTempDirectory("selfrun-debug-test").toFile(); }
        catch (IOException e) { throw new AssertionError(e); }
    }
    private static class FakeDrive implements SelfRunDebugLogArchive.Remote {
        String id = "", text = ""; int creates; boolean loseCreate, hidden, failWrite;
        public void validate(SelfRunDebugLogArchive.Binding b) throws Exception { assertEquals("12345", b.accountId); }
        public String find(SelfRunDebugLogArchive.Binding b) { return hidden ? "" : id; }
        public String create(SelfRunDebugLogArchive.Binding b) throws Exception {
            creates++; id = "document123";
            if (loseCreate) { loseCreate = false; throw new IOException("response lost"); }
            return id;
        }
        public void write(SelfRunDebugLogArchive.Binding b, String document, long sequence, String value) throws Exception {
            assertEquals("document123", document);
            if (failWrite) throw new IOException("write unavailable");
            text = value;
        }
    }
}
