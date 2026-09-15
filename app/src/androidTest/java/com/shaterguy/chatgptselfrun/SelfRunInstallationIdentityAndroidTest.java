package com.shaterguy.chatgptselfrun;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class SelfRunInstallationIdentityAndroidTest {
    private Context context;

    @Before public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        assertTrue(context.getSharedPreferences(SelfRunInstallationIdentity.PREFS, Context.MODE_PRIVATE)
                .edit().clear().commit());
    }

    @After public void tearDown() {
        assertTrue(context.getSharedPreferences(SelfRunInstallationIdentity.PREFS, Context.MODE_PRIVATE)
                .edit().clear().commit());
    }

    @Test public void generatedIdentityIsStableWithinOneInstallation() {
        String first = SelfRunInstallationIdentity.id(context);
        String second = SelfRunInstallationIdentity.id(context);
        assertEquals(first, second);
        assertTrue(first.length() >= 32);
        assertFalse(first.contains(Build.MODEL));
        assertFalse(first.contains(BuildConfig.APPLICATION_ID));
    }
}
