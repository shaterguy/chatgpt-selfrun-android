package com.shaterguy.chatgptselfrun;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public final class SelfRunFirebaseConfiguredAndroidTest {
    @Test public void candidateContainsFirebasePublicBuildConfig() {
        assertTrue("TEST candidate must be built with Firebase public configuration",
                SelfRunFirebase.configured());
    }
}
