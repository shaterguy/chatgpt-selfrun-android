package com.shaterguy.chatgptselfrun;

import android.content.Context;

/** Coordinates gateway routing registration and device-owned Drive files.watch registration. */
final class SelfRunServerWatch {
    private static final long RENEW_EARLY_MS = 5L * 60L * 1000L;

    static final class Result {
        final boolean registered;
        final String reason;
        Result(boolean registered, String reason) {
            this.registered = registered;
            this.reason = reason == null ? "" : reason;
        }
        static Result ok() { return new Result(true, ""); }
        static Result fallback(String reason) { return new Result(false, reason); }
    }

    private final Context app;
    private final SelfRunPushGatewayClient gateway;
    private final SelfRunDriveWatchClient driveWatch;
    private String activeRegistrationKey = "";
    private long activeExpirationMs;

    SelfRunServerWatch(Context context) {
        this(context, new SelfRunPushGatewayClient(BuildConfig.SELFRUN_PUSH_GATEWAY_URL),
                new SelfRunDriveWatchClient());
    }

    SelfRunServerWatch(Context context, SelfRunPushGatewayClient gateway, SelfRunDriveWatchClient driveWatch) {
        app = context.getApplicationContext();
        this.gateway = gateway;
        this.driveWatch = driveWatch;
    }

    Result register(String accessToken, SelfRun3Engine.State state, String fcmToken) {
        if (state == null || state.resource("resultDocumentId").isEmpty()) {
            return Result.fallback("result document unavailable");
        }
        if (fcmToken == null || fcmToken.isEmpty()) return Result.fallback("fcm token unavailable");
        try {
            String installationId = SelfRunInstallationIdentity.id(app);
            String resultDocumentId = state.resource("resultDocumentId");
            String registrationKey = registrationKey(
                    installationId,
                    BuildConfig.APPLICATION_ID,
                    state.taskId(),
                    state.turnId(),
                    resultDocumentId,
                    fcmToken);
            long now = System.currentTimeMillis();
            if (reusableRegistration(registrationKey, activeRegistrationKey, activeExpirationMs, now)) {
                return Result.ok();
            }
            SelfRunPushGatewayClient.WatchRegistration watch = gateway.registerWatch(
                    installationId,
                    BuildConfig.APPLICATION_ID,
                    state.taskId(),
                    state.turnId(),
                    resultDocumentId,
                    fcmToken);
            driveWatch.watchFile(accessToken, resultDocumentId, watch.channelId,
                    watch.webhookUrl, watch.watchKey, watch.expirationMs);
            activeRegistrationKey = registrationKey;
            activeExpirationMs = watch.expirationMs;
            return Result.ok();
        } catch (Throwable error) {
            String type = error.getClass().getSimpleName();
            return Result.fallback(type.isEmpty() ? "server watch registration failed" : type);
        }
    }

    static boolean reusableRegistration(String requestedKey, String activeKey,
                                        long expirationMs, long nowMs) {
        return requestedKey != null && !requestedKey.isEmpty()
                && requestedKey.equals(activeKey)
                && expirationMs - nowMs > RENEW_EARLY_MS;
    }

    private static String registrationKey(String... parts) {
        StringBuilder key = new StringBuilder();
        for (String part : parts) {
            String value = part == null ? "" : part;
            key.append(value.length()).append(':').append(value);
        }
        return key.toString();
    }
}
