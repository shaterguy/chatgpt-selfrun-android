package com.shaterguy.chatgptselfrun;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public final class MainActivity extends Activity {
    private final Handler refreshHandler = new Handler(Looper.getMainLooper());
    private final Runnable refreshRunnable = new Runnable() {
        @Override public void run() {
            refreshCurrent();
            refreshHandler.postDelayed(this, 1000L);
        }
    };

    private SelfRunStore store;
    private SelfRunHistoryStore history;
    private SelfRunRunLog runLog;

    private View emptyStage;
    private View runStage;
    private View composerPanel;
    private View supportingPane;
    private TextView statusPill;
    private TextView currentStatus;
    private TextView runMeta;
    private TextView technicalDetails;
    private TextView nextInputStatus;
    private EditText nextInputEditor;
    private Button immediateInputButton;
    private Button nextInputSaveButton;
    private Button nextInputDeleteButton;
    private Button pauseButton;
    private Button resumeButton;
    private Button stopButton;
    private Button currentLogsButton;
    private String lastNextInputRunId = "";
    private String lastNextInputStored = "";
    private boolean immediateInputInFlight;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        store = new SelfRunStore(this);
        history = new SelfRunHistoryStore(this);
        runLog = new SelfRunRunLog(this);
        NotificationHelper.ensureChannel(this);
        createViews();
    }

    @Override
    protected void onResume() {
        super.onResume();
        history.sync(store);
        refreshHandler.removeCallbacks(refreshRunnable);
        refreshCurrent();
        refreshHandler.postDelayed(refreshRunnable, 1000L);
    }

    @Override
    protected void onPause() {
        refreshHandler.removeCallbacks(refreshRunnable);
        super.onPause();
    }

    private void createViews() {
        LinearLayout console = new LinearLayout(this);
        console.setOrientation(LinearLayout.VERTICAL);
        console.setFocusableInTouchMode(true);

        ScrollView runScroll = new ScrollView(this);
        runScroll.setFillViewport(true);
        LinearLayout runPage = Ui.page(this);
        runScroll.addView(runPage);

        Button newRun = Ui.button(this, "새 작업", v -> openNewRun());
        runPage.addView(Ui.topBar(this, "SelfRun Drive", "v" + BuildConfig.VERSION_NAME + " · Run Console", newRun));

        emptyStage = Ui.heroSurface(this,
                Ui.statusPill(this, "대기"),
                Ui.headline(this, "현재 실행 중인 SelfRun이 없습니다"),
                Ui.body(this, "새 작업을 시작하거나 지난 작업을 확인할 수 있습니다."),
                Ui.actionStrip(this,
                        Ui.button(this, "새 SelfRun 시작", v -> openNewRun()),
                        Ui.outlinedButton(this, "작업 이력", v -> startActivity(new Intent(this, SelfRunHistoryActivity.class)))));
        LinearLayout.LayoutParams emptyParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        emptyParams.topMargin = Ui.dp(this, 10);
        runPage.addView(emptyStage, emptyParams);

        statusPill = Ui.statusPill(this, "실행");
        currentStatus = Ui.headline(this, "");
        runMeta = Ui.body(this, "");
        technicalDetails = Ui.muted(this, "");
        technicalDetails.setVisibility(View.GONE);
        technicalDetails.setTextIsSelectable(true);

        pauseButton = Ui.button(this, "일시정지", v -> pauseSelfRun());
        resumeButton = Ui.button(this, "재개", v -> resumeSelfRun());
        stopButton = Ui.dangerButton(this, "중지", v -> stopSelfRun());
        currentLogsButton = Ui.outlinedButton(this, "로그", v -> openCurrentLogs());
        Button detailsButton = Ui.textButton(this, "실행 정보", v -> {
            technicalDetails.setVisibility(technicalDetails.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
        });

        runStage = Ui.heroSurface(this,
                statusPill,
                currentStatus,
                runMeta,
                Ui.actionStrip(this, pauseButton, resumeButton, stopButton, currentLogsButton),
                detailsButton,
                technicalDetails);
        LinearLayout.LayoutParams stageParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stageParams.topMargin = Ui.dp(this, 10);
        runPage.addView(runStage, stageParams);

        nextInputStatus = Ui.muted(this, "");
        nextInputEditor = new EditText(this);
        nextInputEditor.setHint("다음 턴에 추가하거나 즉시 강제입력할 사용자 입력");
        nextInputEditor.setMinLines(Ui.isExpanded(this) ? 6 : 2);
        nextInputEditor.setMaxLines(Ui.isExpanded(this) ? 14 : 7);
        nextInputEditor.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        immediateInputButton = Ui.outlinedButton(this, "즉시 강제입력", v -> forceImmediateInput());
        nextInputSaveButton = Ui.button(this, "차기턴 저장", v -> saveNextInput());
        nextInputDeleteButton = Ui.textButton(this, "예약 삭제", v -> deleteNextInput());
        composerPanel = Ui.card(this,
                Ui.section(this, "USER INPUT"),
                Ui.headline(this, "차기턴 예약 · 즉시 강제입력"),
                nextInputStatus,
                nextInputEditor,
                Ui.actionStrip(this, nextInputDeleteButton, immediateInputButton, nextInputSaveButton));

        if (Ui.isExpanded(this)) {
            LinearLayout workspace = new LinearLayout(this);
            workspace.setOrientation(LinearLayout.HORIZONTAL);
            workspace.addView(runScroll, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, 1.15f));

            LinearLayout pane = new LinearLayout(this);
            pane.setOrientation(LinearLayout.VERTICAL);
            pane.setPadding(Ui.dp(this, 8), Ui.dp(this, 14), Ui.dp(this, 28), Ui.dp(this, 24));
            pane.addView(composerPanel, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            supportingPane = pane;
            LinearLayout.LayoutParams supportParams = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.MATCH_PARENT, 0.85f);
            supportParams.setMarginStart(Ui.dp(this, 10));
            workspace.addView(pane, supportParams);
            console.addView(workspace, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        } else {
            console.addView(runScroll, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            LinearLayout.LayoutParams composerParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            int side = Ui.isMedium(this) ? Ui.dp(this, 28) : Ui.dp(this, 18);
            composerParams.setMargins(side, Ui.dp(this, 4), side, Ui.dp(this, 10));
            console.addView(composerPanel, composerParams);
        }

        Ui.setPrimaryContent(this, console, Ui.DEST_RUN);
        console.requestFocus();
    }

    private void refreshCurrent() {
        if (currentStatus == null) return;
        String runId = store.runId();
        if (runId.isEmpty()) {
            emptyStage.setVisibility(View.VISIBLE);
            runStage.setVisibility(View.GONE);
            composerPanel.setVisibility(View.GONE);
            if (supportingPane != null) supportingPane.setVisibility(View.GONE);
            refreshNextInput("");
            return;
        }

        emptyStage.setVisibility(View.GONE);
        runStage.setVisibility(View.VISIBLE);
        composerPanel.setVisibility(View.VISIBLE);
        if (supportingPane != null) supportingPane.setVisibility(View.VISIBLE);

        String prefs = SelfRunStore.MODE_WORK.equals(store.mode())
                ? store.pendingModel() + " / " + store.pendingReasoning()
                : ChatReasoningPreferenceStore.summary(this, runId, store.phase(), store.lastErrorCode());
        boolean paused = store.paused() && !store.userStopped();
        boolean terminal = store.userStopped()
                || SelfRunStore.PHASE_DONE.equals(store.phase())
                || SelfRunStore.PHASE_IDLE.equals(store.phase());
        boolean running = store.active() && !paused && !terminal;
        TurnProtocolUiState.Snapshot protocol = TurnProtocolUiState.read(this, runId);
        String displayStatus = displayRuntimeStatus(protocol, paused, terminal);

        statusPill.setText(TurnProtocolUiState.pillFor(displayStatus));
        currentStatus.setText(displayStatus);
        String meta = store.mode() + " · " + prefs + " · SelfRun Turn " + store.turn();
        if (protocol.present) {
            meta += "\nChatGPT Turn " + protocol.sequence + " · " + protocol.phase;
        }
        if (!store.lastErrorCode().isEmpty()) {
            meta += "\n오류  " + errorSummary();
        }
        runMeta.setText(meta);
        technicalDetails.setText("Run ID  " + runId
                + "\nconversation  " + dash(store.conversationUrl())
                + "\n내부 phase  " + dash(store.phase())
                + "\n턴 프로토콜 phase  " + (protocol.present ? protocol.phase : "-")
                + "\n턴 프로토콜 event  " + (protocol.present ? protocol.stage : "-")
                + "\nDrive 문서  " + dash(store.turnDocumentUrl())
                + "\nDrive signal cursor  " + store.driveSignalCursor()
                + "\n마지막 Drive signal  " + dash(store.lastDriveSignalType())
                + "\n마지막 오류  " + errorSummary());

        pauseButton.setVisibility(running ? View.VISIBLE : View.GONE);
        resumeButton.setVisibility(paused ? View.VISIBLE : View.GONE);
        stopButton.setVisibility((running || paused) ? View.VISIBLE : View.GONE);
        currentLogsButton.setVisibility(View.VISIBLE);
        pauseButton.setEnabled(running);
        resumeButton.setEnabled(paused);
        stopButton.setEnabled(running || paused);
        currentLogsButton.setEnabled(true);
        refreshNextInput(runId);
    }

    private String displayRuntimeStatus(TurnProtocolUiState.Snapshot protocol,
                                        boolean paused, boolean terminal) {
        if (store.userStopped()) return "사용자 중지";
        if (paused) return store.lastErrorCode().isEmpty() ? "일시정지" : "일시정지 · 오류 확인 필요";
        String phase = store.phase();
        if (SelfRunStore.PHASE_DONE.equals(phase)) return "작업 완료";
        if (SelfRunStore.PHASE_IDLE.equals(phase)) return "실행 종료";
        if (!store.lastErrorCode().isEmpty()) return "오류 확인 필요";
        if (SelfRunStore.PHASE_POST_DOM_DRIVE_SYNC.equals(phase)) return "답변 완료 · 차기 턴 대기";
        if (SelfRunStore.PHASE_APPLY_PREFS.equals(phase)
                || SelfRunStore.PHASE_APPLY_REASONING.equals(phase)) return "차기 턴 설정 중";
        if (SelfRunStore.PHASE_SEND_CONTINUE.equals(phase)) return "차기 턴 전송 중";
        if (SelfRunStore.PHASE_WAIT_TURN_COMPLETION.equals(phase)) {
            String live = protocol.headline();
            return live.isEmpty() ? "응답 상태 확인 중" : live;
        }
        if (SelfRunStore.PHASE_BOOTSTRAP_SEND.equals(phase)) {
            String live = protocol.headline();
            if (!live.isEmpty()) return live;
            return "첫 턴 전송 중";
        }
        if (SelfRunStore.PHASE_BOOTSTRAP_MODEL.equals(phase)
                || SelfRunStore.PHASE_BOOTSTRAP_REASONING.equals(phase)) return "첫 턴 설정 중";
        if (SelfRunStore.PHASE_BOOTSTRAP.equals(phase)) return "ChatGPT 연결 준비 중";
        if (SelfRunStore.PHASE_RESUME_BASELINE.equals(phase)) return "재개 상태 확인 중";
        if (SelfRunStore.PHASE_DRIVE_ACCOUNT_CHECK.equals(phase)
                || SelfRunStore.PHASE_DRIVE_BASE_FOLDER_CHECK.equals(phase)
                || SelfRunStore.PHASE_JOB_ID_CREATE.equals(phase)
                || SelfRunStore.PHASE_DRIVE_JOB_FOLDER_CREATE.equals(phase)
                || SelfRunStore.PHASE_DRIVE_ATTACHMENT_UPLOAD.equals(phase)
                || SelfRunStore.PHASE_DRIVE_TURN_DOCUMENT_CREATE.equals(phase)
                || SelfRunStore.PHASE_DRIVE_DOCUMENT_INIT.equals(phase)
                || SelfRunStore.PHASE_DRIVE_DOCUMENT_READBACK.equals(phase)) return "실행 준비 중";
        return terminal ? "실행 종료" : "실행 중";
    }

    private void refreshNextInput(String runId) {
        if (nextInputEditor == null) return;
        boolean editable = !runId.isEmpty() && UserNextInputStore.editable(runId);
        String stored = runId.isEmpty() ? "" : UserNextInputStore.current(runId);
        boolean locked = !runId.isEmpty() && UserNextInputStore.submissionLocked(runId);
        boolean unavailable = !runId.isEmpty() && !editable && !locked;
        boolean runChanged = !runId.equals(lastNextInputRunId);
        boolean reservationConsumed = runId.equals(lastNextInputRunId) && !lastNextInputStored.isEmpty() && stored.isEmpty();
        if (runChanged || !nextInputEditor.hasFocus() || reservationConsumed) nextInputEditor.setText(stored);
        lastNextInputRunId = runId;
        lastNextInputStored = stored;
        nextInputEditor.setEnabled(editable);
        nextInputEditor.setVisibility(unavailable ? View.GONE : View.VISIBLE);
        immediateInputButton.setEnabled(editable && !immediateInputInFlight);
        immediateInputButton.setVisibility(unavailable ? View.GONE : View.VISIBLE);
        nextInputSaveButton.setEnabled(editable && !immediateInputInFlight);
        nextInputSaveButton.setVisibility(unavailable ? View.GONE : View.VISIBLE);
        nextInputDeleteButton.setEnabled(editable && !stored.isEmpty() && !immediateInputInFlight);
        nextInputDeleteButton.setVisibility(!unavailable && !stored.isEmpty() ? View.VISIBLE : View.GONE);

        if (runId.isEmpty()) {
            nextInputStatus.setText("");
        } else if (immediateInputInFlight) {
            nextInputStatus.setText("즉시 강제입력 가능 여부를 확인 중입니다. 전송할 수 없으면 차기턴으로 예약합니다.");
        } else if (editable && stored.isEmpty()) {
            nextInputStatus.setText("차기턴 예약 또는 현재 응답에 즉시 강제입력을 사용할 수 있습니다.");
        } else if (editable) {
            nextInputStatus.setText("다음 턴에 예약됨 · 즉시 강제입력을 선택하면 현재 입력을 먼저 시도합니다.");
        } else if (locked) {
            nextInputStatus.setText("다음 턴 제출이 시작되어 현재 예약 입력은 잠겼습니다.");
        } else {
            nextInputStatus.setText("현재 단계에서는 사용자 입력을 예약할 수 없습니다.");
        }
    }

    private void forceImmediateInput() {
        String runId = store.runId();
        String value = nextInputEditor.getText().toString();
        if (value.trim().isEmpty()) {
            Toast.makeText(this, "강제입력할 내용을 입력하세요.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!UserNextInputStore.withinUtf8Limit(value, UserNextInputStore.MAX_USER_UTF8_BYTES)) {
            Toast.makeText(this, "입력 내용이 허용 길이를 초과했습니다.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (immediateInputInFlight || runId.isEmpty()) return;
        immediateInputInFlight = true;
        nextInputEditor.clearFocus();
        refreshNextInput(runId);
        UserImmediateInputCoordinator.submit(store, runLog, value, result -> {
            if (isFinishing() || isDestroyed()) return;
            immediateInputInFlight = false;
            String message;
            if (UserImmediateInputCoordinator.OUTCOME_SENT.equals(result.outcome)) {
                message = "현재 응답에 즉시 강제입력했습니다.";
            } else if (UserImmediateInputCoordinator.OUTCOME_DEFERRED.equals(result.outcome)) {
                message = "즉시 전송할 수 없어 차기턴 입력으로 예약했습니다.";
            } else {
                message = "강제입력을 안전하게 확정하지 못했습니다. 로그를 확인하세요.";
            }
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
            refreshCurrent();
        });
    }

    private void saveNextInput() {
        String runId = store.runId();
        if (!UserNextInputStore.save(runId, nextInputEditor.getText().toString())) {
            Toast.makeText(this, "차기 턴 제출이 이미 시작되었거나 현재 입력할 수 없는 단계입니다.", Toast.LENGTH_SHORT).show();
            refreshCurrent();
            return;
        }
        nextInputEditor.clearFocus();
        Toast.makeText(this, "차기 턴 입력을 저장했습니다.", Toast.LENGTH_SHORT).show();
        refreshCurrent();
    }

    private void deleteNextInput() {
        String runId = store.runId();
        if (!UserNextInputStore.delete(runId)) {
            Toast.makeText(this, "차기 턴 제출이 이미 시작되었거나 현재 삭제할 수 없는 단계입니다.", Toast.LENGTH_SHORT).show();
            refreshCurrent();
            return;
        }
        nextInputEditor.setText("");
        nextInputEditor.clearFocus();
        Toast.makeText(this, "차기 턴 입력을 삭제했습니다.", Toast.LENGTH_SHORT).show();
        refreshCurrent();
    }

    private String errorSummary() {
        if (store.lastErrorCode().isEmpty()) return "없음";
        return store.lastErrorCode() + " · " + store.lastErrorMessage();
    }

    private void pauseSelfRun() {
        if (!store.active() || store.paused() || store.userStopped() || store.runId().isEmpty()) return;
        sendRunnerAction(SelfRunService.ACTION_PAUSE);
    }

    private void resumeSelfRun() {
        if (!store.paused() || store.userStopped() || store.runId().isEmpty()) return;
        sendRunnerAction(SelfRunService.ACTION_RESUME);
    }

    private void stopSelfRun() {
        if (store.runId().isEmpty()) return;
        store.stopByUser();
        runLog.record(store, "UI_STOP", "user_stop");
        stopService(new Intent(this, SelfRunService.class));
        refreshCurrent();
    }

    private void openCurrentLogs() {
        if (store.runId().isEmpty()) return;
        startActivity(new Intent(this, SelfRunLogsActivity.class)
                .putExtra(SelfRunLogsActivity.EXTRA_RUN_ID, store.runId())
                .putExtra(SelfRunLogsActivity.EXTRA_KIND, SelfRunLogsActivity.KIND_DEBUG));
    }

    private void openNewRun() {
        startActivity(new Intent(this, SelfRunNewActivity.class));
    }

    private void sendRunnerAction(String action) {
        Intent intent = new Intent(this, SelfRunService.class);
        intent.setAction(action);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent); else startService(intent);
    }

    private static String dash(String value) {
        return value == null || value.isEmpty() ? "-" : value;
    }
}
