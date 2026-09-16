package com.shaterguy.chatgptselfrun;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class SelfRunPushAckOutboxAndroidTest {
    private Context context;
    private SelfRunPushEvent event;

    @Before public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        assertTrue(context.getSharedPreferences(SelfRunPushAckOutbox.PREFS, Context.MODE_PRIVATE).edit().clear().commit());
        event = SelfRunPushEvent.parse(valid(), BuildConfig.APPLICATION_ID);
    }

    @After public void tearDown() {
        assertTrue(context.getSharedPreferences(SelfRunPushAckOutbox.PREFS, Context.MODE_PRIVATE).edit().clear().commit());
    }

    @Test public void duplicateAckIsIdempotentAndProcessedIsIndependentStage() {
        String received = SelfRunPushAckOutbox.enqueue(context, event, SelfRunPushAckOutbox.AckState.RECEIVED);
        assertEquals(received, SelfRunPushAckOutbox.enqueue(context, event, SelfRunPushAckOutbox.AckState.RECEIVED));
        assertEquals(1, SelfRunPushAckOutbox.pendingCount(context));
        String processed = SelfRunPushAckOutbox.enqueue(context, event, SelfRunPushAckOutbox.AckState.PROCESSED);
        assertNotEquals(received, processed);
        assertEquals(2, SelfRunPushAckOutbox.pendingCount(context));
        SelfRunPushAckOutbox.markDelivered(context, received);
        assertEquals(1, SelfRunPushAckOutbox.pendingCount(context));
        SelfRunPushAckOutbox.markDelivered(context, processed);
        assertEquals(0, SelfRunPushAckOutbox.pendingCount(context));
    }

    @Test public void receivedAckAlwaysFlushesBeforeProcessedForTheSameEvent() throws Exception {
        SelfRunPushAckOutbox.enqueue(context, event, SelfRunPushAckOutbox.AckState.RECEIVED);
        SelfRunPushAckOutbox.enqueue(context, event, SelfRunPushAckOutbox.AckState.PROCESSED);

        List<SelfRunPushAckOutbox.Entry> pending = SelfRunPushAckOutbox.pending(context);
        assertEquals(2, pending.size());
        assertEquals("RECEIVED", pending.get(0).body.getString("state"));
        assertEquals("PROCESSED", pending.get(1).body.getString("state"));
    }

    private static Map<String, String> valid() {
        Map<String, String> data = new HashMap<>();
        data.put("schema", "selfrun-push-v1");
        data.put("type", "RESULT_CHANGED");
        data.put("eventId", "EV-1234567890123456");
        data.put("installationId", "INS-1234567890123456");
        data.put("applicationId", BuildConfig.APPLICATION_ID);
        data.put("taskId", "SR-20260915-ABCDEF");
        data.put("turnId", "SR-20260915-ABCDEF:turn:4");
        data.put("resultDocumentId", "1AbcDefGhijkLMNopQRstuVwxyz012345");
        return data;
    }
}
