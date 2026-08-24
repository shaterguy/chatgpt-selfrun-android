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
    private TextView statusPill;
    private TextView currentStatus;
    private TextView runMeta;
    private TextView technicalDetails;
    private TextView nextInputStatus;
    private EditText nextInputEditor;
    private Button nextInputSaveButton;
    private Button nextInputDeleteButton;
    private Button pauseButton;
    private Button resumeButton;
    private Button stopButton;
    private Button currentLogsButton;

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

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout page = Ui.page(this);
        scroll.addView(page);

        Button newRun = Ui.button(this, "새 작업", v -> openNewRun());
        page.addView(Ui.topBar(this, "SelfRun Drive", "v" + BuildConfig.VERSION_NAME + " · Run Console", newRun));

        emptyStage = Ui.heroSurface(this,
                Ui.statusPill(this, "READY"),
                Ui.headline(this, "현재 실행 중인 SelfRun이 없습니다"),
                Ui.body(this, "새 작업을 시작하거나 지난 작업을 확인할 수 있습니다."),
                Ui.actionStrip(this,
                        Ui.button(this, "새 SelfRun 시작", v -> openNewRun()),
                        Ui.outlinedButton(this, "작업 이력", v -> startActivity(new Intent(this, SelfRunHistoryActivity.class)))));
        LinearLayout.LayoutParams emptyParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        emptyParams.topMargin = Ui.dp(this, 10);
        page.addView(emptyStage, emptyParams);

        statusPill = Ui.statusPill(this, "RUN");
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
        page.addView(runStage, stageParams);

        console.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        nextInputStatus = Ui.muted(this, "");
        nextInputEditor = new EditText(this);
        nextInputEditor.setHint("다음 턴에 추가할 사용자 입력");
        nextInputEditor.setMinLines(2);
        nextInputEditor.setMaxLines(7);
        nextInputEditor.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        nextInputSaveButton = Ui.button(this, "저장", v -> saveNextInput());
        nextInputDeleteButton = Ui.textButton(this, "예약 삭제", v -> deleteNextInput());
        composerPanel = Ui.card(this,
                Ui.section(this, "NEXT TURN"),
                nextInputStatus,
                nextInputEditor,
                Ui.actionStrip(this, nextInputDeleteButton, nextInputSaveButton));
        LinearLayout.LayoutParams composerParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        int side = Ui.isMedium(this) ? Ui.dp(this, 28) : Ui.dp(this, 18);
        composerParams.setMargins(side, Ui.dp(this, 4), side, Ui.dp(this, 10));
        console.addView(composerPanel, composerParams);

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
            refreshNextInput("");
            return;
        }

        emptyStage.setVisibility(View.GONE);
        runStage.setVisibility(View.VISIBLE);
        composerPanel.setVisibility(View.VISIBLE);

        String prefs = SelfRunStore.MODE_WORK.equals(store.mode())
                ? store.pendingModel() + " / " + store.pendingReasoning()
                : ChatReasoningPreferenceStore.summary(this, runId, store.phase(), store.lastErrorCode());
        boolean paused = store.paused() && !store.userStopped();
        boolean terminal = store.userStopped()
                || SelfRunStore.PHASE_DONE.equals(store.phase())
                || SelfRunStore.PHASE_IDLE.equals(store.phase());
        boolean running = store.active() && !paused && !terminal;

        statusPill.setText(paused ? "PAUSED" : terminal ? "FINISHED" : running ? "RUNNING" : "STATE");
        currentStatus.setText(store.status());
        String meta = store.mode() + " · " + prefs + " · Turn " + store.turn()
                + "\n현재 단계  " + dash(store.phase());
        if (!store.lastErrorCode().isEmpty()) {
            meta += "\n오류  " + errorSummary();
        }
        runMeta.setText(meta);
        technicalDetails.setText("Run ID  " + runId
                + "\nconversation  " + dash(store.conversationUrl())
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

    private void refreshNextInput(String runId) {
        if (nextInputEditor == null) return;
        boolean editable = !runId.isEmpty() && UserNextInputStore.editable(runId);
        String stored = runId.isEmpty() ? "" : UserNextInputStore.current(runId);
        boolean locked = !runId.isEmpty() && UserNextInputStore.submissionLocked(runId);
        if (!nextInputEditor.hasFocus()) nextInputEditor.setText(stored);
        nextInputEditor.setEnabled(editable);
        nextInputSaveButton.setEnabled(editable);
        nextInputDeleteButton.setEnabled(editable && !stored.isEmpty());
        nextInputDeleteButton.setVisibility(stored.isEmpty() ? View.GONE : View.VISIBLE);

        if (runId.isEmpty()) {
            nextInputStatus.setText("");
        } else if (editable && stored.isEmpty()) {
            nextInputStatus.setText("제출이 시작되기 전까지 다음 턴 입력을 예약할 수 있습니다.");
        } else if (editable) {
            nextInputStatus.setText("다음 턴에 예약됨 · 실제 제출 시작 전까지 수정할 수 있습니다.");
        } else if (locked) {
            nextInputStatus.setText("다음 턴 제출이 시작되어 현재 예약 입력은 잠겼습니다.");
        } else {
            nextInputStatus.setText("현재 단계에서는 다음 턴 입력을 예약할 수 없습니다.");
        }
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
