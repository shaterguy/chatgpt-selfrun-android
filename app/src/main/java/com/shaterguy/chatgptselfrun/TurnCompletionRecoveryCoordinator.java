package com.shaterguy.chatgptselfrun;

import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.webkit.WebView;

import com.google.android.gms.auth.api.identity.AuthorizationResult;

/** Handles bounded ambiguity-watchdog callbacks without expanding the main Service state machine. */
final class TurnCompletionRecoveryCoordinator {
    private static final Object PROBE_LOCK = new Object();
    private static String activeProbeKey = "";

    private TurnCompletionRecoveryCoordinator() {}

    static boolean handleNavigation(Context context, WebView view, Uri uri) {
        if (context == null || view == null || uri == null
                || !SelfRunContinuationDom.TURN_COMPLETION_SCHEME.equals(uri.getScheme())) return false;
        String host = uri.getHost();
        if (!TurnCompletionRecoveryPolicy.REBIND_HOST.equals(host)
                && !TurnCompletionRecoveryPolicy.DRIVE_PROBE_HOST.equals(host)
                && !TurnCompletionRecoveryPolicy.RECOVER_HOST.equals(host)) return false;

        Context app = context.getApplicationContext();
        SelfRunStore store = new SelfRunStore(app);
        String runId = uri.getQueryParameter("run");
        String token = uri.getQueryParameter("token");
        SelfRunRunLog log = new SelfRunRunLog(app);
        if (!TurnCompletionRecoveryPolicy.validWatchdogCallback(store, runId, token)) {
            log.record(store, "DOM_COMPLETION_WATCHDOG", "stage=" + safe(host) + ";result=rejected");
            return true;
        }

        if (TurnCompletionRecoveryPolicy.REBIND_HOST.equals(host)) {
            forceObserverRebind(view, store, token);
            log.record(store, "DOM_COMPLETION_WATCHDOG", "stage=rebind;result=scheduled");
            return true;
        }
        if (TurnCompletionRecoveryPolicy.DRIVE_PROBE_HOST.equals(host)) {
            boolean attached = HeadlessWebViewHost.attachOutputFor(view);
            try { view.evaluateJavascript("window.__selfRunDomAssistantFallback?.evaluate?.()", null); }
            catch (Throwable ignored) { }
            log.record(store, "DOM_COMPLETION_WATCHDOG",
                    "stage=drive_probe;output=" + (attached ? "attached" : "unchanged"));
            probeDrive(app, view, store, runId, token);
            return true;
        }

        controlledRecover(app, view, store, token, log);
        return true;
    }

    private static void forceObserverRebind(WebView view, SelfRunStore store, String token) {
        String quoted = SelfRunScript.quote(token);
        String reset = "(()=>{const s=window.__selfRunDriveTurnObserver;if(s&&s.token===" + quoted
                + "){try{s.observer?.disconnect();}catch(_){}if(s.timer)clearTimeout(s.timer);"
                + "s.timer=0;s.idleSince=0;s.observer=null;s.root=null;s.composer=null;}return 'REBIND_READY';})()";
        String observe = SelfRunContinuationDom.observeTurnCompletion(store.conversationUrl(), store.runId(), token,
                SelfRunService.TURN_COMPLETION_STABILITY_MS, store.turnObserverSawStop());
        try {
            view.evaluateJavascript(reset, ignored -> {
                try { view.evaluateJavascript(observe, null); }
                catch (Throwable ignoredAgain) { }
            });
        } catch (Throwable ignored) { }
    }

    private static void probeDrive(Context app, WebView view, SelfRunStore snapshot,
                                   String runId, String token) {
        String key = runId + ":" + token;
        synchronized (PROBE_LOCK) {
            if (key.equals(activeProbeKey)) return;
            activeProbeKey = key;
        }
        String turnDocumentId = snapshot.turnDocumentId();
        String jobFolderId = snapshot.jobFolderId();
        String expectedAccountId = snapshot.runDriveAccountId();
        int cursor = snapshot.driveSignalCursor();
        int cursorSchema = snapshot.driveSignalCursorSchemaVersion();
        String mode = snapshot.mode();
        boolean signalTransport = SelfRunSignalTransport.isSignalDocumentRun(app, runId);
        if (!DriveApiClient.validFileId(turnDocumentId) || !DriveApiClient.validFileId(jobFolderId)
                || !DriveApiClient.validOpaqueAccountId(expectedAccountId)
                || cursorSchema != SelfRunStore.DRIVE_SIGNAL_CURSOR_SCHEMA_PHYSICAL) {
            finishProbe(app, view, runId, token, key, false, "invalid_baseline");
            return;
        }

        DriveAuthorization.requestSilently(app, new DriveAuthorization.Callback() {
            @Override public void onAuthorized(AuthorizationResult result) {
                String accessToken = DriveAuthorization.accessToken(result);
                if (accessToken.isEmpty()) {
                    finishProbe(app, view, runId, token, key, false, "access_token_empty");
                    return;
                }
                Thread worker = new Thread(() -> {
                    boolean completion = false;
                    String detail = "missing";
                    try {
                        DriveApiClient client = new DriveApiClient();
                        String accountId = client.getAccountPermissionId(accessToken);
                        if (!expectedAccountId.equals(accountId)) throw new IllegalStateException("account_mismatch");
                        DriveApiClient.Metadata metadata = client.getPollMetadata(
                                accessToken, turnDocumentId, signalTransport);
                        if (metadata.trashed || metadata.shared
                                || !DriveApiClient.MIME_DOCUMENT.equals(metadata.mimeType)
                                || !jobFolderId.equals(metadata.parentId)) {
                            throw new IllegalStateException("metadata_mismatch");
                        }
                        DriveApiClient.DocumentSnapshot document = client.readDocumentSnapshot(accessToken, turnDocumentId);
                        DriveSignalParser.Scan scan = DriveSignalParser.scan(document.text, runId, cursor, mode);
                        completion = TurnCompletionRecoveryPolicy.hasUsableDriveCompletion(scan);
                        detail = completion ? "completion" : "missing";
                    } catch (Throwable error) {
                        detail = "error_" + error.getClass().getSimpleName();
                    }
                    finishProbe(app, view, runId, token, key, completion, detail);
                }, "SelfRunDriveCompletionProbe");
                worker.start();
            }

            @Override public void onResolutionRequired(android.app.PendingIntent ignored) {
                finishProbe(app, view, runId, token, key, false, "authorization_required");
            }

            @Override public void onFailure(Throwable error) {
                finishProbe(app, view, runId, token, key, false,
                        "authorization_" + (error == null ? "failed" : error.getClass().getSimpleName()));
            }
        });
    }

