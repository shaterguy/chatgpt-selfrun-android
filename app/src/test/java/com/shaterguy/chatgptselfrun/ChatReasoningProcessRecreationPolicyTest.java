package com.shaterguy.chatgptselfrun;

import android.content.Context;
import android.content.SharedPreferences;
import android.test.mock.MockContext;

import org.junit.After;
import org.junit.Test;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ChatReasoningProcessRecreationPolicyTest {
    @After public void clearStaticProcessState() throws Exception {
        resetProcessCache();
    }

    @Test public void processRecreationReloadsDurableRunSelection() throws Exception {
        FakeContext context = new FakeContext();
        String runId = "SR-PROCESS-RECREATION";

        resetProcessCache();
        assertTrue(ChatReasoningPreferenceStore.save(
                context, runId, ChatReasoningPreferenceStore.PRO));

        resetProcessCache();
        assertEquals(ChatReasoningPreferenceStore.PRO,
                ChatReasoningPreferenceStore.selectionForRun(context, runId));

        resetProcessCache();
        SelfRunApplication.initializeProcess(context);
        assertEquals(ChatReasoningPreferenceStore.PRO,
                ChatReasoningPreferenceStore.selectionForRun(runId));
    }

    @Test public void processInitializerAndHistorySummaryAreWired() throws Exception {
        String manifest = read("app/src/main/AndroidManifest.xml", "src/main/AndroidManifest.xml");
        String application = src("SelfRunApplication.java");
        String preferences = src("ChatReasoningPreferenceStore.java");
        String history = src("SelfRunHistoryActivity.java");

        assertTrue(manifest.contains("android:name=\".SelfRunApplication\""));
        assertTrue(application.contains("initializeProcess(this)"));
        assertTrue(application.contains("ChatReasoningPreferenceStore.initialize(context)"));
        assertTrue(preferences.contains("selectionForRun(Context context, String runId)"));
        assertTrue(preferences.contains("BootstrapRunStateStore.requested(application, runId)"));
        assertTrue(history.contains("BootstrapRunStateStore.summary(item)"));
        assertFalse(history.contains("모델 변경 없음"));
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

    private static String src(String file) throws Exception {
        return read("app/src/main/java/com/shaterguy/chatgptselfrun/" + file,
                "src/main/java/com/shaterguy/chatgptselfrun/" + file);
    }

    private static String read(String first, String fallback) throws Exception {
        Path path = Paths.get(first);
        if (!Files.exists(path)) path = Paths.get(fallback);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static final class FakeContext extends MockContext {
        private final Map<String, SharedPreferences> stores = new HashMap<>();

        @Override public Context getApplicationContext() { return this; }

        @Override public SharedPreferences getSharedPreferences(String name, int mode) {
            return stores.computeIfAbsent(name, ignored -> new MemoryPreferences());
        }
    }

    private static final class MemoryPreferences implements SharedPreferences {
        private final Map<String, Object> values = new HashMap<>();

        @Override public Map<String, ?> getAll() { return new HashMap<>(values); }
        @Override public String getString(String key, String defValue) {
            Object value = values.get(key);
            return value instanceof String ? (String) value : defValue;
        }
        @SuppressWarnings("unchecked")
        @Override public Set<String> getStringSet(String key, Set<String> defValues) {
            Object value = values.get(key);
            return value instanceof Set ? new HashSet<>((Set<String>) value) : defValues;
        }
        @Override public int getInt(String key, int defValue) {
            Object value = values.get(key);
            return value instanceof Integer ? (Integer) value : defValue;
        }
        @Override public long getLong(String key, long defValue) {
            Object value = values.get(key);
            return value instanceof Long ? (Long) value : defValue;
        }
        @Override public float getFloat(String key, float defValue) {
            Object value = values.get(key);
            return value instanceof Float ? (Float) value : defValue;
        }
        @Override public boolean getBoolean(String key, boolean defValue) {
            Object value = values.get(key);
            return value instanceof Boolean ? (Boolean) value : defValue;
        }
        @Override public boolean contains(String key) { return values.containsKey(key); }
        @Override public Editor edit() { return new MemoryEditor(); }
        @Override public void registerOnSharedPreferenceChangeListener(
                OnSharedPreferenceChangeListener listener) { }
        @Override public void unregisterOnSharedPreferenceChangeListener(
                OnSharedPreferenceChangeListener listener) { }

        private final class MemoryEditor implements Editor {
            private final Map<String, Object> pending = new HashMap<>();
            private final Set<String> removals = new HashSet<>();
            private boolean clear;

            @Override public Editor putString(String key, String value) {
                pending.put(key, value); removals.remove(key); return this;
            }
            @Override public Editor putStringSet(String key, Set<String> value) {
                pending.put(key, value == null ? null : new HashSet<>(value));
                removals.remove(key); return this;
            }
            @Override public Editor putInt(String key, int value) {
                pending.put(key, value); removals.remove(key); return this;
            }
            @Override public Editor putLong(String key, long value) {
                pending.put(key, value); removals.remove(key); return this;
            }
            @Override public Editor putFloat(String key, float value) {
                pending.put(key, value); removals.remove(key); return this;
            }
            @Override public Editor putBoolean(String key, boolean value) {
                pending.put(key, value); removals.remove(key); return this;
            }
            @Override public Editor remove(String key) {
                removals.add(key); pending.remove(key); return this;
            }
            @Override public Editor clear() {
                clear = true; pending.clear(); removals.clear(); return this;
            }
            @Override public boolean commit() { applyChanges(); return true; }
            @Override public void apply() { applyChanges(); }

            private void applyChanges() {
                if (clear) values.clear();
                for (String key : removals) values.remove(key);
                for (Map.Entry<String, Object> entry : pending.entrySet()) {
                    if (entry.getValue() == null) values.remove(entry.getKey());
                    else values.put(entry.getKey(), entry.getValue());
                }
            }
        }
    }
}
