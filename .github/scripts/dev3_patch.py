from pathlib import Path

def replace(path, old, new, count=1):
    p = Path(path)
    s = p.read_text(encoding='utf-8')
    actual = s.count(old)
    if actual != count:
        raise SystemExit(f'{path}: expected {count} occurrences, found {actual}: {old[:120]!r}')
    p.write_text(s.replace(old, new, count), encoding='utf-8')

def replace_all(path, old, new):
    p = Path(path)
    s = p.read_text(encoding='utf-8')
    if old not in s:
        raise SystemExit(f'{path}: token not found: {old!r}')
    p.write_text(s.replace(old, new), encoding='utf-8')

# Version identity.
replace_all('app/build.gradle', 'versionCode 1000002', 'versionCode 1000003')
replace_all('app/build.gradle', "versionName '1.0.0-dev2'", "versionName '1.0.0-dev3'")
replace_all('app/build.gradle', 'android.defaultConfig.versionCode != 1000002',
            'android.defaultConfig.versionCode != 1000003')
replace_all('app/build.gradle', "android.defaultConfig.versionName != '1.0.0-dev2'",
            "android.defaultConfig.versionName != '1.0.0-dev3'")
replace_all('.github/workflows/build-drive-v1.yml', 'selfrun-drive/v1.0.0-dev2',
            'selfrun-drive/v1.0.0-dev3')
replace_all('.github/workflows/build-drive-v1.yml', '1000002', '1000003')
replace_all('.github/workflows/build-drive-v1.yml', '1.0.0-dev2', '1.0.0-dev3')
replace_all('tools/verify_drive_variant.sh', '1000002', '1000003')
replace_all('tools/verify_drive_variant.sh', '1.0.0-dev2', '1.0.0-dev3')
replace_all('app/src/test/java/com/shaterguy/chatgptselfrun/DriveVariantPolicyTest.java',
            '1000002', '1000003')
replace_all('app/src/test/java/com/shaterguy/chatgptselfrun/DriveVariantPolicyTest.java',
            '1.0.0-dev2', '1.0.0-dev3')

# Bootstrap simplification.
replace('app/src/main/java/com/shaterguy/chatgptselfrun/SelfRunProtocol.java',
'''    static String bootstrapDrive(String runId, String mode, String requirement, String baseFolderId,
                                 String jobFolderId, String documentId, String documentUrl,
                                 int expectedTurn) {
        return "[SELF_RUN_BOOTSTRAP 0.1.0 " + runId + " MODE=" + mode + "]\\n"
                + "SELF_RUN_CLIENT=DRIVE_V1\\n"
                + "ANDROID_APPLICATION_ID=" + BuildConfig.APPLICATION_ID + "\\n"
                + "DRIVE_PROTOCOL_VERSION=1\\n"
                + "DRIVE_JOB_ID=" + runId + "\\n"
                + "DRIVE_RUNS_BASE_FOLDER_ID=" + baseFolderId + "\\n"
                + "DRIVE_JOB_FOLDER_ID=" + jobFolderId + "\\n"
                + "DRIVE_TURN_DOCUMENT_ID=" + documentId + "\\n"
                + "DRIVE_TURN_DOCUMENT_URL=" + documentUrl + "\\n"
                + "DRIVE_EXPECTED_TURN=" + expectedTurn + "\\n\\n"
                + requirement.trim();
    }
''',
'''    static String bootstrapDrive(String runId, String mode, String requirement, String documentId) {
        return "[SELF_RUN_BOOTSTRAP 0.1.0 " + runId + " MODE=" + mode + "]\\n"
                + "SELF_RUN_CLIENT=DRIVE_V1\\n"
                + "DRIVE_TURN_DOCUMENT_ID=" + documentId + "\\n"
                + "최종 답변 출력 직전에 위 작업문서에 이 턴의 SelfRun 완료 신호와 완료 시점을 기록한다.\\n\\n"
                + requirement.trim();
    }
''')

# SelfRunService state machine.
service = Path('app/src/main/java/com/shaterguy/chatgptselfrun/SelfRunService.java')
s = service.read_text(encoding='utf-8')

def rs(old, new, count=1):
    global s
    actual = s.count(old)
    if actual != count:
        raise SystemExit(f'SelfRunService.java: expected {count}, found {actual}: {old[:120]!r}')
    s = s.replace(old, new, count)

rs('''    static final long CONTINUATION_GUARD_MS = 120_000L;
    private static final long[] BACKOFF = {15_000L, 30_000L, 60_000L, 120_000L, 240_000L};
''',
'''    static final long CONTINUATION_GUARD_MS = 45_000L;
    static final long SUBMISSION_RETRY_MS = 5 * 60_000L;
    static final long SUBMISSION_CONFIRMATION_GRACE_MS = 15_000L;
    private static final long[] BACKOFF = {15_000L, 30_000L, 60_000L, 120_000L, 240_000L};
''')

rs('''    private final Runnable webRunnable = this::runWebStep;
    private final Runnable guardRunnable = this::guardElapsed;
''',
'''    private final Runnable webRunnable = this::runWebStep;
    private final Runnable guardRunnable = this::guardElapsed;
    private final Runnable submissionRetryRunnable = this::submissionRetryElapsed;
''')

rs('''    private void resumeStateMachine() {
        if (!canRun()) return;
        String phase = store.phase();
        if (SelfRunStore.PHASE_SEND_CONTINUE.equals(phase)
''',
'''    private void resumeStateMachine() {
        if (!canRun()) return;
        if (store.hasSubmissionRetry()) {
            scheduleOrResumeSubmissionRetry();
            return;
        }
        String phase = store.phase();
        if (SelfRunStore.PHASE_SEND_CONTINUE.equals(phase)
''')

rs('''            transition(SelfRunStore.PHASE_DRIVE_COMMIT_GUARD, "Drive commit 안전 지연 · 120초", "continue_commit");
''',
'''            transition(SelfRunStore.PHASE_DRIVE_COMMIT_GUARD, "Drive commit 안전 지연 · 45초", "continue_commit");
''')

rs('''        if (store.pendingEventSeq() < 1 || store.pendingCommitId().isEmpty()
                || detectedAt <= 0 || dueAt - detectedAt < CONTINUATION_GUARD_MS
                || dueAt - detectedAt > 180_000L) {
''',
'''        if (store.pendingEventSeq() < 1 || store.pendingCommitId().isEmpty()
                || detectedAt <= 0 || dueAt - detectedAt != CONTINUATION_GUARD_MS) {
''')

rs('''                @Override public void onReceivedSslError(WebView v, SslErrorHandler h, SslError e) {
                    h.cancel();
                    if (launchedRunId.equals(store.runId())) pauseError("WEBVIEW_SSL", "SSL 오류");
                }
''',
'''                @Override public void onReceivedSslError(WebView v, SslErrorHandler h, SslError e) {
                    h.cancel();
                    if (launchedRunId.equals(store.runId()) && canRun()
                            && isWebAutomationPhase(store.phase())) {
                        runLog.record(store, "WEBVIEW_SSL_RETRY", "cancelled;retry_in=300000");
                        postWebCallback(SelfRunService.this::restoreCanonical, SUBMISSION_RETRY_MS);
                    }
                }
''')

rs('''            case SelfRunStore.PHASE_BOOTSTRAP_SEND -> script = SelfRunStore.BOOTSTRAP_SUBMISSION_STARTED
                    .equals(store.bootstrapSubmissionState())
                    ? SelfRunDom.checkDriveInitialSubmitted(store.projectUrl(), store.runId())
                    : SelfRunDom.sendDriveInitial(store.projectUrl(), driveBootstrap(), store.runId());
''',
'''            case SelfRunStore.PHASE_BOOTSTRAP_SEND -> {
                if (store.retryForBootstrap() && store.submissionRetryReady()) {
                    script = SelfRunDom.prepareDriveInitialRetry(store.projectUrl(), driveBootstrap(), store.runId());
                } else if (SelfRunStore.BOOTSTRAP_SUBMISSION_STARTED.equals(store.bootstrapSubmissionState())
                        || store.retryForBootstrap()) {
                    script = SelfRunDom.checkDriveInitialSubmitted(store.projectUrl(), store.runId());
                } else {
                    script = SelfRunDom.sendDriveInitial(store.projectUrl(), driveBootstrap(), store.runId());
                }
            }
''')

