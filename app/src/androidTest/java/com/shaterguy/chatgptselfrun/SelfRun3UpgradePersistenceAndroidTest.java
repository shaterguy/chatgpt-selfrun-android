package com.shaterguy.chatgptselfrun;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Process;
import android.webkit.CookieManager;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

/** Runs the same test APK before and after target upgrade, using only platform data APIs. */
@RunWith(AndroidJUnit4.class)
public final class SelfRun3UpgradePersistenceAndroidTest {
    private static final String MARKER = "selfrun_upgrade_fixture";
    private static final String PREFS = "selfrun_drive_chat_reasoning";
    private static final String KEY = "continuationSelection";
    private static final String URL = "https://selfrun-upgrade.invalid/";
    private static final String COOKIE = "selfrun_upgrade_fixture=retained";
    private Context context() { return ApplicationProvider.getApplicationContext(); }

    @Test public void seedUpgradeState() throws Exception {
        Context c = context();
        SharedPreferences prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        assertTrue(c.getSharedPreferences(MARKER, Context.MODE_PRIVATE).edit()
                .putInt("uid", Process.myUid()).putBoolean("hadKey", prefs.contains(KEY))
                .putString("original", prefs.getString(KEY, "")).commit());
        assertTrue(prefs.edit().putString(KEY, "high").commit());
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Boolean> accepted = new AtomicReference<>(false);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            CookieManager manager = CookieManager.getInstance();
            manager.setCookie(URL, COOKIE + "; Path=/; Max-Age=86400; Secure; SameSite=Lax",
                    value -> { accepted.set(value); done.countDown(); });
        });
        assertTrue("cookie write callback", done.await(10, TimeUnit.SECONDS));
        assertEquals(Boolean.TRUE, accepted.get());
        CookieManager.getInstance().flush();
    }

    @Test public void verifyUpgradeState() throws Exception {
        Context c = context();
        SharedPreferences marker = c.getSharedPreferences(MARKER, Context.MODE_PRIVATE);
        SharedPreferences prefs = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        assertEquals("app UID preserved", marker.getInt("uid", -1), Process.myUid());
        assertEquals("actual Chat continuation preference preserved", "high", prefs.getString(KEY, ""));
        AtomicReference<String> cookie = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(
                () -> cookie.set(CookieManager.getInstance().getCookie(URL)));
        assertNotNull("fixture cookie preserved", cookie.get());
        assertTrue(cookie.get().contains(COOKIE));
        SharedPreferences.Editor restore = prefs.edit();
        if (marker.getBoolean("hadKey", false)) restore.putString(KEY, marker.getString("original", ""));
        else restore.remove(KEY);
        assertTrue(restore.commit());
        CountDownLatch done = new CountDownLatch(1);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(
                () -> CookieManager.getInstance().setCookie(URL,
                        "selfrun_upgrade_fixture=; Path=/; Max-Age=0; Secure", value -> done.countDown()));
        assertTrue(done.await(10, TimeUnit.SECONDS));
        CookieManager.getInstance().flush();
        assertTrue(marker.edit().clear().commit());
    }
}
