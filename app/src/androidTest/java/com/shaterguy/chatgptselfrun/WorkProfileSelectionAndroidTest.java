package com.shaterguy.chatgptselfrun;

import android.view.View;

import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.google.android.material.textfield.MaterialAutoCompleteTextView;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class WorkProfileSelectionAndroidTest {
    @Test public void workDropdownDistinguishesModelAndRestoresExactPair() {
        try (ActivityScenario<SelfRunNewActivity> scenario = ActivityScenario.launch(SelfRunNewActivity.class)) {
            scenario.onActivity(activity -> {
                Ui.SelectionField mode = field(activity, "mode", Ui.SelectionField.class);
                Ui.SelectionField work = field(activity, "workBootstrapProfile", Ui.SelectionField.class);
                mode.setSelection(1);
                invoke(activity, "updateChatReasoningAvailability");
                assertEquals(View.VISIBLE, work.getVisibility());

                List<ProfileRegistry.Profile> profiles = ProfileRegistry.listWork();
                int solMax = position(profiles, "sol", "max");
                int terraMax = position(profiles, "terra", "max");
                MaterialAutoCompleteTextView input =
                        (MaterialAutoCompleteTextView) work.getEditText();
                assertNotNull(input);
                assertNotNull(input.getAdapter());

                assertEquals("Sol / max", input.getAdapter().getItem(solMax));
                work.setSelection(solMax);
                assertEquals("Sol / max", input.getText().toString());
                assertEquals(solMax, work.getSelectedItemPosition());

                assertEquals("Terra / max", input.getAdapter().getItem(terraMax));
                work.setSelection(terraMax);
                assertEquals("Terra / max", input.getText().toString());
                assertEquals(terraMax, work.getSelectedItemPosition());
            });

            scenario.recreate();
            scenario.onActivity(activity -> {
                Ui.SelectionField mode = field(activity, "mode", Ui.SelectionField.class);
                Ui.SelectionField work = field(activity, "workBootstrapProfile", Ui.SelectionField.class);
                MaterialAutoCompleteTextView input =
                        (MaterialAutoCompleteTextView) work.getEditText();
                assertEquals(1, mode.getSelectedItemPosition());
                assertEquals("Terra / max", input.getText().toString());
                ProfileRegistry.Profile selected =
                        ProfileRegistry.listWork().get(work.getSelectedItemPosition());
                assertEquals("terra", selected.signalModel);
                assertEquals("max", selected.signalReasoning);
            });
        }
    }

    private static int position(List<ProfileRegistry.Profile> profiles,
                                String model, String reasoning) {
        for (int i = 0; i < profiles.size(); i++) {
            ProfileRegistry.Profile profile = profiles.get(i);
            if (model.equals(profile.signalModel)
                    && reasoning.equals(profile.signalReasoning)) return i;
        }
        fail("Missing Work profile: " + model + " / " + reasoning);
        return -1;
    }

    private static <T> T field(Object target, String name, Class<T> type) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return type.cast(field.get(target));
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    private static void invoke(Object target, String name) {
        try {
            Method method = target.getClass().getDeclaredMethod(name);
            method.setAccessible(true);
            method.invoke(target);
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }
}