rs('''            case SelfRunStore.PHASE_SEND_CONTINUE -> {
                String prompt = continuationPrompt();
                if (SelfRunStore.SUBMISSION_STARTED.equals(store.submissionState())) {
                    script = SelfRunDom.checkDriveTurnSubmitted(store.conversationUrl(), prompt, store.pendingCommitId());
                } else script = SelfRunDom.prepareDriveTurn(store.conversationUrl(), prompt, store.pendingCommitId());
            }
''',
'''            case SelfRunStore.PHASE_SEND_CONTINUE -> {
                String prompt = continuationPrompt();
                if (store.retryForContinue() && store.submissionRetryReady()) {
                    script = SelfRunDom.prepareDriveTurnRetry(store.conversationUrl(), prompt, store.pendingCommitId(),
                            store.submissionBaselineCount());
                } else if (SelfRunStore.SUBMISSION_STARTED.equals(store.submissionState())
                        || store.retryForContinue()) {
                    script = SelfRunDom.checkDriveTurnSubmitted(store.conversationUrl(), prompt,
                            store.pendingCommitId(), store.submissionBaselineCount());
                } else {
                    script = SelfRunDom.prepareDriveTurn(store.conversationUrl(), prompt, store.pendingCommitId());
                }
            }
''')

old_eval = '''            JSONObject result = parse(raw); String status = result.optString("status", "SCRIPT_ERROR");
            if ("TARGET_ERROR".equals(status)) { restoreCanonical(); return; }
            if ("AUTH_REQUIRED".equals(status)) { pauseError("CHATGPT_AUTH_REQUIRED", "SelfRun Drive에서 ChatGPT 로그인이 필요합니다."); return; }
            if ("MARKER_FAILED".equals(status)) { pauseError("SUBMISSION_MARKER_FAILED", result.optString("detail")); return; }
            if ("SUBMISSION_AMBIGUOUS".equals(status) || "SUBMISSION_PENDING".equals(status)) {
                enterPreservedPause("SUBMISSION_AMBIGUOUS", "continuation 제출 결과 확인 필요 · 자동 재전송 차단", false); return;
            }
            if ("BOOTSTRAP_SUBMISSION_AMBIGUOUS".equals(status)
                    || "BOOTSTRAP_SUBMISSION_PENDING".equals(status)) {
                enterPreservedPause("BOOTSTRAP_SUBMISSION_RESULT_UNKNOWN",
                        "첫 요청 제출 결과가 불명확해 자동 재전송을 차단했습니다.", false);
                return;
            }
            if ("BOOTSTRAP_SUBMITTED".equals(status)) {
                if (SelfRunStore.BOOTSTRAP_SUBMISSION_STARTED.equals(store.bootstrapSubmissionState())
                        && store.bootstrapSubmittedAt() > 0
                        && System.currentTimeMillis() - store.bootstrapSubmittedAt() >= 120_000L) {
                    enterPreservedPause("BOOTSTRAP_SUBMISSION_RESULT_UNKNOWN",
                            "첫 요청은 클릭됐지만 conversation URL을 확정하지 못해 자동 재전송을 차단했습니다.", false);
                } else scheduleWeb(1_200L);
                return;
            }
            if ("UI_WAIT".equals(status) || "WAIT".equals(status) || "SUBMITTED".equals(status)) {
                if (SelfRunStore.PHASE_SEND_CONTINUE.equals(phase)
                        && SelfRunStore.SUBMISSION_STARTED.equals(store.submissionState())
                        && store.submissionStartedAt() > 0
                        && System.currentTimeMillis() - store.submissionStartedAt() >= 120_000L) {
                    enterPreservedPause("SUBMISSION_CONFIRMATION_TIMEOUT",
                            "continuation 사용자 메시지 확인 시간 초과 · 자동 재전송 차단", false);
                    return;
                }
                scheduleWeb("WAIT".equals(status) ? 2_000L : 1_200L); return;
            }
            handleWebResult(phase, status, result);
'''
new_eval = '''            JSONObject result = parse(raw); String status = result.optString("status", "SCRIPT_ERROR");
            if ("TARGET_ERROR".equals(status)) { restoreCanonical(); return; }
            if ("AUTH_REQUIRED".equals(status)) {
                enterPreservedPause("CHATGPT_AUTH_REQUIRED", "ChatGPT 로그인 필요 · 사용자 조치 대기", false);
                NotificationHelper.notifyUser(this, "확인 필요", store.status());
                return;
            }
            if ("MARKER_FAILED".equals(status) || "SCRIPT_ERROR".equals(status)) {
                if (isSubmissionPhase(phase)) scheduleSubmissionRetryForPhase(phase, status);
                else scheduleWeb(2_000L);
                return;
            }
            if ("SUBMISSION_AMBIGUOUS".equals(status) || "SUBMISSION_PENDING".equals(status)) {
                scheduleSubmissionRetry(SelfRunStore.RETRY_CONTINUE, status);
                return;
            }
            if ("BOOTSTRAP_SUBMISSION_AMBIGUOUS".equals(status)
                    || "BOOTSTRAP_SUBMISSION_PENDING".equals(status)) {
                scheduleSubmissionRetry(SelfRunStore.RETRY_BOOTSTRAP, status);
                return;
            }
            if ("BOOTSTRAP_SUBMITTED".equals(status)) {
                if (store.retryForBootstrap() && store.submissionRetryDue()
                        && !store.submissionRetryReady()) {
                    store.markSubmissionRetryReady();
                    store.setStatus("첫 요청 재시도 준비 · 이전 제출 미확인");
                    scheduleWeb(100L);
                } else if (!store.hasSubmissionRetry()
                        && SelfRunStore.BOOTSTRAP_SUBMISSION_STARTED.equals(store.bootstrapSubmissionState())
                        && store.bootstrapSubmittedAt() > 0
                        && System.currentTimeMillis() - store.bootstrapSubmittedAt()
                                >= SUBMISSION_CONFIRMATION_GRACE_MS) {
                    scheduleSubmissionRetry(SelfRunStore.RETRY_BOOTSTRAP, "BOOTSTRAP_SUBMISSION_UNCONFIRMED");
                } else {
                    scheduleWeb(1_200L);
                }
                return;
            }
            if ("UI_WAIT".equals(status) || "WAIT".equals(status) || "SUBMITTED".equals(status)) {
                if (SelfRunStore.PHASE_SEND_CONTINUE.equals(phase)
                        && store.retryForContinue() && store.submissionRetryDue()
                        && !store.submissionRetryReady() && "WAIT".equals(status)) {
                    store.markSubmissionRetryReady();
                    store.setStatus("continuation 재시도 준비 · 이전 제출 미확인");
                    scheduleWeb(100L);
                    return;
                }
                if (SelfRunStore.PHASE_SEND_CONTINUE.equals(phase)
                        && !store.hasSubmissionRetry()
                        && SelfRunStore.SUBMISSION_STARTED.equals(store.submissionState())
                        && store.submissionStartedAt() > 0
                        && System.currentTimeMillis() - store.submissionStartedAt()
                                >= SUBMISSION_CONFIRMATION_GRACE_MS) {
                    scheduleSubmissionRetry(SelfRunStore.RETRY_CONTINUE, "SUBMISSION_UNCONFIRMED");
                    return;
                }
                scheduleWeb("WAIT".equals(status) ? 2_000L : 1_200L);
                return;
            }
            handleWebResult(phase, status, result);
'''
rs(old_eval, new_eval)

rs('''        if (SelfRunStore.PHASE_BOOTSTRAP_SEND.equals(phase) && "READY_TO_SUBMIT".equals(status)) {
            store.markBootstrapSubmissionStarted();
            evaluate(SelfRunStore.PHASE_BOOTSTRAP_SEND,
                    SelfRunDom.clickPreparedDriveInitial(store.projectUrl(), driveBootstrap(), store.runId()));
            return;
        }
''',
'''        if (SelfRunStore.PHASE_BOOTSTRAP_SEND.equals(phase) && "READY_TO_SUBMIT".equals(status)) {
            if (store.retryForBootstrap() && store.submissionRetryReady()) store.markBootstrapRetryStarted();
            else store.markBootstrapSubmissionStarted();
            evaluate(SelfRunStore.PHASE_BOOTSTRAP_SEND,
                    SelfRunDom.clickPreparedDriveInitial(store.projectUrl(), driveBootstrap(), store.runId()));
            return;
        }
''')

rs('''        if (SelfRunStore.PHASE_SEND_CONTINUE.equals(phase)) {
            if ("CONFIRMED".equals(status)) { confirmContinuation(); return; }
            if ("READY_TO_SUBMIT".equals(status)) { startAndClickContinuation(); return; }
        }
''',
'''        if (SelfRunStore.PHASE_SEND_CONTINUE.equals(phase)) {
            if ("CONFIRMED".equals(status)) { confirmContinuation(); return; }
            if ("READY_TO_SUBMIT".equals(status)) {
                int beforeCount = result.optInt("beforeCount", -1);
                if (beforeCount < 0) scheduleSubmissionRetry(SelfRunStore.RETRY_CONTINUE, "BASELINE_MISSING");
                else startAndClickContinuation(beforeCount);
                return;
            }
        }
''')

