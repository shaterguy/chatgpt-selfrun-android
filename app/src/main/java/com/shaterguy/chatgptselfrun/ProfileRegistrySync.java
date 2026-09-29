package com.shaterguy.chatgptselfrun;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.google.android.gms.auth.api.identity.AuthorizationResult;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Refreshes the two canonical Drive profile registries and preserves validated last-known-good snapshots. */
final class ProfileRegistrySync {
    static final String CHAT_DOCUMENT_ID = "1CblWAg3XWuVYbGIuUt0TLKGuIyhRX3yW2KCmvAmZ75Q";
    static final String WORK_DOCUMENT_ID = "1TB5_H84_2ypC5XFAdEaopySyBpkKBHLOcPB5s3naDOo";

    interface Callback { void onComplete(Result result); }

    static final class Result {
        final boolean chatUsable;
        final boolean workUsable;
        final boolean chatUpdated;
        final boolean workUpdated;
        final String error;

        Result(boolean chatUsable, boolean workUsable, boolean chatUpdated,
               boolean workUpdated, String error) {
            this.chatUsable = chatUsable;
            this.workUsable = workUsable;
            this.chatUpdated = chatUpdated;
            this.workUpdated = workUpdated;
            this.error = error == null ? "" : error;
        }

        boolean anyUsable() { return chatUsable || workUsable; }
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "selfrun-profile-registry-sync");
        thread.setDaemon(true);
        return thread;
    });
    private static final Object LOCK = new Object();
    private static final List<Callback> WAITERS = new ArrayList<>();
    private static boolean inFlight;

    private ProfileRegistrySync() {}

    static void refresh(Context context, Callback callback) {
        if (context == null) {
            if (callback != null) callback.onComplete(current("context unavailable"));
            return;
        }
        Context app = context.getApplicationContext();
        if (app == null) app = context;
        ProfileRegistry.initialize(app);
        synchronized (LOCK) {
            if (callback != null) WAITERS.add(callback);
            if (inFlight) return;
            inFlight = true;
        }
        Context application = app;
        DriveAuthorization.requestSilently(application, new DriveAuthorization.Callback() {
            @Override public void onAuthorized(AuthorizationResult result) {
                String token = DriveAuthorization.accessToken(result);
                if (token.isEmpty()) {
                    finish(current("Drive authorization token unavailable"));
                    return;
                }
                EXECUTOR.execute(() -> finish(refreshWithToken(token)));
            }

            @Override public void onResolutionRequired(android.app.PendingIntent pendingIntent) {
                finish(current("Drive authorization requires user resolution"));
            }

            @Override public void onFailure(Throwable error) {
                finish(current(error == null ? "Drive authorization failed"
                        : String.valueOf(error.getMessage())));
            }
        });
    }

    static Result refreshWithToken(String token) {
        if (token == null || token.isEmpty()) return current("Drive authorization token unavailable");
        DriveApiClient api = new DriveApiClient();
        boolean chatUpdated = false;
        boolean workUpdated = false;
        StringBuilder errors = new StringBuilder();
        try { chatUpdated = refreshOne(api, token, ProfileRegistry.Mode.CHAT, CHAT_DOCUMENT_ID); }
        catch (Exception error) { appendError(errors, "CHAT", error); }
        try { workUpdated = refreshOne(api, token, ProfileRegistry.Mode.WORK, WORK_DOCUMENT_ID); }
        catch (Exception error) { appendError(errors, "WORK", error); }
        return new Result(
                ProfileRegistry.hasUsableMode(ProfileRegistry.Mode.CHAT),
                ProfileRegistry.hasUsableMode(ProfileRegistry.Mode.WORK),
                chatUpdated, workUpdated, errors.toString());
    }

    private static boolean refreshOne(DriveApiClient api, String token, ProfileRegistry.Mode mode,
                                      String documentId) throws Exception {
        DriveApiClient.Metadata metadata = api.getMetadata(token, documentId);
        if (!DriveApiClient.MIME_DOCUMENT.equals(metadata.mimeType) || metadata.trashed) {
            throw new IllegalStateException("canonical registry document invalid");
        }
        String sourceVersion = metadata.version + "|" + metadata.modifiedTime;
        if (!sourceVersion.isEmpty()
                && sourceVersion.equals(ProfileRegistry.sourceVersion(mode))
                && ProfileRegistry.hasUsableMode(mode)) {
            return false;
        }
        String raw = api.readDocumentText(token, documentId);
        ProfileRegistry.replaceCanonicalSnapshot(mode, raw == null ? "" : raw.trim(), sourceVersion);
        return true;
    }

    private static void appendError(StringBuilder out, String mode, Exception error) {
        if (out.length() > 0) out.append("; ");
        out.append(mode).append(":")
                .append(error == null || error.getMessage() == null
                        ? "refresh failed" : error.getMessage());
    }

    private static Result current(String error) {
        return new Result(
                ProfileRegistry.hasUsableMode(ProfileRegistry.Mode.CHAT),
                ProfileRegistry.hasUsableMode(ProfileRegistry.Mode.WORK),
                false, false, error);
    }

    private static void finish(Result result) {
        MAIN.post(() -> {
            List<Callback> callbacks;
            synchronized (LOCK) {
                callbacks = new ArrayList<>(WAITERS);
                WAITERS.clear();
                inFlight = false;
            }
            for (Callback callback : callbacks) {
                try { callback.onComplete(result); } catch (RuntimeException ignored) {}
            }
        });
    }
}
