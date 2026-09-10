package com.shaterguy.chatgptselfrun;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class SelfRun3UnboundedLimitsAndroidTest {
    private Context context;
    private SharedPreferences runPrefs;
    private SharedPreferences inputPrefs;

    @Before public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        runPrefs = context.getSharedPreferences("selfrun_drive", Context.MODE_PRIVATE);
        inputPrefs = context.getSharedPreferences("selfrun_drive_user_next_input", Context.MODE_PRIVATE);
        assertTrue(runPrefs.edit().clear().commit());
        assertTrue(inputPrefs.edit().clear().commit());
    }

    @After public void tearDown() {
        assertTrue(inputPrefs.edit().clear().commit());
        assertTrue(runPrefs.edit().clear().commit());
    }

    @Test public void attachmentDraftStateAcceptsMoreThanTenAndKnownFileOver100MiB() {
        ArrayList<SelfRunStore.Attachment> drafts = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            long size = i == 11 ? 101L * 1024L * 1024L : 1024L + i;
            drafts.add(SelfRunStore.Attachment.draft(i,
                    "content://selfrun.fixture/attachment/" + i,
                    "fixture-" + i + ".bin", DriveApiClient.MIME_OCTET_STREAM, size));
        }
        String encoded = SelfRunStore.encodeAttachmentDrafts(drafts);
        List<SelfRunStore.Attachment> decoded = SelfRunStore.decodeAttachmentDrafts(encoded);
        assertEquals(12, decoded.size());
        assertEquals(101L * 1024L * 1024L, decoded.get(11).size);
    }

    @Test public void additionalInputPersistsBeyondFormer64KiBGateWithRevisionUpdates() {
        String runId = "android-large-input";
        assertTrue(runPrefs.edit()
                .putString("runId", runId)
                .putBoolean("active", true)
                .putBoolean("userStopped", false)
                .putString("phase", SelfRun3Coordinator.PHASE_WAITING)
                .commit());
        UserNextInputStore.initialize(context);

        String first = "추가지시".repeat(30_000);
        assertTrue(first.getBytes(StandardCharsets.UTF_8).length > 64 * 1024);
        assertTrue(UserNextInputStore.save(runId, first));
        assertEquals(first, UserNextInputStore.current(runId));
        assertEquals(1L, inputPrefs.getLong("revision", 0L));

        String second = first + "\n후속";
        assertTrue(UserNextInputStore.save(runId, second));
        assertEquals(second, UserNextInputStore.current(runId));
        assertEquals(2L, inputPrefs.getLong("revision", 0L));
    }
}