rs('''    private void startAndClickContinuation() {
        // Must be durable before the click. A crash from this point is resolved by user-message marker only.
        store.markSubmissionStarted();
        String script = SelfRunDom.clickPreparedDriveTurn(store.conversationUrl(), continuationPrompt(), store.pendingCommitId());
        evaluate(SelfRunStore.PHASE_SEND_CONTINUE, script);
    }
''',
'''    private void startAndClickContinuation(int beforeCount) {
        // Baseline and SUBMISSION_STARTED are durable before the click. Recovery checks the user-turn count first.
        store.markSubmissionStarted(beforeCount);
        String script = SelfRunDom.clickPreparedDriveTurn(store.conversationUrl(), continuationPrompt(), store.pendingCommitId());
        evaluate(SelfRunStore.PHASE_SEND_CONTINUE, script);
    }
''')

rs('''    private String driveBootstrap() {
        return SelfRunProtocol.bootstrapDrive(store.runId(), store.mode(), store.requirement(), store.runBaseFolderId(),
                store.jobFolderId(), store.turnDocumentId(), store.turnDocumentUrl(), store.expectedTurn());
    }
''',
'''    private String driveBootstrap() {
        return SelfRunProtocol.bootstrapDrive(store.runId(), store.mode(), store.requirement(), store.turnDocumentId());
    }
''')

insert_before = '''    private void scheduleDrivePoll() {
'''
retry_helpers = '''    private static boolean isSubmissionPhase(String phase) {
        return SelfRunStore.PHASE_BOOTSTRAP_SEND.equals(phase)
                || SelfRunStore.PHASE_SEND_CONTINUE.equals(phase);
    }

    private void scheduleSubmissionRetryForPhase(String phase, String reason) {
        if (SelfRunStore.PHASE_BOOTSTRAP_SEND.equals(phase)) {
            scheduleSubmissionRetry(SelfRunStore.RETRY_BOOTSTRAP, reason);
        } else if (SelfRunStore.PHASE_SEND_CONTINUE.equals(phase)) {
            scheduleSubmissionRetry(SelfRunStore.RETRY_CONTINUE, reason);
        }
    }

    private void scheduleSubmissionRetry(String kind, String reason) {
        if (!canRun()) return;
        if (store.hasSubmissionRetry()) {
            scheduleOrResumeSubmissionRetry();
            return;
        }
        long dueAt = System.currentTimeMillis() + SUBMISSION_RETRY_MS;
        store.scheduleSubmissionRetry(kind, reason, dueAt);
        runLog.record(store, "SUBMISSION_RETRY_WAIT",
                "kind=" + kind + ";attempt=" + store.submissionRetryAttempt()
                        + ";dueAt=" + dueAt + ";reason=" + reason);
        store.setStatus((SelfRunStore.RETRY_BOOTSTRAP.equals(kind) ? "첫 요청" : "continuation")
                + " 제출 미확인 · 5분 후 재확인/재시도");
        startForegroundCompat();
        scheduleOrResumeSubmissionRetry();
    }

    private void scheduleOrResumeSubmissionRetry() {
        if (!canRun() || !store.hasSubmissionRetry()) return;
        handler.removeCallbacks(webRunnable);
        handler.removeCallbacks(submissionRetryRunnable);
        releaseWakeLock();
        long delay = Math.max(0L, store.submissionRetryDueAt() - System.currentTimeMillis());
        if (delay == 0L) handler.post(submissionRetryRunnable);
        else handler.postDelayed(submissionRetryRunnable, delay);
    }

    private void submissionRetryElapsed() {
        if (!canRun() || !store.hasSubmissionRetry()) return;
        store.setStatus("제출 재시도 전 기존 제출 성공 여부 확인");
        runLog.record(store, "SUBMISSION_RETRY_CHECK",
                "kind=" + store.submissionRetryKind() + ";attempt=" + store.submissionRetryAttempt());
        ensureWebView();
    }

'''
if s.count(insert_before) != 1:
    raise SystemExit('SelfRunService.java: scheduleDrivePoll insertion point missing')
s = s.replace(insert_before, retry_helpers + insert_before, 1)

rs('''    private void scheduleWeb(long delay) {
        handler.removeCallbacks(webRunnable);
        if (webView != null && canRun() && isWebAutomationPhase(store.phase()))
            handler.postDelayed(webRunnable, delay);
    }
''',
'''    private void scheduleWeb(long delay) {
        handler.removeCallbacks(webRunnable);
        if (store.hasSubmissionRetry() && !store.submissionRetryDue()) return;
        if (webView != null && canRun() && isWebAutomationPhase(store.phase()))
            handler.postDelayed(webRunnable, delay);
    }
''')

rs('''                int index = Math.min(retryAttempt++, BACKOFF.length - 1);
                long base = BACKOFF[index];
                long jitter = ThreadLocalRandom.current().nextLong(Math.max(1L, base / 4L));
                String kind = error instanceof DriveApiClient.ApiException api ? "http_" + api.status : "network";
                runLog.record(store, "DRIVE_BACKOFF", "kind=" + kind + ";attempt=" + retryAttempt);
''',
'''                retryAttempt = retryAttempt == Integer.MAX_VALUE ? Integer.MAX_VALUE : retryAttempt + 1;
                int index = Math.min(Math.max(0, retryAttempt - 1), BACKOFF.length - 1);
                long base = BACKOFF[index];
                long jitter = ThreadLocalRandom.current().nextLong(Math.max(1L, base / 4L));
                String kind = error instanceof DriveApiClient.ApiException api ? "http_" + api.status : "network";
                runLog.record(store, "DRIVE_BACKOFF", "kind=" + kind + ";attempt=" + retryAttempt);
''')

rs('''        handler.removeCallbacks(driveRunnable); handler.removeCallbacks(webRunnable);
        handler.removeCallbacks(guardRunnable); handler.removeCallbacks(driveRetryRunnable);
''',
'''        handler.removeCallbacks(driveRunnable); handler.removeCallbacks(webRunnable);
        handler.removeCallbacks(guardRunnable); handler.removeCallbacks(driveRetryRunnable);
        handler.removeCallbacks(submissionRetryRunnable);
''')

service.write_text(s, encoding='utf-8')

# SelfRunStore durable retry state.
store = Path('app/src/main/java/com/shaterguy/chatgptselfrun/SelfRunStore.java')
s = store.read_text(encoding='utf-8')
def rt(old, new, count=1):
    global s
    actual = s.count(old)
    if actual != count:
        raise SystemExit(f'SelfRunStore.java: expected {count}, found {actual}: {old[:120]!r}')
    s = s.replace(old, new, count)

rt('''    static final String BOOTSTRAP_SUBMISSION_CONFIRMED = "BOOTSTRAP_SUBMISSION_CONFIRMED";
''',
'''    static final String BOOTSTRAP_SUBMISSION_CONFIRMED = "BOOTSTRAP_SUBMISSION_CONFIRMED";
    static final String RETRY_BOOTSTRAP = "BOOTSTRAP";
    static final String RETRY_CONTINUE = "CONTINUE";
''')

rt('''                .putString("submissionState", EVENT_CONSUMED).putLong("submissionStartedAt", 0L)
                .putString("lastSubmittedCommitId", "")
                .putString("creationStage", CREATION_NONE).putLong("bootstrapSubmittedAt", 0L)
''',
'''                .putString("submissionState", EVENT_CONSUMED).putLong("submissionStartedAt", 0L)
                .putInt("submissionBaselineCount", -1).putString("lastSubmittedCommitId", "")
                .putString("submissionRetryKind", "").putString("submissionRetryReason", "")
                .putLong("submissionRetryDueAt", 0L).putInt("submissionRetryAttempt", 0)
                .putBoolean("submissionRetryReady", false)
                .putString("creationStage", CREATION_NONE).putLong("bootstrapSubmittedAt", 0L)
''')

rt('''    String lastSubmittedCommitId() { return get("lastSubmittedCommitId"); }
    String creationStage() { return getOr("creationStage", CREATION_NONE); }
''',
'''    String lastSubmittedCommitId() { return get("lastSubmittedCommitId"); }
    int submissionBaselineCount() { return prefs.getInt("submissionBaselineCount", -1); }
    String submissionRetryKind() { return get("submissionRetryKind"); }
    String submissionRetryReason() { return get("submissionRetryReason"); }
    long submissionRetryDueAt() { return prefs.getLong("submissionRetryDueAt", 0L); }
    int submissionRetryAttempt() { return prefs.getInt("submissionRetryAttempt", 0); }
    boolean submissionRetryReady() { return prefs.getBoolean("submissionRetryReady", false); }
    boolean hasSubmissionRetry() {
        return (RETRY_BOOTSTRAP.equals(submissionRetryKind()) || RETRY_CONTINUE.equals(submissionRetryKind()))
                && submissionRetryDueAt() > 0L;
    }
    boolean submissionRetryDue() {
        return hasSubmissionRetry() && System.currentTimeMillis() >= submissionRetryDueAt();
    }
    boolean retryForBootstrap() { return RETRY_BOOTSTRAP.equals(submissionRetryKind()); }
    boolean retryForContinue() { return RETRY_CONTINUE.equals(submissionRetryKind()); }
    String creationStage() { return getOr("creationStage", CREATION_NONE); }
''')

