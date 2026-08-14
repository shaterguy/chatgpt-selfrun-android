package com.shaterguy.chatgptselfrun;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.os.SystemClock;
import android.text.Layout;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ScrollView;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

@RunWith(AndroidJUnit4.class)
public final class SelfRunNewActivityImeTest {
    @Test
    public void longCommandCaretRemainsVisibleAboveImeAcrossCursorMovement() {
        try (ActivityScenario<SelfRunNewActivity> scenario = ActivityScenario.launch(SelfRunNewActivity.class)) {
            scenario.onActivity(activity -> {
                activity.getWindow().setSoftInputMode(
                        WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                                | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
                EditText editor = requirement(activity);
                editor.setText(longCommand());
                editor.requestFocus();
                editor.setSelection(editor.length());
                editor.bringPointIntoView(editor.length());
            });
            waitForWindowFocus(scenario);
            requestIme(scenario);
            waitForIme(scenario);
            assertCaretVisible(scenario, 2, true);
            assertCaretVisible(scenario, 1, false);
            assertCaretVisible(scenario, 0, false);
        }
    }

    private static void waitForWindowFocus(ActivityScenario<SelfRunNewActivity> scenario) {
        for (int attempt = 0; attempt < 80; attempt++) {
            AtomicBoolean focused = new AtomicBoolean(false);
            scenario.onActivity(activity -> focused.set(
                    activity.getWindow().getDecorView().hasWindowFocus()
                            && requirement(activity).hasFocus()));
            if (focused.get()) return;
            SystemClock.sleep(100L);
        }
        throw new AssertionError("editor did not gain window focus on the emulator");
    }

    private static void requestIme(ActivityScenario<SelfRunNewActivity> scenario) {
        scenario.onActivity(activity -> {
            EditText editor = requirement(activity);
            if (editor.getWindowInsetsController() != null) {
                editor.getWindowInsetsController().show(WindowInsets.Type.ime());
            }
            InputMethodManager imm = (InputMethodManager) activity.getSystemService(SelfRunNewActivity.INPUT_METHOD_SERVICE);
            if (imm != null) imm.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT);
        });
    }

    private static void waitForIme(ActivityScenario<SelfRunNewActivity> scenario) {
        for (int attempt = 0; attempt < 100; attempt++) {
            AtomicBoolean visible = new AtomicBoolean(false);
            scenario.onActivity(activity -> {
                View decor = activity.getWindow().getDecorView();
                WindowInsets insets = decor.getRootWindowInsets();
                visible.set(insets != null && insets.isVisible(WindowInsets.Type.ime())
                        && insets.getInsets(WindowInsets.Type.ime()).bottom > 0);
            });
            if (visible.get()) return;
            if (attempt == 20 || attempt == 50) requestIme(scenario);
            SystemClock.sleep(100L);
        }
        throw new AssertionError("IME did not become visible on the emulator");
    }

    private static void assertCaretVisible(ActivityScenario<SelfRunNewActivity> scenario, int position, boolean requireInternalScroll) {
        scenario.onActivity(activity -> {
            EditText editor = requirement(activity);
            int offset = position == 0 ? 0 : (position == 1 ? editor.length() / 2 : editor.length());
            editor.setSelection(offset);
            editor.bringPointIntoView(offset);
            outerScroll(editor).scrollTo(0, 0);
            invokeKeepCommandCursorVisible(activity);
        });
        SystemClock.sleep(500L);
        scenario.onActivity(activity -> {
            EditText editor = requirement(activity);
            Layout layout = editor.getLayout();
            assertNotNull(layout);
            View decor = activity.getWindow().getDecorView();
            WindowInsets insets = decor.getRootWindowInsets();
            assertNotNull(insets);
            int imeBottom = insets.getInsets(WindowInsets.Type.ime()).bottom;
            assertTrue("IME must remain visible", imeBottom > 0);
            int line = layout.getLineForOffset(editor.getSelectionStart());
            int[] editorLocation = new int[2];
            int[] decorLocation = new int[2];
            editor.getLocationOnScreen(editorLocation);
            decor.getLocationOnScreen(decorLocation);
            int caretBottom = editorLocation[1] + editor.getTotalPaddingTop() + layout.getLineBottom(line) - editor.getScrollY();
            int imeTop = decorLocation[1] + decor.getHeight() - imeBottom;
            if (requireInternalScroll) assertTrue("long input must exercise internal scrolling", editor.getScrollY() > 0);
            assertTrue("caret must remain above IME: " + caretBottom + " > " + imeTop, caretBottom <= imeTop);
        });
    }

    private static ScrollView outerScroll(EditText editor) {
        View parent = (View) editor.getParent();
        if (!(parent.getParent() instanceof ScrollView)) throw new AssertionError("expected outer ScrollView");
        return (ScrollView) parent.getParent();
    }

    private static EditText requirement(SelfRunNewActivity activity) {
        try {
            Field field = SelfRunNewActivity.class.getDeclaredField("requirement");
            field.setAccessible(true);
            return (EditText) field.get(activity);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static void invokeKeepCommandCursorVisible(SelfRunNewActivity activity) {
        try {
            Method method = SelfRunNewActivity.class.getDeclaredMethod("keepCommandCursorVisible");
            method.setAccessible(true);
            method.invoke(activity);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static String longCommand() {
        StringBuilder text = new StringBuilder();
        for (int line = 0; line < 80; line++) {
            if (line > 0) text.append('\n');
            text.append("selfrun long command line ").append(line).append(" with enough text to exercise editing");
        }
        return text.toString();
    }
}
