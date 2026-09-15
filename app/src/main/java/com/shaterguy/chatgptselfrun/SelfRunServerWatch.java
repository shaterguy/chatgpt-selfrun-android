package com.shaterguy.chatgptselfrun;

import android.content.Context;

/** Coordinates gateway routing registration and device-owned Drive files.watch registration. */
final class SelfRunServerWatch {
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

    SelfRunServerWatch(Context context) {
        this(context, new SelfRunPushGatewayClient(), new SelfRunDriveWatchClient());
    }

    SelfRunServerWatch(Context context, SelfRunPushGatewayClient gateway, SelfRunDriveWatchClient driveWatch) {
        app = context.getApplicationContext();
        this.gateway = gateway;
        this.driveWatch = driveWatch;
    }

    Result register(String accessToken, SelfRun3Engine.State state, String fcmToken) {
        if (state == null || state.resource("resultDocumentId").isEmpty()) return Result.fallback("result document unavailable");
        try {
            String installationId = SelfRunInstallationIdentity.id(app);
            SelfRunPushGatewayClient.WatchRegistration watch = gateway.registerWatch(
                    installationId,
                    BuildConfig.APPLICATION_ID,
                    state.taskId(),
                    state.turnId(),
                    state.resource("resultDocumentId"),
                    fcmToken);
            driveWatch.watchFile(accessToken, state.resource("resultDocumentId"), watch.channelId,
                    watch.webhookUrl, watch.watchKey, watch.expirationMs);
            return Result.ok();
        } catch (Throwable error) {
            String type = error.getClass().getSimpleName();
            return Result.fallback(type.isEmpty() ? "server watch registration failed" : type);
        }
    }
}