    private static void finishProbe(Context app, WebView view, String runId, String token,
                                    String key, boolean completion, String detail) {
        new Handler(Looper.getMainLooper()).post(() -> {
            synchronized (PROBE_LOCK) {
                if (key.equals(activeProbeKey)) activeProbeKey = "";
            }
            SelfRunStore current = new SelfRunStore(app);
            if (!TurnCompletionRecoveryPolicy.validWatchdogCallback(current, runId, token)) return;
            new SelfRunRunLog(app).record(current, "DOM_COMPLETION_WATCHDOG",
                    "stage=drive_probe;result=" + safe(detail));
            if (completion && current.beginPostDomDriveSync(token)) {
                startRuntime(app);
                return;
            }
            try {
                view.evaluateJavascript(
                        "window.__selfRunDomAssistantFallback?.driveProbeResult?.('missing')", null);
            } catch (Throwable ignored) { }
        });
    }

    private static void controlledRecover(Context app, WebView view, SelfRunStore store,
                                          String token, SelfRunRunLog log) {
        if (TurnProtocolUiState.activeGenerationFor(token)) {
            forceObserverRebind(view, store, token);
            try { view.evaluateJavascript("window.__selfRunDomAssistantFallback?.evaluate?.()", null); }
            catch (Throwable ignored) { }
            log.record(store, "DOM_COMPLETION_WATCHDOG",
                    "stage=recover;result=deferred_active_protocol");
            return;
        }
        try { view.evaluateJavascript(SelfRunContinuationDom.cancelTurnCompletionObserver(token), null); }
        catch (Throwable ignored) { }
        HeadlessWebViewHost.detachOutputFor(view);
        String message = "답변 완료 상태를 확정하지 못해 자동 CONTINUE를 차단했습니다.";
        if (!SelfRunRolloverPolicy.knownConversation(store.conversationUrl()) || !networkValidated(app)) {
            pauseFailClosed(app, store, message, log, "network_or_conversation_unavailable");
            return;
        }
        SelfRunRolloverCoordinator.Result result = new SelfRunRolloverCoordinator(app)
                .beginOrResume(store, SelfRunRolloverPolicy.TURN_COMPLETION_SIGNAL_TIMEOUT);
        log.record(store, "DOM_COMPLETION_WATCHDOG",
                "stage=recover;result=" + safe(result.status) + ";successor=" + safe(result.successorRunId));
        if (result.started()) {
            startRuntime(app);
            return;
        }
        pauseFailClosed(app, store, message, log, "rollover_" + safe(result.status));
    }

    private static void pauseFailClosed(Context app, SelfRunStore store, String message,
                                        SelfRunRunLog log, String reason) {
        String prior = store.phase();
        try {
            store.setLastError("DOM_COMPLETION_AMBIGUOUS_TIMEOUT", message);
            store.enterPause(prior, false);
            store.setStatus("DOM_COMPLETION_AMBIGUOUS_TIMEOUT · " + message);
            log.record(store, "DOM_COMPLETION_WATCHDOG", "stage=recover;result=pause;reason=" + safe(reason));
            NotificationHelper.notifyUser(app, "확인 필요", store.status());
        } catch (Throwable ignored) { }
    }

    private static boolean networkValidated(Context app) {
        try {
            ConnectivityManager manager = app.getSystemService(ConnectivityManager.class);
            Network network = manager == null ? null : manager.getActiveNetwork();
            NetworkCapabilities caps = network == null || manager == null ? null : manager.getNetworkCapabilities(network);
            return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void startRuntime(Context app) {
        try {
            Intent intent = new Intent(app, SelfRunService.class).setAction(SelfRunService.ACTION_RUN);
            app.startForegroundService(intent);
        } catch (Throwable ignored) { }
    }

    private static String safe(String value) {
        return value == null ? "" : value.replaceAll("[^A-Za-z0-9._:-]", "_");
    }
}
