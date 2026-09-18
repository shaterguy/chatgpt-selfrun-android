package com.shaterguy.chatgptselfrun;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.SystemClock;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.google.firebase.FirebaseApp;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Production-path smoke for the signed formal package. The FCM token never leaves the test process
 * except in the HTTPS registration body consumed by the production command bridge.
 */
@RunWith(AndroidJUnit4.class)
public final class SelfRunFormalFcmSmokeAndroidTest {
    private static final String FORMAL_PACKAGE = "com.shaterguy.chatgptselfrun.drive";
    private static final String FORMAL_FIREBASE_APP_ID = "1:859485943787:android:feab87243808eb6ca30bae";

    @Test public void formalPackageGetsFcmAndCompletesTwoStageAck() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertEquals(FORMAL_PACKAGE, context.getPackageName());
        assertTrue("Firebase public config must be present", SelfRunFirebase.configured());
        assertTrue("formal Firebase initialization failed", SelfRunFirebase.initialize(context));
        assertEquals(FORMAL_FIREBASE_APP_ID, FirebaseApp.getInstance().getOptions().getApplicationId());

        String token = awaitToken(context);
        assertNotNull(token);
        assertTrue(token.length() >= 16);

        String smokeId = normalizedSmokeId(
                InstrumentationRegistry.getArguments().getString("smokeId", "formal-smoke-local"));
        String installationId = SelfRunInstallationIdentity.id(context);
        String applicationId = context.getPackageName();
        String taskId = "FCM-SMOKE-" + smokeId;
        String turnId = taskId + ":turn:1";
        String resultDocumentId = "FCM-RESULT-" + smokeId;

        SharedPreferences prefs = context.getSharedPreferences(SelfRunPushAckOutbox.PREFS, Context.MODE_PRIVATE);
        CountDownLatch received = new CountDownLatch(1);
        CountDownLatch processed = new CountDownLatch(1);
        AtomicReference<String> eventId = new AtomicReference<>();
        AtomicReference<Throwable> listenerFailure = new AtomicReference<>();

        SharedPreferences.OnSharedPreferenceChangeListener listener = (shared, key) -> {
            if (key == null || !key.startsWith("ack:")) return;
            String raw = shared.getString(key, "");
            if (raw == null || raw.isEmpty()) return;
            try {
                JSONObject body = new JSONObject(raw);
                if (!taskId.equals(body.optString("taskId", ""))) return;
                requireExact("installationId", installationId, body.optString("installationId", ""));
                requireExact("applicationId", applicationId, body.optString("applicationId", ""));
                requireExact("turnId", turnId, body.optString("turnId", ""));
                requireExact("resultDocumentId", resultDocumentId, body.optString("resultDocumentId", ""));
                String observedEvent = body.optString("eventId", "");
                if (observedEvent.length() < 16) throw new IllegalStateException("eventId missing");
                String prior = eventId.get();
                if (prior == null) eventId.compareAndSet(null, observedEvent);
                else requireExact("eventId", prior, observedEvent);
                String state = body.optString("state", "");
                if ("RECEIVED".equals(state)) received.countDown();
                if ("PROCESSED".equals(state)) processed.countDown();
            } catch (Throwable failure) {
                listenerFailure.compareAndSet(null, failure);
            }
        };

        prefs.registerOnSharedPreferenceChangeListener(listener);
        try {
            SelfRunPushGatewayClient.WatchRegistration watch = new SelfRunPushGatewayClient().registerWatch(
                    installationId, applicationId, taskId, turnId, resultDocumentId, token);
            triggerDriveWebhook(watch, smokeId);

            assertTrue("formal package did not persist RECEIVED ACK", received.await(90, TimeUnit.SECONDS));
            throwListenerFailure(listenerFailure);
            assertTrue("formal package did not persist PROCESSED ACK", processed.await(90, TimeUnit.SECONDS));
            throwListenerFailure(listenerFailure);

            String correlatedEvent = eventId.get();
            assertNotNull("FCM event identity missing", correlatedEvent);
            assertFalse(correlatedEvent.isEmpty());
            assertTrue("two-stage ACKs were not accepted and drained by production gateway",
                    waitForAckDrain(context, correlatedEvent, 90_000L));
            throwListenerFailure(listenerFailure);

            Bundle evidence = new Bundle();
            evidence.putString("selfrunFormalFcmEvent", fingerprint(correlatedEvent));
            evidence.putString("selfrunFormalFirebaseApp", "formal");
            InstrumentationRegistry.getInstrumentation().sendStatus(0, evidence);
        } finally {
            prefs.unregisterOnSharedPreferenceChangeListener(listener);
        }
    }

    private static String awaitToken(Context context) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> token = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        SelfRunFirebase.requestToken(context, new SelfRunFirebase.TokenCallback() {
            @Override public void onToken(String value) {
                token.set(value);
                done.countDown();
            }
            @Override public void onUnavailable(Throwable error) {
                failure.set(error);
                done.countDown();
            }
        });
        assertTrue("FCM token request timed out", done.await(60, TimeUnit.SECONDS));
        if (failure.get() != null) throw new AssertionError("FCM token request failed", failure.get());
        return token.get();
    }

    private static void triggerDriveWebhook(SelfRunPushGatewayClient.WatchRegistration watch, String smokeId)
            throws Exception {
        HttpURLConnection connection = (HttpURLConnection) URI.create(watch.webhookUrl).toURL().openConnection();
        try {
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(20_000);
            connection.setUseCaches(false);
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(0);
            connection.setRequestProperty("x-goog-channel-id", watch.channelId);
            connection.setRequestProperty("x-goog-channel-token", watch.watchKey);
            connection.setRequestProperty("x-goog-resource-state", "update");
            connection.setRequestProperty("x-goog-changed", "content");
            connection.setRequestProperty("x-goog-message-number", "2");
            connection.setRequestProperty("x-goog-resource-id", "formal-fcm-smoke-" + smokeId);
            int status = connection.getResponseCode();
            assertEquals("production bridge webhook rejected smoke event", 204, status);
        } finally {
            connection.disconnect();
        }
    }

    private static boolean waitForAckDrain(Context context, String eventId, long timeoutMs) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        while (SystemClock.elapsedRealtime() < deadline) {
            boolean pending = false;
            for (SelfRunPushAckOutbox.Entry entry : SelfRunPushAckOutbox.pending(context)) {
                if (eventId.equals(entry.body.optString("eventId", ""))) {
                    pending = true;
                    break;
                }
            }
            if (!pending) return true;
            SystemClock.sleep(250L);
        }
        return false;
    }

    private static String normalizedSmokeId(String raw) {
        String value = raw == null ? "" : raw.trim().replaceAll("[^A-Za-z0-9-]", "-");
        if (value.length() < 8) value = "formal-smoke-" + value;
        if (value.length() > 48) value = value.substring(0, 48);
        return value;
    }

    private static void requireExact(String field, String expected, String actual) {
        if (!expected.equals(actual)) {
            throw new IllegalStateException(field + " mismatch");
        }
    }

    private static void throwListenerFailure(AtomicReference<Throwable> failure) {
        Throwable value = failure.get();
        if (value != null) throw new AssertionError("push ACK observation failed", value);
    }

    private static String fingerprint(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder(16);
        for (int i = 0; i < 8; i++) result.append(String.format(Locale.US, "%02x", digest[i] & 0xff));
        return result.toString();
    }
}
