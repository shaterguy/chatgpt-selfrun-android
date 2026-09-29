package com.shaterguy.chatgptselfrun;

import android.app.PendingIntent;
import android.content.Context;

import com.google.android.gms.auth.api.identity.AuthorizationResult;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Refreshes canonical profile registries while preserving the last-known-good cache on any failure. */
final class ProfileRegistryRefresher {
    static final String CHAT_DOCUMENT_ID = "1CblWAg3XWuVYbGIuUt0TLKGuIyhRX3yW2KCmvAmZ75Q";
    static final String WORK_DOCUMENT_ID = "1TB5_H84_2ypC5XFAdEaopySyBpkKBHLOcPB5s3naDOo";

    interface Completion {
        void onComplete(boolean changed, Throwable error);
    }

    private static final Object LOCK = new Object();
    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static final List<Completion> WAITERS = new ArrayList<>();
    private static boolean inFlight;

    private ProfileRegistryRefresher() {}

    static void refresh(Context context, Completion completion) {
        if (context == null) {
            if (completion != null) completion.onComplete(false, new IllegalArgumentException("context required"));
            return;
        }
        Context app = context.getApplicationContext() == null ? context : context.getApplicationContext();
        ProfileRegistry.initialize(app);
        synchronized (LOCK) {
            if (completion != null) WAITERS.add(completion);
            if (inFlight) return;
            inFlight = true;
        }
        DriveAuthorization.requestSilently(app, new DriveAuthorization.Callback() {
            @Override public void onAuthorized(AuthorizationResult result) {
                String token = DriveAuthorization.accessToken(result);
                if (token.isEmpty()) {
                    finish(false, new IllegalStateException("DRIVE_TOKEN_EMPTY"));
                    return;
                }
                IO.execute(() -> {
                    RefreshResult resultValue = refreshWithTokenBlocking(app, token);
                    finish(resultValue.changed, resultValue.error);
                });
            }

            @Override public void onResolutionRequired(PendingIntent pendingIntent) {
                finish(false, new IllegalStateException("DRIVE_AUTH_REQUIRED"));
            }

            @Override public void onFailure(Throwable error) {
                finish(false, error);
            }
        });
    }

    static RefreshResult refreshWithTokenBlocking(Context context, String accessToken) {
        ProfileRegistry.initialize(context);
        DriveApiClient api = new DriveApiClient();
        boolean changed = false;
        Throwable firstError = null;
        for (ProfileRegistry.Mode mode : ProfileRegistry.Mode.values()) {
            String id = mode == ProfileRegistry.Mode.CHAT ? CHAT_DOCUMENT_ID : WORK_DOCUMENT_ID;
            try {
                DriveApiClient.Metadata metadata = api.getPollMetadata(accessToken, id);
                if (metadata.trashed || !DriveApiClient.MIME_DOCUMENT.equals(metadata.mimeType)) {
                    throw new IllegalStateException("canonical registry metadata invalid");
                }
                ProfileRegistry.SnapshotInfo cached = ProfileRegistry.snapshotInfo(mode);
                boolean unchanged = cached.count > 0
                        && metadata.version.equals(cached.driveVersion)
                        && metadata.modifiedTime.equals(cached.modifiedTime);
                if (unchanged) continue;
                String raw = api.readDocumentText(accessToken, id).trim();
                ProfileRegistry.acceptCanonicalSnapshot(mode, raw, metadata.version, metadata.modifiedTime);
                changed = true;
            } catch (Throwable error) {
                if (firstError == null) firstError = error;
            }
        }
        if (firstError == null) ProfileRegistry.markRefreshSuccess();
        else ProfileRegistry.markRefreshFailure(firstError);
        return new RefreshResult(changed, firstError);
    }

    private static void finish(boolean changed, Throwable error) {
        List<Completion> callbacks;
        synchronized (LOCK) {
            inFlight = false;
            callbacks = new ArrayList<>(WAITERS);
            WAITERS.clear();
        }
        if (error == null) ProfileRegistry.markRefreshSuccess();
        else ProfileRegistry.markRefreshFailure(error);
        for (Completion callback : callbacks) {
            try { callback.onComplete(changed, error); }
            catch (Throwable ignored) { }
        }
    }

    static final class RefreshResult {
        final boolean changed;
        final Throwable error;
        RefreshResult(boolean changed, Throwable error) {
            this.changed = changed;
            this.error = error;
        }
    }
}