rt('''    void markBootstrapSubmissionStarted() {
        if (!BOOTSTRAP_NOT_STARTED.equals(bootstrapSubmissionState())) {
            throw new IllegalStateException("bootstrap submission already started");
        }
        commitOrThrow(prefs.edit().putString("bootstrapSubmissionState", BOOTSTRAP_SUBMISSION_STARTED)
                .putLong("bootstrapSubmittedAt", System.currentTimeMillis()));
    }
''',
'''    void markBootstrapSubmissionStarted() {
        if (!BOOTSTRAP_NOT_STARTED.equals(bootstrapSubmissionState())) {
            throw new IllegalStateException("bootstrap submission already started");
        }
        commitOrThrow(prefs.edit().putString("bootstrapSubmissionState", BOOTSTRAP_SUBMISSION_STARTED)
                .putLong("bootstrapSubmittedAt", System.currentTimeMillis()));
    }

    void markBootstrapRetryStarted() {
        if (!BOOTSTRAP_SUBMISSION_STARTED.equals(bootstrapSubmissionState())
                || !retryForBootstrap() || !submissionRetryReady()) {
            throw new IllegalStateException("bootstrap retry is not ready");
        }
        commitOrThrow(prefs.edit().putLong("bootstrapSubmittedAt", System.currentTimeMillis())
                .putString("submissionRetryKind", "").putString("submissionRetryReason", "")
                .putLong("submissionRetryDueAt", 0L).putBoolean("submissionRetryReady", false));
    }
''')

rt('''        commitOrThrow(prefs.edit().putString("conversationUrl", safe(conversationUrl))
                .putString("bootstrapSubmissionState", BOOTSTRAP_SUBMISSION_CONFIRMED)
                .putString("phase", PHASE_WAIT_DRIVE_COMMIT)
''',
'''        commitOrThrow(prefs.edit().putString("conversationUrl", safe(conversationUrl))
                .putString("bootstrapSubmissionState", BOOTSTRAP_SUBMISSION_CONFIRMED)
                .putString("submissionRetryKind", "").putString("submissionRetryReason", "")
                .putLong("submissionRetryDueAt", 0L).putBoolean("submissionRetryReady", false)
                .putInt("submissionRetryAttempt", 0)
                .putString("phase", PHASE_WAIT_DRIVE_COMMIT)
''')

rt('''    void detectEvent(DriveCommitParser.Commit commit, long detectedAt, long dueAt) {
        commitOrThrow(prefs.edit().putLong("pendingEventSeq", commit.eventSeq).putInt("pendingTurn", commit.turn)
                .putString("pendingSignalRaw", commit.signalRaw).putString("pendingCommitId", commit.id())
                .putString("lastCommittedAt", commit.committedAt).putLong("commitDetectedAt", detectedAt)
                .putLong("guardDueAt", dueAt).putString("submissionState", EVENT_DETECTED));
        syncHistory();
    }

    void markGuarding() { commitOrThrow(prefs.edit().putString("submissionState", EVENT_GUARDING)); }
    void markSubmissionStarted() { commitOrThrow(prefs.edit().putString("submissionState", SUBMISSION_STARTED)
            .putLong("submissionStartedAt", System.currentTimeMillis())); }
''',
'''    void detectEvent(DriveCommitParser.Commit commit, long detectedAt, long dueAt) {
        commitOrThrow(prefs.edit().putLong("pendingEventSeq", commit.eventSeq).putInt("pendingTurn", commit.turn)
                .putString("pendingSignalRaw", commit.signalRaw).putString("pendingCommitId", commit.id())
                .putString("lastCommittedAt", commit.committedAt).putLong("commitDetectedAt", detectedAt)
                .putLong("guardDueAt", dueAt).putString("submissionState", EVENT_DETECTED)
                .putInt("submissionBaselineCount", -1)
                .putString("submissionRetryKind", "").putString("submissionRetryReason", "")
                .putLong("submissionRetryDueAt", 0L).putBoolean("submissionRetryReady", false)
                .putInt("submissionRetryAttempt", 0));
        syncHistory();
    }

    void markGuarding() { commitOrThrow(prefs.edit().putString("submissionState", EVENT_GUARDING)); }

    void markSubmissionStarted(int beforeCount) {
        if (beforeCount < 0) throw new IllegalArgumentException("submission baseline is required");
        commitOrThrow(prefs.edit().putString("submissionState", SUBMISSION_STARTED)
                .putLong("submissionStartedAt", System.currentTimeMillis())
                .putInt("submissionBaselineCount", beforeCount)
                .putString("submissionRetryKind", "").putString("submissionRetryReason", "")
                .putLong("submissionRetryDueAt", 0L).putBoolean("submissionRetryReady", false));
    }

    void scheduleSubmissionRetry(String kind, String reason, long dueAt) {
        if (!(RETRY_BOOTSTRAP.equals(kind) || RETRY_CONTINUE.equals(kind)) || dueAt <= 0L) {
            throw new IllegalArgumentException("valid retry state required");
        }
        int prior = submissionRetryAttempt();
        int next = prior == Integer.MAX_VALUE ? Integer.MAX_VALUE : prior + 1;
        commitOrThrow(prefs.edit().putString("submissionRetryKind", kind)
                .putString("submissionRetryReason", safe(reason))
                .putLong("submissionRetryDueAt", dueAt).putInt("submissionRetryAttempt", next)
                .putBoolean("submissionRetryReady", false));
        syncHistory();
    }

    void markSubmissionRetryReady() {
        if (!hasSubmissionRetry() || !submissionRetryDue()) {
            throw new IllegalStateException("submission retry is not due");
        }
        commitOrThrow(prefs.edit().putBoolean("submissionRetryReady", true));
        syncHistory();
    }
''')

rt('''                .putString("submissionState", EVENT_CONSUMED).putLong("submissionStartedAt", 0L)
                .putBoolean("resumeNeedsContinuation", false).putString("phase", PHASE_WAIT_DRIVE_COMMIT)
''',
'''                .putString("submissionState", EVENT_CONSUMED).putLong("submissionStartedAt", 0L)
                .putInt("submissionBaselineCount", -1)
                .putString("submissionRetryKind", "").putString("submissionRetryReason", "")
                .putLong("submissionRetryDueAt", 0L).putBoolean("submissionRetryReady", false)
                .putInt("submissionRetryAttempt", 0)
                .putBoolean("resumeNeedsContinuation", false).putString("phase", PHASE_WAIT_DRIVE_COMMIT)
''')

rt('''                .putString("pendingCommitId", "").putString("submissionState", EVENT_CONSUMED)
                .putLong("submissionStartedAt", 0L).putLong("phaseStartedAt", System.currentTimeMillis());
''',
'''                .putString("pendingCommitId", "").putString("submissionState", EVENT_CONSUMED)
                .putLong("submissionStartedAt", 0L).putInt("submissionBaselineCount", -1)
                .putString("submissionRetryKind", "").putString("submissionRetryReason", "")
                .putLong("submissionRetryDueAt", 0L).putBoolean("submissionRetryReady", false)
                .putInt("submissionRetryAttempt", 0)
                .putLong("phaseStartedAt", System.currentTimeMillis());
''')

rt('''                .putLong("guardDueAt", System.currentTimeMillis()).putString("submissionState", EVENT_GUARDING)
                .putBoolean("resumeNeedsContinuation", false).putBoolean("paused", false)
''',
'''                .putLong("guardDueAt", System.currentTimeMillis()).putString("submissionState", EVENT_GUARDING)
                .putInt("submissionBaselineCount", -1)
                .putString("submissionRetryKind", "").putString("submissionRetryReason", "")
                .putLong("submissionRetryDueAt", 0L).putBoolean("submissionRetryReady", false)
                .putInt("submissionRetryAttempt", 0)
                .putBoolean("resumeNeedsContinuation", false).putBoolean("paused", false)
''')
store.write_text(s, encoding='utf-8')

# DOM baseline and retry preparers.
dom = Path('app/src/main/java/com/shaterguy/chatgptselfrun/SelfRunDom.java')
s = dom.read_text(encoding='utf-8')
def rd(old, new, count=1):
    global s
    actual = s.count(old)
    if actual != count:
        raise SystemExit(f'SelfRunDom.java: expected {count}, found {actual}: {old[:120]!r}')
    s = s.replace(old, new, count)

