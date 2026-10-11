package com.shaterguy.chatgptselfrun;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.UUID;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class SelfRunConversationUrlAndroidTest {
    @Test public void projectionRetainsOriginalAddressAcrossStoreRecreation() {
        String namespace = "url-fixture-" + UUID.randomUUID() + "-";
        Context isolated = new ContextWrapper(ApplicationProvider.getApplicationContext()) {
            @Override public Context getApplicationContext() { return this; }
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                return super.getSharedPreferences(namespace + name, mode);
            }
        };
        String raw = "https://chatgpt.com/g/g-p-6a582c824ba08191ac7e74e9bad721fc-vibe-coding/c/6acb731a-b770-83ec-81e8-f5f4ac3fb1fc";
        SelfRunStore store = new SelfRunStore(isolated);
        store.setExecutionProjection("CHAT", "fixture-model", "xhigh", raw);
        assertEquals(raw, store.conversationUrl());
        SelfRunStore restored = new SelfRunStore(isolated);
        assertEquals(raw, restored.conversationUrl());
        assertEquals(raw, SelfRunDetailActivity.canonicalConversationUrl(restored.conversationUrl()));
    }

    @Test public void namedProjectOpenLinkPreservesOriginalAddress() {
        String raw = "https://chatgpt.com/g/g-p-6a582c824ba08191ac7e74e9bad721fc-vibe-coding/c/6acb731a-b770-83ec-81e8-f5f4ac3fb1fc";
        assertEquals(raw, SelfRunDetailActivity.canonicalConversationUrl(raw));
    }

    @Test public void generalConversationOpenLinkRemainsUsable() {
        String raw = "https://chatgpt.com/c/owned-1";
        assertEquals(raw, SelfRunDetailActivity.canonicalConversationUrl(raw));
    }

    @Test public void unsafeOrNonConversationOpenLinkIsDisabled() {
        for (String raw : new String[]{"https://evil.example/c/owned-1", "http://chatgpt.com/c/owned-1", "https://user@chatgpt.com/c/owned-1", "https://chatgpt.com/g/g-p-any/project"})
            assertEquals("", SelfRunDetailActivity.canonicalConversationUrl(raw));
    }
}
