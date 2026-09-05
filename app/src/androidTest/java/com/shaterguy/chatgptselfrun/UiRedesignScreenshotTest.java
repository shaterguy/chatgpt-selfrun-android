package com.shaterguy.chatgptselfrun;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.*;

/** Real Android Views; fixture data never starts SelfRunService or submits a network prompt. */
@RunWith(AndroidJUnit4.class)
public final class UiRedesignScreenshotTest {
    private final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    private Context context;
    private File evidence;
    private String originalFont;
    private String originalImeWithHardware;
    private final Map<String, Map<String, ?>> saved = new HashMap<>();
    private static final String RUN = "SR-20260905-000000-UI0001";
    private static final String RETAINED_EVIDENCE = "/data/local/tmp/selfrun-ui-evidence";
    @Before public void setUp() throws Exception {
        context = ApplicationProvider.getApplicationContext();
        evidence = new File(context.getExternalFilesDir(null), "ui-evidence");
        assertTrue(evidence.exists() || evidence.mkdirs());
        originalFont = shell("settings get system font_scale").trim();
        originalImeWithHardware = shell("settings get secure show_ime_with_hard_keyboard").trim();
        shell("settings put secure show_ime_with_hard_keyboard 1");
        for (String name : new String[]{"selfrun_drive", "selfrun_drive_history", "selfrun_drive_user_next_input"}) {
            SharedPreferences prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE);
            saved.put(name, new HashMap<>(prefs.getAll()));
            assertTrue(prefs.edit().clear().commit());
        }
        SelfRunApplication.initializeProcess(context);
        record("source_variant=debug; package=" + context.getPackageName() + "; version=" + BuildConfig.VERSION_NAME
                + "; live_service=false; image_type=Android_UiAutomation_screenshot\n");
    }
    @After public void tearDown() throws Exception {
        shell("settings put system font_scale " + (originalFont.matches("[0-9.]+") ? originalFont : "1.0"));
        shell(("null".equals(originalImeWithHardware) ? "settings delete secure show_ime_with_hard_keyboard"
                : "settings put secure show_ime_with_hard_keyboard " + originalImeWithHardware));
        shell("cmd uimode night no");
        shell("wm size reset");
        shell("wm density reset");
        for (Map.Entry<String, Map<String, ?>> entry : saved.entrySet()) {
            SharedPreferences.Editor edit = context.getSharedPreferences(entry.getKey(), Context.MODE_PRIVATE).edit().clear();
            for (Map.Entry<String, ?> value : entry.getValue().entrySet()) {
                Object v = value.getValue(); String key = value.getKey();
                if (v instanceof String) edit.putString(key, (String) v);
                else if (v instanceof Boolean) edit.putBoolean(key, (Boolean) v);
                else if (v instanceof Integer) edit.putInt(key, (Integer) v);
                else if (v instanceof Long) edit.putLong(key, (Long) v);
                else if (v instanceof Float) edit.putFloat(key, (Float) v);
            }
            assertTrue(edit.commit());
        }
    }
    @Test public void screenMatrixAndDraftImeInteractions() throws Exception {
        shell("wm density 160");
        for (int width : new int[]{360, 840}) {
            shell("wm size " + width + "x1000");
            for (boolean dark : new boolean[]{false, true}) {
                shell("cmd uimode night " + (dark ? "yes" : "no"));
                for (String scale : new String[]{"1.0", "2.0"}) {
                    shell("settings put system font_scale " + scale);
                    instrumentation.waitForIdleSync();
                    String prefix = width + "-" + (dark ? "dark" : "light") + "-" + scale;
                    seed("empty");
                    captureActivity(MainActivity.class, prefix + "-main-empty");
                    seed("running");
                    try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
                        scenario.onActivity(activity -> {
                            android.content.res.Configuration actual = activity.getResources().getConfiguration();
                            assertEquals("Window width", width, Ui.windowWidthDp(activity));
                            assertEquals("Font scale", Float.parseFloat(scale), actual.fontScale, 0.01f);
                            assertEquals("Night mode", dark ? android.content.res.Configuration.UI_MODE_NIGHT_YES
                                    : android.content.res.Configuration.UI_MODE_NIGHT_NO,
                                    actual.uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK);
                            assertNotNull(findText(activity.getWindow().getDecorView(), "추가 지시"));
                            assertNotNull(findText(activity.getWindow().getDecorView(), "즉시 보내기"));
                        });
                        capture(prefix + "-main-running");
                        scenario.onActivity(activity -> {
                            try {
                                Field flight = MainActivity.class.getDeclaredField("immediateInputInFlight");
                                flight.setAccessible(true); flight.setBoolean(activity, true);
                                invoke(activity, "refreshCurrent");
                            } catch (Exception error) { throw new AssertionError(error); }
                        });
                        capture(prefix + "-main-submitting");
                        scenario.onActivity(activity -> {
                            try {
                                Field flight = MainActivity.class.getDeclaredField("immediateInputInFlight");
                                flight.setAccessible(true); flight.setBoolean(activity, false);
                            } catch (Exception error) { throw new AssertionError(error); }
                        });
                        for (String state : new String[]{"paused", "reserved", "locked", "done", "error"}) {
                            seed(state);
                            scenario.onActivity(activity -> invoke(activity, "refreshCurrent"));
                            capture(prefix + "-main-" + state);
                        }
                        seed("running");
                        scenario.onActivity(activity -> {
                            invoke(activity, "refreshCurrent");
                            field(activity, "nextInputEditor", EditText.class).setText("담당자를 추가해 주세요.");
                            invoke(activity, "saveNextInput");
                            assertEquals("담당자를 추가해 주세요.", UserNextInputStore.current(RUN));
                            invoke(activity, "deleteNextInput");
                            assertEquals("", UserNextInputStore.current(RUN));
                        });
                    }
                    seed("running");
                    try (ActivityScenario<SelfRunNewActivity> scenario = ActivityScenario.launch(SelfRunNewActivity.class)) {
                        scenario.onActivity(activity -> {
                            EditText editor = field(activity, "requirement", EditText.class);
                            editor.setText("회의 자료를 정리하고 다음 주 실행 계획을 만들어 주세요.\n원본 줄바꿈과 공백 유지  ");
                            Ui.SelectionField mode = field(activity, "mode", Ui.SelectionField.class);
                            mode.setSelection(1);
                            invoke(activity, "updateChatReasoningAvailability");
                            assertNull(findText(activity.getWindow().getDecorView(), "하이브리드"));
                        });
                        capture(prefix + "-new-work");
                        scenario.recreate();
                        scenario.onActivity(activity -> {
                            EditText editor = field(activity, "requirement", EditText.class);
                            assertTrue(editor.getText().toString().endsWith("공백 유지  "));
                            assertEquals(1, field(activity, "mode", Ui.SelectionField.class).getSelectedItemPosition());
                            field(activity, "mode", Ui.SelectionField.class).setSelection(0);
                            invoke(activity, "updateChatReasoningAvailability");
                            editor.requestFocus();
                            editor.setSelection(editor.length());
                            activity.getSystemService(InputMethodManager.class).showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT);
                        });
                        awaitImeVisible(scenario);
                        capture(prefix + "-new-chat-ime");
                    }
                    captureActivity(SelfRunHistoryActivity.class, prefix + "-history");
                    captureActivity(SelfRunLogMenuActivity.class, prefix + "-settings");
                    captureActivity(DriveSetupActivity.class, prefix + "-drive");
                    if (width == 360 && !dark && "1.0".equals(scale)) {
                        captureActivity(ProfileRegistryActivity.class, prefix + "-profiles");
                        try (ActivityScenario<SelfRunDetailActivity> detail = ActivityScenario.launch(
                                new Intent(context, SelfRunDetailActivity.class).putExtra(SelfRunDetailActivity.EXTRA_RUN_ID, RUN))) {
                            capture(prefix + "-detail");
                        }
                        try (ActivityScenario<SelfRunLogsActivity> logs = ActivityScenario.launch(
                                new Intent(context, SelfRunLogsActivity.class).putExtra(SelfRunLogsActivity.EXTRA_RUN_ID, RUN)
                                        .putExtra(SelfRunLogsActivity.EXTRA_KIND, SelfRunLogsActivity.KIND_DEBUG))) {
                            capture(prefix + "-logs");
                        }
                        captureActivity(SelfRunRestartActivity.class, prefix + "-restart-error");
                        captureOfflineWeb(LoginActivity.class, prefix + "-browser");
                        captureOfflineWeb(WebUiCalibrationActivity.class, prefix + "-calibration");
                    }
                }
            }
        }
    }
    private void awaitImeVisible(ActivityScenario<SelfRunNewActivity> scenario) {
        java.util.concurrent.atomic.AtomicBoolean visible = new java.util.concurrent.atomic.AtomicBoolean();
        long deadline = android.os.SystemClock.uptimeMillis() + 5000L;
        while (!visible.get() && android.os.SystemClock.uptimeMillis() < deadline) {
            scenario.onActivity(activity -> {
                android.view.WindowInsets insets = activity.getWindow().getDecorView().getRootWindowInsets();
                visible.set(insets != null && insets.isVisible(android.view.WindowInsets.Type.ime()));
            });
            if (!visible.get()) android.os.SystemClock.sleep(50L);
        }
        assertTrue("IME must be visible in the IME screenshot", visible.get());
    }
    private void captureOfflineWeb(Class<? extends Activity> type, String name) throws Exception {
        try (ActivityScenario<?> scenario = ActivityScenario.launch(new Intent(context, type))) {
            scenario.onActivity(activity -> {
                android.webkit.WebView web = field(activity, "webView", android.webkit.WebView.class);
                web.stopLoading();
                web.loadData("<html><body style='font-family:sans-serif;padding:24px'>ChatGPT</body></html>", "text/html", "UTF-8");
            });
            capture(name);
        }
    }
    private void seed(String state) {
        SharedPreferences prefs = context.getSharedPreferences("selfrun_drive", Context.MODE_PRIVATE);
        if ("empty".equals(state)) { assertTrue(prefs.edit().clear().commit()); return; }
        boolean done = "done".equals(state);
        boolean paused = "paused".equals(state) || "error".equals(state);
        assertTrue(prefs.edit().putString("runId", RUN).putString("mode", "WORK")
                .putString("requirement", "다음 주 제품 출시 계획 정리")
                .putString("projectUrl", SelfRunScript.GENERAL_CHAT_URL)
                .putString("conversationUrl", "https://chatgpt.com/c/ui-fixture")
                .putString("phase", done ? SelfRunStore.PHASE_DONE : SelfRunStore.PHASE_WAIT_TURN_COMPLETION)
                .putString("pausedFromPhase", SelfRunStore.PHASE_WAIT_TURN_COMPLETION)
                .putString("turnProtocolToken", RUN + ":turn:4").putInt("turn", 4)
                .putInt("driveSignalCursorSchemaVersion", 2)
                .putString("pendingModel", "astra").putString("pendingReasoning", "xhigh")
                .putString("lastErrorCode", "error".equals(state) ? "DRIVE_CONNECTION" : "")
                .putString("lastErrorMessage", "error".equals(state) ? "Drive 연결을 확인하세요." : "")
                .putBoolean("active", !done).putBoolean("paused", paused).putBoolean("userStopped", false)
                .putLong("createdAt", 1788560400000L).putLong("updatedAt", 1788560700000L).commit());
        SharedPreferences.Editor input = context.getSharedPreferences("selfrun_drive_user_next_input", Context.MODE_PRIVATE).edit().clear();
        if ("reserved".equals(state) || "locked".equals(state))
            input.putString("runId", RUN).putString("text", "일정에 담당자와 마감일도 포함해 주세요.").putLong("revision", 1L);
        if ("locked".equals(state)) input.putString("lockedContinuation", RUN + ":continue:4").putLong("lockedRevision", 1L);
        assertTrue(input.commit());
        new SelfRunHistoryStore(context).sync(new SelfRunStore(context));
    }
    private void captureActivity(Class<? extends Activity> type, String name) throws Exception {
        try (ActivityScenario<?> scenario = ActivityScenario.launch(new Intent(context, type))) {
            scenario.onActivity(activity -> validateLayout(activity.getWindow().getDecorView()));
            capture(name);
        }
    }
    private void capture(String name) throws Exception {
        instrumentation.waitForIdleSync();
        instrumentation.getUiAutomation().waitForIdle(100, 5000);
        Bitmap bitmap = instrumentation.getUiAutomation().takeScreenshot();
        assertNotNull("Screenshot unavailable: " + name, bitmap);
        File file = new File(evidence, name + ".png");
        try (FileOutputStream out = new FileOutputStream(file)) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out));
        }
        exportEvidence(file);
        android.content.res.Configuration actual = context.getResources().getConfiguration();
        record(name + ".png " + bitmap.getWidth() + "x" + bitmap.getHeight()
                + "; widthDp=" + actual.screenWidthDp + "; fontScale=" + actual.fontScale
                + "; night=" + (actual.uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK) + "\n");
        bitmap.recycle();
    }
    private void validateLayout(View view) {
        if (view.getVisibility() != View.VISIBLE) return;
        if (view instanceof TextView && !(view instanceof EditText)) {
            TextView text = (TextView) view;
            if (text.getLayout() != null && text.getMaxLines() > 2)
                assertTrue("Text clipped: " + text.getText(), text.getHeight() + 1 >=
                        text.getLayout().getHeight() + text.getCompoundPaddingTop() + text.getCompoundPaddingBottom());
        }
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
            validateLayout(((ViewGroup) view).getChildAt(i));
    }
    private static TextView findText(View view, String label) {
        if (view instanceof TextView && label.contentEquals(((TextView) view).getText())) return (TextView) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
            TextView found = findText(((ViewGroup) view).getChildAt(i), label);
            if (found != null) return found;
        }
        return null;
    }
    private static <T> T field(Object target, String name, Class<T> type) {
        try { Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return type.cast(field.get(target)); }
        catch (Exception error) { throw new AssertionError(error); }
    }
    private static void invoke(Object target, String name) {
        try { java.lang.reflect.Method method = target.getClass().getDeclaredMethod(name); method.setAccessible(true); method.invoke(target); }
        catch (Exception error) { throw new AssertionError(error); }
    }
    private String shell(String command) throws Exception {
        try (ParcelFileDescriptor descriptor = instrumentation.getUiAutomation().executeShellCommand(command);
             FileInputStream input = new FileInputStream(descriptor.getFileDescriptor())) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    private void record(String line) throws Exception {
        File manifest = new File(evidence, "manifest.txt");
        try (FileOutputStream out = new FileOutputStream(manifest, true)) {
            out.write(line.getBytes(StandardCharsets.UTF_8));
        }
        exportEvidence(manifest);
    }
    private void exportEvidence(File file) throws Exception {
        // UiAutomation tokenizes one command; it does not interpret shell operators or quotes.
        String source = file.getAbsolutePath();
        String destination = new File(RETAINED_EVIDENCE, file.getName()).getAbsolutePath();
        assertTrue("Unexpected evidence source path", source.matches("/[A-Za-z0-9_./-]+"));
        assertTrue("Unexpected evidence destination path", destination.matches("/[A-Za-z0-9_./-]+"));
        shell("mkdir -p " + RETAINED_EVIDENCE);
        shell("cp -f " + source + " " + destination);
        String sourceChecksum = shell("sha256sum " + source).trim();
        String destinationChecksum = shell("sha256sum " + destination).trim();
        assertTrue("Invalid source checksum: " + sourceChecksum,
                sourceChecksum.matches("[0-9a-fA-F]{64}[ \\t]+" + java.util.regex.Pattern.quote(source)));
        assertTrue("Invalid retained checksum: " + destinationChecksum,
                destinationChecksum.matches("[0-9a-fA-F]{64}[ \\t]+" + java.util.regex.Pattern.quote(destination)));
        assertEquals("Retained evidence differs: " + file.getName(),
                sourceChecksum.substring(0, 64), destinationChecksum.substring(0, 64));
    }
}