rd('''                + "if(!persisted)return result('MARKER_FAILED','commit 표식 저장 실패');return result('READY_TO_SUBMIT','continuation 제출 준비 완료');}"
''',
'''                + "if(!persisted)return result('MARKER_FAILED','commit 표식 저장 실패');return JSON.stringify({status:'READY_TO_SUBMIT',detail:'continuation 제출 준비 완료',url:location.href,beforeCount:countPrompt()});}"
''')

marker = '''    /** Stage the unchanged legacy continuation line while keeping the Drive commit ID internal. */
'''
helper = '''    /** Retry bootstrap only after the previous click has remained unconfirmed for the retry interval. */
    static String prepareDriveInitialRetry(String projectUrl, String prompt, String runId) {
        String project = q(SelfRunScript.projectId(projectUrl));
        String expected = q(prompt);
        String marker = q("selfrun-drive:bootstrap:" + runId);
        return "(() =>{const result=(status,detail='',extra={})=>JSON.stringify({status,detail,url:location.href,...extra});"
                + projectGuard(project) + authGuard() + textHelpers(expected)
                + "const p2=location.pathname.split('/').filter(Boolean),ci=p2.indexOf('c'),conv=ci>=0&&ci+1<p2.length?p2[ci+1]:'';"
                + durableMarkerRead(marker)
                + "if(conv&&prior)return result('CONFIRMED','재시도 전 기존 bootstrap conversation 확인',{conversationUrl:location.href});"
                + "if(conv)return result('BOOTSTRAP_SUBMISSION_AMBIGUOUS','conversation은 존재하지만 bootstrap 표식이 없습니다.',{conversationUrl:location.href});"
                + composer() + "if(!composer)return result('UI_WAIT','bootstrap 재시도 입력창 대기');" + composerOps()
                + input()
                + "if(!same())return result('UI_WAIT','bootstrap 재시도 입력 반영 대기');const send=findSend();if(!send||send.disabled||send.getAttribute('aria-disabled')==='true')return result('UI_WAIT','bootstrap 재시도 전송 버튼 대기');"
                + "const markerKey2=" + marker + ",v=JSON.stringify({state:'prepared',at:Date.now(),runId:" + q(runId) + ",retry:true});let persisted=false;try{localStorage.setItem(markerKey2,v);persisted=localStorage.getItem(markerKey2)===v;}catch(_){}if(!persisted){try{sessionStorage.setItem(markerKey2,v);persisted=sessionStorage.getItem(markerKey2)===v;}catch(_){}}if(!persisted)return result('MARKER_FAILED','bootstrap 재시도 표식 저장 실패');return result('READY_TO_SUBMIT','bootstrap 재시도 준비 완료');})()";
    }

'''
if s.count(marker) != 1: raise SystemExit('SelfRunDom bootstrap retry insertion point missing')
s=s.replace(marker,helper+marker,1)

click_marker='''    /** Clicks at most once, and only after Android has durably stored SUBMISSION_STARTED. */
'''
retry_turn = '''    /** Retry path: re-check late success first, then establish a fresh baseline before another click. */
    static String prepareDriveTurnRetry(String conversationUrl, String prompt, String commitId, int androidBaseline) {
        String expected = q(prompt);
        String marker = q("selfrun-drive:commit:" + commitId);
        return "(() =>{const result=(status,detail='',extra={})=>JSON.stringify({status,detail,url:location.href,...extra});"
                + conversationGuard(q(SelfRunScript.conversationId(conversationUrl))) + authGuard() + textHelpers(expected)
                + "const countPrompt=()=>[...document.querySelectorAll('[data-message-author-role=\\\"user\\\"],article[data-turn=\\\"user\\\"]')].map(e=>canonical(e.innerText||e.textContent||'')).filter(t=>t===expected).length;"
                + durableMarkerRead(marker)
                + "const androidBaseline=" + androidBaseline + ";let markerBaseline=-1;try{const data=prior?JSON.parse(prior):null;markerBaseline=Number(data?.beforeCount??-1);}catch(_){}const baseline=androidBaseline>=0?androidBaseline:markerBaseline;if(baseline>=0&&countPrompt()>baseline)return result('CONFIRMED','재시도 전 기존 continuation 사용자 턴 확인');"
                + composer() + "if(!composer)return result('UI_WAIT','재시도 입력창 대기');" + composerOps()
                + input()
                + "if(!same())return result('UI_WAIT','재시도 입력 반영 대기');const send=findSend();if(!send||send.disabled||send.getAttribute('aria-disabled')==='true')return result('UI_WAIT','재시도 전송 버튼 대기');const before=countPrompt();"
                + "const markerKey2=" + marker + ",v=JSON.stringify({state:'prepared',at:Date.now(),commitId:" + q(commitId) + ",beforeCount:before,retry:true});let persisted=false;try{localStorage.setItem(markerKey2,v);persisted=localStorage.getItem(markerKey2)===v;}catch(_){}if(!persisted){try{sessionStorage.setItem(markerKey2,v);persisted=sessionStorage.getItem(markerKey2)===v;}catch(_){}}if(!persisted)return result('MARKER_FAILED','commit 재시도 표식 저장 실패');return result('READY_TO_SUBMIT','continuation 재시도 준비 완료',{beforeCount:before});})()";
    }

    /** Clicks at most once per attempt, only after Android durably stores the baseline and SUBMISSION_STARTED. */
'''
if s.count(click_marker)!=1: raise SystemExit('SelfRunDom retry turn insertion point missing')
s=s.replace(click_marker,retry_turn,1)

rd('''    static String checkDriveTurnSubmitted(String conversationUrl, String prompt, String commitId) {
        String expected = q(prompt);
        String marker = q("selfrun-drive:commit:" + commitId);
        return "(() =>{const result=(status,detail='')=>JSON.stringify({status,detail,url:location.href});"
                + conversationGuard(q(SelfRunScript.conversationId(conversationUrl))) + authGuard()
                + textHelpers(expected) + durableMarkerRead(marker)
                + "if(!prior)return result('WAIT','commit 제출 표식 미확인');let data;try{data=JSON.parse(prior);}catch(_){return result('WAIT','commit 제출 표식 해석 실패');}const count=[...document.querySelectorAll('[data-message-author-role=\\\"user\\\"],article[data-turn=\\\"user\\\"]')].map(e=>canonical(e.innerText||e.textContent||'')).filter(t=>t===expected).length;return data.state==='clicked'&&count>Number(data.beforeCount||0)?result('CONFIRMED','continuation 사용자 턴 증가 확인'):result('WAIT','continuation 사용자 턴 미확인');})()";
    }
''',
'''    static String checkDriveTurnSubmitted(String conversationUrl, String prompt, String commitId, int androidBaseline) {
        String expected = q(prompt);
        String marker = q("selfrun-drive:commit:" + commitId);
        return "(() =>{const result=(status,detail='')=>JSON.stringify({status,detail,url:location.href});"
                + conversationGuard(q(SelfRunScript.conversationId(conversationUrl))) + authGuard()
                + textHelpers(expected) + durableMarkerRead(marker)
                + "const count=[...document.querySelectorAll('[data-message-author-role=\\\"user\\\"],article[data-turn=\\\"user\\\"]')].map(e=>canonical(e.innerText||e.textContent||'')).filter(t=>t===expected).length;const androidBaseline=" + androidBaseline + ";if(androidBaseline>=0&&count>androidBaseline)return result('CONFIRMED','Android baseline 이후 continuation 사용자 턴 증가 확인');if(!prior)return result('WAIT','commit 제출 표식 미확인');let data;try{data=JSON.parse(prior);}catch(_){return result('WAIT','commit 제출 표식 해석 실패');}const markerBaseline=Number(data.beforeCount??-1);return data.state==='clicked'&&markerBaseline>=0&&count>markerBaseline?result('CONFIRMED','continuation 사용자 턴 증가 확인'):result('WAIT','continuation 사용자 턴 미확인');})()";
    }
''')
dom.write_text(s,encoding='utf-8')

