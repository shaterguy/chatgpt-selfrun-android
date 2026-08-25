package com.shaterguy.chatgptselfrun;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Verifies durable Chat reasoning recovery after process-local static state is lost. */
@RunWith(AndroidJUnit4.class)
public final class ChatReasoningProcessRecreationAndroidTest {
    @Test public void processRecreationReloadsDurableRunSelection() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        String runId = "SR-PROCESS-RECREATION-ANDROID";
        clearPersistentState(context);
        try {
            resetProcessCache();
            assertTrue(ChatReasoningPreferenceStore.save(
                    context, runId, ChatReasoningPreferenceStore.PRO_EXTENDED));

            resetProcessCache();
            assertEquals(ChatReasoningPreferenceStore.PRO_EXTENDED,
                    ChatReasoningPreferenceStore.selectionForRun(context, runId));

            resetProcessCache();
            SelfRunApplication.initializeProcess(context);
            assertEquals(ChatReasoningPreferenceStore.PRO_EXTENDED,
                    ChatReasoningPreferenceStore.selectionForRun(runId));
        } finally {
            clearPersistentState(context);
            resetProcessCache();
            SelfRunApplication.initializeProcess(context);
        }
    }

    private static void resetProcessCache() throws Exception {
        clearStatic("preferences");
        clearStatic("appContext");
    }

    private static void clearStatic(String name) throws Exception {
        Field field = ChatReasoningPreferenceStore.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(null, null);
    }

    private static void clearPersistentState(Context context) {
        context.getSharedPreferences("selfrun_drive_chat_reasoning", Context.MODE_PRIVATE)
                .edit().clear().commit();
        context.getSharedPreferences("selfrun_drive_bootstrap_runs", Context.MODE_PRIVATE)
                .edit().clear().commit();
    }
}
