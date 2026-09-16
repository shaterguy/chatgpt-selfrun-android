package com.shaterguy.chatgptselfrun;

import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;

public final class SelfRunServerWatchPolicyTest {
    @Test public void gatewayRegistrationBodyContainsRoutingAndFcmButNeverDriveCredentials() throws Exception {
        JSONObject body = SelfRunPushGatewayClient.registrationBody(
                "INS-0123456789abcdef0123456789abcdef",
                "com.shaterguy.chatgptselfrun.drive.test",
                "SR-20260915-ABCDEF",
                "SR-20260915-ABCDEF:turn:4",
                "1AbcDefGhijkLMNopQRstuVwxyz012345",
                "fcm-token-value-with-enough-length-1234567890");
        assertEquals("selfrun-watch-register-v1", body.getString("schema"));
        assertEquals("SR-20260915-ABCDEF:turn:4", body.getString("turnId"));
        assertTrue(body.has("fcmToken"));
        assertFalse(body.has("driveAccessToken"));
        assertFalse(body.has("refreshToken"));
        assertFalse(body.has("resultBody"));
        assertFalse(body.has("requirementBody"));
    }

    @Test public void dedicatedDriveWatchClientRegistersAWebHookChannelAgainstPinnedFile() throws Exception {
        String source = source("SelfRunDriveWatchClient.java");
        assertTrue(source.contains("/watch?supportsAllDrives=true"));
        assertTrue(source.contains("put(\"type\", \"web_hook\")"));
        assertTrue(source.contains("put(\"address\", address)"));
        assertTrue(source.contains("put(\"token\", channelToken)"));
        assertTrue(source.contains("put(\"expiration\", expirationMs)"));
    }

    @Test public void gatewayClientIsHttpsOnlyBoundedAndNeverFollowsRedirects() throws Exception {
        String source = source("SelfRunPushGatewayClient.java");
        assertTrue(source.contains("https"));
        assertTrue(source.contains("setInstanceFollowRedirects(false)"));
        assertTrue(source.contains("MAX_RESPONSE_BYTES"));
        assertFalse(source.contains("driveAccessToken"));
    }

    @Test public void sameLivePerTurnWatchIsReusableButNearExpiryOrDifferentIdentityIsNot() {
        long now = 1_000_000L;
        assertTrue(SelfRunServerWatch.reusableRegistration(
                "same", "same", now + 10L * 60L * 1000L, now));
        assertFalse(SelfRunServerWatch.reusableRegistration(
                "next", "same", now + 10L * 60L * 1000L, now));
        assertFalse(SelfRunServerWatch.reusableRegistration(
                "same", "same", now + 5L * 60L * 1000L, now));
        assertFalse(SelfRunServerWatch.reusableRegistration(
                "same", "same", now - 1L, now));
    }

    @Test public void registrationChecksLiveCacheBeforeCreatingAnotherGatewayAndDriveWatch() throws Exception {
        String source = source("SelfRunServerWatch.java");
        int reuse = source.indexOf("reusableRegistration(registrationKey, activeRegistrationKey, activeExpirationMs, now)");
        int gateway = source.indexOf("gateway.registerWatch(");
        assertTrue(reuse >= 0);
        assertTrue(gateway > reuse);
        assertTrue(source.contains("activeRegistrationKey = registrationKey;"));
        assertTrue(source.contains("activeExpirationMs = watch.expirationMs;"));
    }

    private static String source(String name) throws Exception {
        Path path = Path.of("app/src/main/java/com/shaterguy/chatgptselfrun/" + name);
        if (!Files.exists(path)) path = Path.of("src/main/java/com/shaterguy/chatgptselfrun/" + name);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