# Tests.
protocol_test = Path('app/src/test/java/com/shaterguy/chatgptselfrun/SelfRunProtocolTest.java')
t=protocol_test.read_text(encoding='utf-8')
old='''    @Test
    public void driveBootstrapCarriesOnlyExactRunMetadataAndRequirement() {
        String text = SelfRunProtocol.bootstrapDrive("SR-1", SelfRunStore.MODE_CHAT, "do work",
                "runsFolder_12345678", "jobFolder_12345678", "document_12345678",
                "https://docs.google.com/document/d/document_12345678/edit", 1);
        assertTrue(text.contains("SELF_RUN_CLIENT=DRIVE_V1"));
        assertTrue(text.contains("ANDROID_APPLICATION_ID=com.shaterguy.chatgptselfrun.drive"));
        assertTrue(text.contains("DRIVE_PROTOCOL_VERSION=1"));
        assertTrue(text.contains("DRIVE_JOB_ID=SR-1"));
        assertTrue(text.contains("DRIVE_RUNS_BASE_FOLDER_ID=runsFolder_12345678"));
        assertTrue(text.contains("DRIVE_JOB_FOLDER_ID=jobFolder_12345678"));
        assertTrue(text.contains("DRIVE_TURN_DOCUMENT_ID=document_12345678"));
        assertTrue(text.contains("DRIVE_EXPECTED_TURN=1"));
        assertTrue(text.endsWith("\\n\\ndo work"));
        assertFalse(text.contains("Drive V1 실행 계약:"));
        assertFalse(text.contains("commit 작성, 동일 문서 readback"));
    }
'''
new='''    @Test
    public void driveBootstrapCarriesOnlyDriveModeDocumentAndFinalWriteInstruction() {
        String text = SelfRunProtocol.bootstrapDrive("SR-1", SelfRunStore.MODE_CHAT, "do work",
                "document_12345678");
        assertTrue(text.startsWith("[SELF_RUN_BOOTSTRAP 0.1.0 SR-1 MODE=CHAT]\\n"));
        assertTrue(text.contains("SELF_RUN_CLIENT=DRIVE_V1"));
        assertTrue(text.contains("DRIVE_TURN_DOCUMENT_ID=document_12345678"));
        assertTrue(text.contains("최종 답변 출력 직전에 위 작업문서에 이 턴의 SelfRun 완료 신호와 완료 시점을 기록한다."));
        assertTrue(text.endsWith("\\n\\ndo work"));
        assertFalse(text.contains("ANDROID_APPLICATION_ID="));
        assertFalse(text.contains("DRIVE_PROTOCOL_VERSION="));
        assertFalse(text.contains("DRIVE_JOB_ID="));
        assertFalse(text.contains("DRIVE_RUNS_BASE_FOLDER_ID="));
        assertFalse(text.contains("DRIVE_JOB_FOLDER_ID="));
        assertFalse(text.contains("DRIVE_TURN_DOCUMENT_URL="));
        assertFalse(text.contains("DRIVE_EXPECTED_TURN="));
    }
'''
if t.count(old)!=1: raise SystemExit('SelfRunProtocolTest bootstrap block mismatch')
protocol_test.write_text(t.replace(old,new),encoding='utf-8')

pause_test=Path('app/src/test/java/com/shaterguy/chatgptselfrun/SelfRunPauseResumeTest.java')
t=pause_test.read_text(encoding='utf-8')
t=t.replace('''        assertTrue(stop.contains("removeCallbacks(driveRetryRunnable)"));
''','''        assertTrue(stop.contains("removeCallbacks(driveRetryRunnable)"));
        assertTrue(stop.contains("removeCallbacks(submissionRetryRunnable)"));
''')
old='''    @Test public void continuationRecoveryChecksMarkerBeforeTimeoutPause() throws Exception {
        String source = source("SelfRunService.java");
        int checkScript = source.indexOf("SelfRunDom.checkDriveTurnSubmitted");
        int timeout = source.indexOf("SUBMISSION_CONFIRMATION_TIMEOUT");
        assertTrue(checkScript >= 0);
        assertTrue(timeout > checkScript);
    }
'''
new='''    @Test public void continuationRecoveryChecksSuccessBeforeFiveMinuteRetryAndNeverTimeoutPauses() throws Exception {
        String source = source("SelfRunService.java");
        assertTrue(source.contains("SelfRunDom.checkDriveTurnSubmitted"));
        assertTrue(source.contains("SUBMISSION_RETRY_MS = 5 * 60_000L"));
        assertTrue(source.contains("scheduleSubmissionRetry(SelfRunStore.RETRY_CONTINUE"));
        assertFalse(source.contains("SUBMISSION_CONFIRMATION_TIMEOUT"));
    }
'''
if t.count(old)!=1: raise SystemExit('SelfRunPauseResumeTest timeout block mismatch')
t=t.replace(old,new)
oldcall='''        String script = SelfRunDom.checkDriveTurnSubmitted(
                "https://chatgpt.com/g/g-p-demo/c/abc", "[SELF_RUN_CONTINUE SR-1]", "SR-1:1:1");
'''
newcall='''        String script = SelfRunDom.checkDriveTurnSubmitted(
                "https://chatgpt.com/g/g-p-demo/c/abc", "[SELF_RUN_CONTINUE SR-1]", "SR-1:1:1", 2);
'''
if t.count(oldcall)!=1: raise SystemExit('SelfRunPauseResumeTest check call mismatch')
pause_test.write_text(t.replace(oldcall,newcall),encoding='utf-8')

variant=Path('app/src/test/java/com/shaterguy/chatgptselfrun/DriveVariantPolicyTest.java')
t=variant.read_text(encoding='utf-8')
t=t.replace('assertTrue(service.contains("CONTINUATION_GUARD_MS = 120_000L"));',
            'assertTrue(service.contains("CONTINUATION_GUARD_MS = 45_000L"));')
t=t.replace('assertTrue(service.contains("SUBMISSION_CONFIRMATION_TIMEOUT"));',
            'assertTrue(service.contains("SUBMISSION_RETRY_MS = 5 * 60_000L"));\\n        assertFalse(service.contains("SUBMISSION_CONFIRMATION_TIMEOUT"));')
t=t.replace('assertTrue(service.contains("BOOTSTRAP_SUBMISSION_RESULT_UNKNOWN"));',
            'assertTrue(service.contains("RETRY_BOOTSTRAP"));\\n        assertFalse(service.contains("BOOTSTRAP_SUBMISSION_RESULT_UNKNOWN"));')
old='''        String drive = SelfRunProtocol.bootstrapDrive("SR-1", SelfRunStore.MODE_CHAT, "work",
                "runsFolder_12345678", "jobFolder_12345678", "document_12345678",
                "https://docs.google.com/document/d/document_12345678/edit", 1);
'''
new='''        String drive = SelfRunProtocol.bootstrapDrive("SR-1", SelfRunStore.MODE_CHAT, "work",
                "document_12345678");
'''
if t.count(old)!=1: raise SystemExit('DriveVariantPolicyTest bootstrap invocation mismatch')
variant.write_text(t.replace(old,new),encoding='utf-8')

cont=Path('app/src/test/java/com/shaterguy/chatgptselfrun/SelfRunContinuationSubmissionTest.java')
t=cont.read_text(encoding='utf-8')
insert='''    @Test
    public void retryPreparationRechecksLateSuccessAndReturnsFreshBaseline() {
        String script = SelfRunDom.prepareDriveTurnRetry(
                "https://chatgpt.com/g/g-p-demo/c/abc",
                "[SELF_RUN_CONTINUE SR-1]", "SR-1:2:7", 3);
        assertTrue(script.contains("countPrompt()>baseline"));
        assertTrue(script.contains("재시도 전 기존 continuation 사용자 턴 확인"));
        assertTrue(script.contains("beforeCount:before"));
        assertTrue(script.contains("retry:true"));
    }

    @Test
    public void androidBaselineConfirmsEvenWhenWebMarkerWasLost() {
        String script = SelfRunDom.checkDriveTurnSubmitted(
                "https://chatgpt.com/g/g-p-demo/c/abc",
                "[SELF_RUN_CONTINUE SR-1]", "SR-1:2:7", 3);
        assertTrue(script.contains("androidBaseline>=0&&count>androidBaseline"));
        assertTrue(script.contains("Android baseline 이후 continuation 사용자 턴 증가 확인"));
        assertTrue(!script.contains("assistant"));
    }

'''
pos=t.rfind('}')
if pos<0: raise SystemExit('SelfRunContinuationSubmissionTest closing brace missing')
cont.write_text(t[:pos]+insert+t[pos:],encoding='utf-8')

