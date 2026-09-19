package com.shaterguy.chatgptselfrun;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.graphics.Rect;
import android.text.Selection;
import android.text.Spannable;
import android.view.View;
import android.view.MotionEvent;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class SelfRunDebugUiAndroidTest {
    private static final String TASK = "SR-20260919-UI0001";
    private Context context;
    @Before public void setup() {
        context = ApplicationProvider.getApplicationContext();
        context.getSharedPreferences("selfrun_drive", 0).edit().clear()
                .putString("runId", TASK).putString("requirement", "debug UI contract")
                .putString("taskMode", "HYBRID").putString("mode", "WORK")
                .putString("phase", SelfRunStore.PHASE_WAIT_TURN_COMPLETION)
                .putBoolean("paused", true).putInt("turn", 2).commit();
        new SelfRunHistoryStore(context).sync(new SelfRunStore(context));
    }
    @After public void cleanup() {
        context.getSharedPreferences("selfrun_drive", 0).edit().clear().commit();
    }

    @Test public void executionInfoSelectionSurvivesRealRefreshAndCopies() {
        try (ActivityScenario<MainActivity> scenario = ActivityScenario.launch(MainActivity.class)) {
            AtomicReference<CharSequence> original = new AtomicReference<>();
            int[] touch = new int[2];
            scenario.onActivity(activity -> {
                TextView info = field(activity, "technicalDetails");
                find(activity.getWindow().getDecorView(), "실행 정보").performClick();
                assertTrue(info.isTextSelectable());
                assertTrue(info.isLongClickable());
                info.requestRectangleOnScreen(new Rect(0, 0, info.getWidth(), Ui.dp(activity, 40)), true);
            });
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                TextView info = field(activity, "technicalDetails");
                info.getLocationOnScreen(touch);
                int offset = info.getText().toString().indexOf(TASK) + 4;
                touch[0] += info.getTotalPaddingLeft() + (int) info.getLayout().getPrimaryHorizontal(offset);
                touch[1] += info.getTotalPaddingTop() + info.getLineHeight() / 2;
            });
            long down = SystemClock.uptimeMillis();
            inject(down, down, MotionEvent.ACTION_DOWN, touch);
            SystemClock.sleep(850);
            inject(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, touch);
            InstrumentationRegistry.getInstrumentation().waitForIdleSync();
            scenario.onActivity(activity -> {
                TextView info = field(activity, "technicalDetails");
                assertTrue("long press did not create a native selection", info.getSelectionEnd() > info.getSelectionStart());
                int start = info.getText().toString().indexOf(TASK);
                assertTrue(start >= 0);
                Selection.setSelection((Spannable) info.getText(), start, start + TASK.length());
                original.set(info.getText());
            });
            SystemClock.sleep(3200);
            scenario.onActivity(activity -> {
                TextView info = field(activity, "technicalDetails");
                assertSame("refresh replaced the selected buffer", original.get(), info.getText());
                assertTrue("refresh cleared the native selection",
                        info.getSelectionStart() >= 0 && info.getSelectionEnd() > info.getSelectionStart());
                int start = info.getText().toString().indexOf(TASK);
                Selection.setSelection((Spannable) info.getText(), start, start + TASK.length());
                assertTrue(info.onTextContextMenuItem(android.R.id.copy));
                ClipboardManager clipboard = activity.getSystemService(ClipboardManager.class);
                assertEquals(TASK, clipboard.getPrimaryClip().getItemAt(0).coerceToText(activity).toString());
            });
        }
    }

    private static void inject(long down, long now, int action, int[] point) {
        MotionEvent event = MotionEvent.obtain(down, now, action, point[0], point[1], 0);
        try { assertTrue(InstrumentationRegistry.getInstrumentation().getUiAutomation().injectInputEvent(event, true)); }
        finally { event.recycle(); }
    }

    @Test public void everyHistoryKindOpensOnlyItsOwnDocumentAndRejectsInvalidIds() {
        Intent intent = new Intent(context, SelfRunDetailActivity.class)
                .putExtra(SelfRunDetailActivity.EXTRA_RUN_ID, TASK);
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        AtomicReference<Intent> opened = new AtomicReference<>();
        Instrumentation.ActivityMonitor monitor = new Instrumentation.ActivityMonitor() {
            @Override public Instrumentation.ActivityResult onStartActivity(Intent outgoing) {
                if (!Intent.ACTION_VIEW.equals(outgoing.getAction())) return null;
                opened.set(outgoing);
                return new Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null);
            }
        };
        instrumentation.addMonitor(monitor);
        try (ActivityScenario<SelfRunDetailActivity> scenario = ActivityScenario.launch(intent)) {
            // The loader is asynchronous; use an isolated attached row for deterministic link clicks.
            scenario.onActivity(activity -> {
                LinearLayout rows = new LinearLayout(activity);
                rows.setOrientation(LinearLayout.VERTICAL);
                setField(activity, "conversations", rows);
                setField(activity, "historyGeneration", 100000);
                activity.setContentView(rows);
                String[] kinds = {"NORMAL", "REPAIR", "PARALLEL_BRANCH", "PARALLEL_MERGE", "USER_INTERVENTION"};
                for (int i = 0; i < kinds.length; i++) {
                    rows.removeAllViews();
                    String id = "document_turn_" + i;
                    render(activity, kinds[i], id);
                    Button chat = (Button) find(rows, "대화 열기");
                    Button doc = (Button) find(rows, "작업문서");
                    assertSame(chat.getParent(), doc.getParent());
                    LinearLayout strip = (LinearLayout) doc.getParent();
                    strip.measure(View.MeasureSpec.makeMeasureSpec(Ui.dp(activity, 320), View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
                    assertTrue(doc.getMeasuredWidth() <= Ui.dp(activity, 320));
                    assertTrue(chat.getMinimumHeight() >= Ui.dp(activity, 48));
                    doc.performClick();
                    assertEquals("https://docs.google.com/document/d/" + id + "/edit", opened.get().getDataString());
                    chat.performClick();
                    assertEquals("https://chatgpt.com/c/conversation_turn", opened.get().getDataString());
                }
                for (String id : new String[]{"", "../foreign", "id?redirect=evil", "https://evil.test"}) {
                    rows.removeAllViews();
                    render(activity, "NORMAL", id);
                    assertFalse(find(rows, "작업문서").isEnabled());
                }
            });
        } finally { instrumentation.removeMonitor(monitor); }
    }

    private static void render(SelfRunDetailActivity activity, String kind, String id) {
        try {
            JSONObject entry = new JSONObject().put("executionKind", kind).put("turn", 2)
                    .put("conversationUrl", "https://chatgpt.com/c/conversation_turn")
                    .put("resultDocumentId", id);
            Method method = SelfRunDetailActivity.class.getDeclaredMethod("renderConversation", JSONObject.class);
            method.setAccessible(true); method.invoke(activity, entry);
        } catch (Exception e) { throw new AssertionError(e); }
    }
    @SuppressWarnings("unchecked") private static <T> T field(Object object, String name) {
        try { Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); return (T) field.get(object); }
        catch (Exception e) { throw new AssertionError(e); }
    }
    private static void setField(Object object, String name, Object value) {
        try { Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); field.set(object, value); }
        catch (Exception e) { throw new AssertionError(e); }
    }
    private static View find(View root, String text) {
        if (root instanceof TextView && text.contentEquals(((TextView) root).getText())) return root;
        if (root instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) root).getChildCount(); i++) {
            View found = find(((ViewGroup) root).getChildAt(i), text);
            if (found != null) return found;
        }
        return null;
    }
}