policy_test = Path('app/src/test/java/com/shaterguy/chatgptselfrun/SelfRunDriveDev3PolicyTest.java')
policy_test.write_text(r'''package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.*;

public class SelfRunDriveDev3PolicyTest {
    private static String source(String file) throws Exception {
        Path p = Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun", file);
        if (!Files.exists(p)) p = Paths.get("src/main/java/com/shaterguy/chatgptselfrun", file);
        return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
    }

    @Test public void guardIsWithinThirtyToSixtySeconds() throws Exception {
        String s = source("SelfRunService.java");
        assertTrue(s.contains("CONTINUATION_GUARD_MS = 45_000L"));
        assertFalse(s.contains("CONTINUATION_GUARD_MS = 120_000L"));
    }
    @Test public void driveCompletionNeverUsesAssistantCompletionDetector() throws Exception {
        String s = source("SelfRunService.java");
        assertFalse(s.contains("WAIT_ASSISTANT"));
        assertFalse(s.contains("observeAssistant"));
        assertFalse(s.contains("stop-button"));
        assertFalse(s.contains("GENERATING"));
    }
    @Test public void continuationSuccessIsOnlyUserTurnIncrease() throws Exception {
        String d = source("SelfRunDom.java");
        String part = d.substring(d.indexOf("static String checkDriveTurnSubmitted"),
                d.indexOf("static String observeAssistant"));
        assertTrue(part.contains("count>androidBaseline"));
        assertTrue(part.contains("count>markerBaseline"));
        assertFalse(part.contains("data-message-author-role=\\\"assistant\\\""));
        assertFalse(part.contains("send.click()"));
    }
    @Test public void ambiguousSubmissionSchedulesFiveMinuteRetryInsteadOfPause() throws Exception {
        String s = source("SelfRunService.java");
        assertTrue(s.contains("scheduleSubmissionRetry(SelfRunStore.RETRY_CONTINUE, status)"));
        assertTrue(s.contains("SUBMISSION_RETRY_MS = 5 * 60_000L"));
        assertFalse(s.contains("enterPreservedPause(\"SUBMISSION_AMBIGUOUS\""));
    }
    @Test public void confirmationGraceIsNotTerminalTimeout() throws Exception {
        String s = source("SelfRunService.java");
        assertTrue(s.contains("SUBMISSION_CONFIRMATION_GRACE_MS = 15_000L"));
        assertFalse(s.contains("SUBMISSION_CONFIRMATION_TIMEOUT"));
        assertFalse(s.contains("maxRetryCount"));
    }
    @Test public void retryTimerIsCancelledByPauseAndPersistedByStore() throws Exception {
        String s = source("SelfRunService.java");
        String st = source("SelfRunStore.java");
        assertTrue(s.contains("handler.removeCallbacks(submissionRetryRunnable)"));
        assertTrue(st.contains("submissionRetryDueAt"));
        assertTrue(st.contains("submissionRetryKind"));
        assertTrue(st.contains("submissionRetryAttempt"));
        assertTrue(st.contains("submissionRetryReady"));
    }
    @Test public void retryAttemptCounterCannotTerminateOrOverflow() throws Exception {
        String st = source("SelfRunStore.java");
        assertTrue(st.contains("prior == Integer.MAX_VALUE ? Integer.MAX_VALUE : prior + 1"));
        assertFalse(st.contains("submissionRetryAttempt() >"));
        assertFalse(st.contains("submissionRetryAttempt() =="));
    }
    @Test public void retryRechecksBeforeReclick() throws Exception {
        String d = source("SelfRunDom.java");
        String method = d.substring(d.indexOf("static String prepareDriveTurnRetry"),
                d.indexOf("/** Clicks at most once per attempt"));
        assertTrue(method.indexOf("countPrompt()>baseline") < method.indexOf("READY_TO_SUBMIT"));
    }
    @Test public void baselineIsDurableBeforeContinuationClick() throws Exception {
        String s = source("SelfRunService.java");
        String st = source("SelfRunStore.java");
        assertTrue(s.contains("store.markSubmissionStarted(beforeCount)"));
        assertTrue(st.contains(".putInt(\"submissionBaselineCount\", beforeCount)"));
        assertTrue(s.indexOf("store.markSubmissionStarted(beforeCount)")
                < s.indexOf("SelfRunDom.clickPreparedDriveTurn"));
    }
    @Test public void rendererLossAndNetworkErrorRecoverWithoutEndingJob() throws Exception {
        String s = source("SelfRunService.java");
        assertTrue(s.contains("onRenderProcessGone"));
        assertTrue(s.contains("postWebCallback(SelfRunService.this::ensureWebView, 2_000L)"));
        assertTrue(s.contains("v.loadUrl(canonicalUrl())"));
    }
    @Test public void sslErrorCancelsConnectionAndRetriesWithoutPause() throws Exception {
        String s = source("SelfRunService.java");
        assertTrue(s.contains("WEBVIEW_SSL_RETRY"));
        assertTrue(s.contains("postWebCallback(SelfRunService.this::restoreCanonical, SUBMISSION_RETRY_MS)"));
        assertFalse(s.contains("pauseError(\"WEBVIEW_SSL\""));
    }
    @Test public void driveTransientBackoffIsDelayCappedNotRetryCountCapped() throws Exception {
        String s = source("SelfRunService.java");
        assertTrue(s.contains("retryAttempt == Integer.MAX_VALUE ? Integer.MAX_VALUE : retryAttempt + 1"));
        assertTrue(s.contains("Math.min(Math.max(0, retryAttempt - 1), BACKOFF.length - 1)"));
        assertFalse(s.contains("retryAttempt >= BACKOFF.length"));
    }
    @Test public void manualPausePreservesRetryState() throws Exception {
        String st = source("SelfRunStore.java");
        String pause = st.substring(st.indexOf("void enterPause("), st.indexOf("void leavePause("));
        assertFalse(pause.contains("submissionRetryDueAt"));
    }
    @Test public void resumeRestoresPriorPhaseThenRetrySchedulerWins() throws Exception {
        String s = source("SelfRunService.java");
        assertTrue(s.contains("if (store.hasSubmissionRetry())"));
        assertTrue(s.contains("scheduleOrResumeSubmissionRetry();"));
        assertTrue(s.contains("store.leavePause(next)"));
    }
    @Test public void terminalPauseAndUserActionArePauseNotDone() throws Exception {
        String st = source("SelfRunStore.java");
        String terminal = st.substring(st.indexOf("void consumeTerminal"), st.indexOf("void resumeTerminalWithContinuation"));
        assertTrue(terminal.contains("case PAUSE"));
        assertTrue(terminal.contains("case USER_ACTION"));
        assertTrue(terminal.contains(".putBoolean(\"paused\", true)"));
    }
    @Test public void duplicateDriveCommitCannotScheduleAnotherContinuation() throws Exception {
        String s = source("SelfRunService.java");
        String st = source("SelfRunStore.java");
        assertTrue(s.contains("boolean restoring = commit.id().equals(store.pendingCommitId())"));
        assertTrue(st.contains("lastConsumedEventSeq"));
        assertTrue(st.contains("expectedTurn"));
    }
    @Test public void successImmediatelyReturnsToDriveWait() throws Exception {
        String s = source("SelfRunService.java");
        String st = source("SelfRunStore.java");
        assertTrue(s.contains("finalizeConfirmedContinuation()"));
        assertTrue(st.contains(".putString(\"phase\", PHASE_WAIT_DRIVE_COMMIT)"));
        assertTrue(s.contains("scheduleDrivePoll()"));
    }
    @Test public void noAssistantStateAppearsInContinuationSubmissionScripts() throws Exception {
        String d = source("SelfRunDom.java");
        String part = d.substring(d.indexOf("static String prepareDriveTurn"), d.indexOf("static String observeAssistant"));
        assertFalse(part.contains("stop-button"));
        assertFalse(part.contains("aria-busy"));
        assertFalse(part.contains("data-is-streaming"));
    }
    @Test public void bootstrapDoesNotExposeInternalDriveMetadata() throws Exception {
        String p = source("SelfRunProtocol.java");
        String b = p.substring(p.indexOf("static String bootstrapDrive"), p.indexOf("static String continuation"));
        assertTrue(b.contains("SELF_RUN_CLIENT=DRIVE_V1"));
        assertTrue(b.contains("DRIVE_TURN_DOCUMENT_ID="));
        assertFalse(b.contains("ANDROID_APPLICATION_ID"));
        assertFalse(b.contains("DRIVE_PROTOCOL_VERSION"));
        assertFalse(b.contains("DRIVE_RUNS_BASE_FOLDER_ID"));
        assertFalse(b.contains("DRIVE_JOB_FOLDER_ID"));
        assertFalse(b.contains("DRIVE_TURN_DOCUMENT_URL"));
        assertFalse(b.contains("DRIVE_EXPECTED_TURN"));
    }
    @Test public void noSubmissionFailurePathStopsRuntime() throws Exception {
        String s = source("SelfRunService.java");
        String e = s.substring(s.indexOf("private void evaluate("), s.indexOf("private void handleWebResult"));
        assertFalse(e.contains("stopRuntime()"));
        assertFalse(e.contains("SUBMISSION_CONFIRMATION_TIMEOUT"));
        assertTrue(e.contains("scheduleSubmissionRetry"));
    }
}
''',encoding='utf-8')

# Repository protocol documentation.
Path('docs/SELF_RUN_DRIVE_V1_PROTOCOL.md').write_text(r'''# SelfRun orchestration: legacy WebView + Drive V1

이 문서는 Drive V1 차이 규격이다. SelfRun의 작업 해석, HANDOFF, NEXT/DONE/PAUSE/USER_ACTION_REQUIRED 의미와 역할 전환은 기존 공통 SelfRun 운영 규칙을 그대로 사용한다. Drive 버전용 SelfRun 규칙 전체를 복제하지 않는다.

## Drive V1 판별

ChatGPT에 전달되는 bootstrap에서 다음 두 정보만으로 Drive V1을 판별한다.

```text
SELF_RUN_CLIENT=DRIVE_V1
DRIVE_TURN_DOCUMENT_ID=<documentId>
```

Android application ID, Runs 기준 폴더 ID, Job 폴더 ID, 문서 URL, Drive protocol version, expected turn과 앱 내부 상태관리 값은 ChatGPT bootstrap에 전달하지 않는다. 이 값들은 필요한 경우 Android 앱 내부 Drive API 상태로만 유지한다.

실제 첫 프롬프트 형식은 다음과 같다.

```text
[SELF_RUN_BOOTSTRAP 0.1.0 <RUN_ID> MODE=<MODE>]
SELF_RUN_CLIENT=DRIVE_V1
DRIVE_TURN_DOCUMENT_ID=<documentId>
최종 답변 출력 직전에 위 작업문서에 이 턴의 SelfRun 완료 신호와 완료 시점을 기록한다.

<사용자의 실제 작업 지시>
```

## Drive V1의 유일한 실행 차이

기존 WebView SelfRun은 assistant 답변 완료 여부를 WebView에서 판정한다. Drive V1은 그 완료 판정만 Google Drive 작업문서의 새 완료 기록으로 대체한다.

현재 턴의 실제 작업과 검증을 마친 뒤 최종 답변을 출력하기 직전에 지정된 `DRIVE_TURN_DOCUMENT_ID` 문서에 해당 턴의 SelfRun 완료 신호와 완료 시점을 기록하고 readback한다. 그 밖의 SelfRun 제어 의미는 공통 운영 규칙을 따른다.

Android 쪽 정상 루프는 다음과 같다.

```text
프롬프트 제출
→ 제출 성공만 확인
→ Drive 작업문서 업데이트 대기
→ 새 완료 기록 수락
→ 45초 UI 안정 대기
→ 같은 conversation 입력창 확보
→ [SELF_RUN_CONTINUE <RUN_ID>] 강제 입력·제출
→ 제출 성공만 확인
→ Drive 작업문서 업데이트 대기
```

45초 지연은 assistant completion을 재확인하기 위한 시간이 아니다. Drive 완료 기록이 authoritative completion signal이다. Drive 완료 기록 이후 stop 버튼, streaming, assistant message completion, generation 상태를 다음 제출 조건으로 사용하지 않는다.

## 제출 성공과 중복 방지

continuation은 클릭 전에 해당 conversation에서 동일한 CONTINUE 사용자 턴 수를 Android 영속 상태와 WebView marker에 baseline으로 저장한다. 제출 성공은 baseline 이후 동일 사용자 턴 수가 실제 증가했는지 확인한다. assistant DOM은 확인하지 않는다.

결과가 즉시 명확하지 않으면 같은 신호를 바로 다시 보내지 않는다. 5분 대기 상태를 영속하고, 5분 뒤 먼저 기존 제출이 늦게 성공했는지 같은 baseline으로 확인한다. 이미 성공했으면 재전송하지 않고 Drive 대기로 복귀한다. 아직 미제출이면 입력창을 다시 확보하고 동일 신호를 다시 준비·제출한다.

제출 실패·미확인은 terminal error가 아니다. 5분 재시도 횟수에 상한을 두지 않는다. 시도 횟수는 관찰용으로만 기록하며 종료 조건으로 사용하지 않는다.

## WebView 제어권 복구

Drive V1에서 WebView가 필요한 이유는 assistant completion 감시가 아니라 동일 conversation의 프롬프트 입력·제출 제어권 확보이다. 기존 WebView가 유효하면 그대로 쓰고, 입력창을 찾지 못하거나 renderer/WebView가 소실되면 저장된 conversation URL을 다시 열어 입력창을 재획득한다. 네트워크·WebView의 복구 가능한 오류는 Job 종료 사유로 승격하지 않는다.

## 일시정지와 재개

`[SELF_RUN_PAUSE ...]`, `[SELF_RUN_USER_ACTION_REQUIRED ...]`, 사용자 수동 일시정지는 Job 종료가 아닌 일시정지다. 일시정지 동안 45초 continuation 예약과 5분 제출 재시도 timer는 실행하지 않지만 pending Drive event, 제출 baseline, retry 종류·예정 시각·시도 수와 conversation/document 식별자는 보존한다.

재개 시 기존 WebView 또는 저장된 conversation URL로 입력 제어권을 확보한다. pending 제출이 있으면 먼저 기존 성공 여부를 확인하고 필요할 때만 동일 신호를 제출한다. 재개 후 제출 실패도 다시 5분 재시도 상태로 돌아간다.

## Drive 완료 기록 형식

ChatGPT 실행 측이 최종 답변 직전에 쓰는 완료 기록 형식과 SIGNAL/HANDOFF 문법은 공식 `SELF_RUN_ORCHESTRATION_SKILL`을 따른다. Android 앱은 완전한 새 기록만 수락하고 이미 소비한 event sequence/turn을 다시 소비하지 않는다. `DONE`은 정상 종료이며 `PAUSE`와 `USER_ACTION_REQUIRED`는 자동 continuation을 보내지 않고 일시정지한다.

## 생성 단계

Job 폴더와 작업문서의 생성·Drive 인증·parent 검증·초기 readback은 Android 앱의 기존 Drive setup 책임이다. 이번 dev3 턴 진행 변경은 해당 생성 구조를 재설계하지 않는다.
''',encoding='utf-8')

# Static policy checks.
p=Path('tools/verify_drive_variant.sh')
v=p.read_text(encoding='utf-8')
v=v.replace("grep -Fq 'CONTINUATION_GUARD_MS = 120_000L' \"$SERVICE\"",
            "grep -Fq 'CONTINUATION_GUARD_MS = 45_000L' \"$SERVICE\"")
v=v.replace("grep -Fq 'store.markSubmissionStarted()' \"$SERVICE\"",
            "grep -Fq 'store.markSubmissionStarted(beforeCount)' \"$SERVICE\"")
anchor="grep -Fq 'checkDriveTurnSubmitted' \"$SERVICE\"\n"
if anchor not in v: raise SystemExit('verify script anchor missing')
v=v.replace(anchor,anchor+
    "grep -Fq 'SUBMISSION_RETRY_MS = 5 * 60_000L' \"$SERVICE\"\n"
    "grep -Fq 'submissionRetryRunnable' \"$SERVICE\"\n"
    "! grep -Fq 'SUBMISSION_CONFIRMATION_TIMEOUT' \"$SERVICE\"\n"
    "! grep -Fq 'enterPreservedPause(\"SUBMISSION_AMBIGUOUS\"' \"$SERVICE\"\n")
anchor="grep -Fq 'SELF_RUN_CLIENT=DRIVE_V1' \"$PROTOCOL\"\n"
v=v.replace(anchor,anchor+
    "grep -Fq 'DRIVE_TURN_DOCUMENT_ID=' \"$PROTOCOL\"\n"
    "! grep -Fq 'ANDROID_APPLICATION_ID=' \"$PROTOCOL\"\n"
    "! grep -Fq 'DRIVE_PROTOCOL_VERSION=' \"$PROTOCOL\"\n"
    "! grep -Fq 'DRIVE_RUNS_BASE_FOLDER_ID=' \"$PROTOCOL\"\n"
    "! grep -Fq 'DRIVE_JOB_FOLDER_ID=' \"$PROTOCOL\"\n"
    "! grep -Fq 'DRIVE_TURN_DOCUMENT_URL=' \"$PROTOCOL\"\n")
p.write_text(v,encoding='utf-8')

# Final source assertions.
service_text=service.read_text(encoding='utf-8')
for token in (
    'CONTINUATION_GUARD_MS = 120_000L',
    'SUBMISSION_CONFIRMATION_TIMEOUT',
    'enterPreservedPause("SUBMISSION_AMBIGUOUS"',
    'pauseError("WEBVIEW_SSL"',
):
    if token in service_text: raise SystemExit(f'forbidden service policy remains: {token}')
protocol=Path('app/src/main/java/com/shaterguy/chatgptselfrun/SelfRunProtocol.java').read_text(encoding='utf-8')
for token in ('ANDROID_APPLICATION_ID=', 'DRIVE_PROTOCOL_VERSION=', 'DRIVE_RUNS_BASE_FOLDER_ID=',
              'DRIVE_JOB_FOLDER_ID=', 'DRIVE_TURN_DOCUMENT_URL=', 'DRIVE_EXPECTED_TURN='):
    if token in protocol: raise SystemExit(f'bootstrap still exposes {token}')
