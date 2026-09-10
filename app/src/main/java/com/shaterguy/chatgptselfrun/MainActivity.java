package com.shaterguy.chatgptselfrun;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
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

/** SelfRun 3.1 task console. Current-conversation immediate input is intentionally absent. */
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
    private View emptyStage, runStage, composerPanel, supportingPane;
    private TextView jobTitle, currentStatus, runMeta, technicalDetails, nextInputStatus;
    private EditText nextInputEditor;
    private Button newRunButton, nextInputSaveButton, nextInputDeleteButton;
    private Button pauseButton, resumeButton, stopButton, currentConversationButton, currentLogsButton;
    private LinearLayout runControlSlot;
    private String lastNextInputRunId = "", lastNextInputStored = "";

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        store = new SelfRunStore(this);
        history = new SelfRunHistoryStore(this);
        runLog = new SelfRunRunLog(this);
        NotificationHelper.ensureChannel(this);
        createViews();
    }

    @Override protected void onResume() {
        super.onResume();
        history.sync(store);
        refreshHandler.removeCallbacks(refreshRunnable);
        refreshCurrent();
        refreshHandler.postDelayed(refreshRunnable, 1000L);
    }

    @Override protected void onPause() {
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
        newRunButton = Ui.textButton(this, "새 작업", v -> openNewRun());
        runPage.addView(Ui.topBar(this, "SelfRun", "", newRunButton));
        emptyStage = Ui.card(this, Ui.headline(this, "새 작업을 시작하세요"),
                Ui.button(this, "새 작업", v -> openNewRun()));
        runPage.addView(emptyStage);

        jobTitle = Ui.headline(this, "");
        jobTitle.setMaxLines(2);
        jobTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        currentStatus = Ui.body(this, "");
        currentStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        runMeta = Ui.muted(this, "");
        technicalDetails = Ui.muted(this, "");
        technicalDetails.setVisibility(View.GONE);
        technicalDetails.setTextIsSelectable(true);
        pauseButton = Ui.button(this, "일시정지", v -> pauseSelfRun());
        resumeButton = Ui.button(this, "재개", v -> resumeSelfRun());
        currentConversationButton = Ui.outlinedButton(this, "현재 대화", v -> openCurrentConversation());
        currentConversationButton.setEnabled(false);
        stopButton = Ui.dangerButton(this, "중지", v -> stopSelfRun());
        runControlSlot = new LinearLayout(this);
        runControlSlot.setOrientation(LinearLayout.HORIZONTAL);
        runControlSlot.setGravity(android.view.Gravity.CENTER_VERTICAL);
        runControlSlot.addView(pauseButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        runControlSlot.addView(resumeButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams conversationButtonParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        conversationButtonParams.setMarginStart(Ui.dp(this, 8));
        runControlSlot.addView(currentConversationButton, conversationButtonParams);
        currentLogsButton = Ui.textButton(this, "대화 이력·로그", v -> {
            if (!store.runId().isEmpty()) startActivity(new Intent(this, SelfRunDetailActivity.class)
                    .putExtra(SelfRunDetailActivity.EXTRA_RUN_ID, store.runId()));
        });
        Button detailsButton = Ui.textButton(this, "실행 정보", v -> technicalDetails.setVisibility(
                technicalDetails.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        runStage = Ui.card(this, jobTitle, currentStatus, runMeta,
                Ui.actionStrip(this, runControlSlot, stopButton),
                Ui.divider(this), Ui.actionStrip(this, currentLogsButton, detailsButton), technicalDetails);
        runPage.addView(runStage);

        nextInputStatus = Ui.muted(this, "");
        nextInputEditor = new EditText(this);
        nextInputEditor.setHint("지시를 입력하세요");
        nextInputEditor.setMinLines(Ui.isExpanded(this) ? 6 : 2);
        nextInputEditor.setMaxLines(Ui.isExpanded(this) ? 14 : 7);
        nextInputEditor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        nextInputSaveButton = Ui.tonalButton(this, "다음 실행 예약", v -> saveNextInput());
        nextInputDeleteButton = Ui.textButton(this, "예약 삭제", v -> deleteNextInput());
        composerPanel = Ui.card(this, Ui.section(this, "추가 지시"), nextInputStatus, nextInputEditor,
                Ui.actionStrip(this, nextInputSaveButton), nextInputDeleteButton);

        if (Ui.isExpanded(this)) {
            ScrollView paneScroll = new ScrollView(this);
            LinearLayout pane = Ui.page(this);
            pane.addView(composerPanel);
            paneScroll.addView(pane);
            supportingPane = paneScroll;
            console.addView(Ui.twoPane(this, runScroll, paneScroll),
                    new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        } else {
            LinearLayout.LayoutParams composerParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            composerParams.topMargin = Ui.dp(this, 16);
            runPage.addView(composerPanel, composerParams);
            console.addView(runScroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        }
        Ui.setPrimaryContent(this, console, Ui.DEST_RUN);
        console.requestFocus();
    }

    private void refreshCurrent() {
        if (currentStatus == null) return;
        String runId = store.runId();
        if (runId.isEmpty()) {
            emptyStage.setVisibility(View.VISIBLE);
            newRunButton.setVisibility(View.GONE);
            runStage.setVisibility(View.GONE);
            composerPanel.setVisibility(View.GONE);
            if (supportingPane != null) supportingPane.setVisibility(View.GONE);
            refreshNextInput("");
            return;
        }

        emptyStage.setVisibility(View.GONE);
        newRunButton.setVisibility(View.VISIBLE);
        runStage.setVisibility(View.VISIBLE);
        boolean paused = store.paused() && !store.userStopped();
        boolean terminal = store.userStopped() || SelfRunStore.PHASE_DONE.equals(store.phase())
                || SelfRunStore.PHASE_IDLE.equals(store.phase());
        boolean running = store.active() && !paused && !terminal;
        composerPanel.setVisibility(terminal ? View.GONE : View.VISIBLE);
        if (supportingPane != null) supportingPane.setVisibility(terminal ? View.GONE : View.VISIBLE);

        jobTitle.setText(store.requirement().trim().isEmpty() ? "현재 작업" : store.requirement());
        currentStatus.setText(displayStatus(paused, terminal));
        String profile = SelfRunStore.MODE_WORK.equals(store.mode())
                ? workProfileLabel() : ChatReasoningPreferenceStore.summary(this, runId, store.phase(), store.lastErrorCode());
        String taskMode = store.taskMode();
        String actualMode = SelfRunStore.MODE_WORK.equals(store.mode()) ? "워크" : "일반 채팅";
        String meta = (SelfRunStore.MODE_HYBRID.equals(taskMode) ? "하이브리드 · " : "")
                + actualMode + " · " + profile + " · " + store.turn() + "턴";
        if (!store.lastErrorCode().isEmpty()) meta += "\n오류  " + errorSummary();
        runMeta.setText(meta);
        technicalDetails.setText("Run ID  " + runId
                + "\nTask mode  " + dash(taskMode)
                + "\n현재 실행 모드  " + dash(store.mode())
                + "\nconversation  " + dash(store.conversationUrl())
                + "\n모델 / 추론  " + dash(store.pendingModel()) + " / " + dash(store.pendingReasoning())
                + "\n내부 phase  " + dash(store.phase())
                + "\nRun 폴더  " + dash(store.runBaseFolderId())
                + "\n마지막 오류  " + errorSummary());

        String conversationUrl = SelfRunDetailActivity.canonicalConversationUrl(store.conversationUrl());
        pauseButton.setVisibility(running ? View.VISIBLE : View.GONE);
        resumeButton.setVisibility(paused ? View.VISIBLE : View.GONE);
        stopButton.setVisibility(running || paused ? View.VISIBLE : View.GONE);
        pauseButton.setEnabled(running);
        resumeButton.setEnabled(paused);
        stopButton.setEnabled(running || paused);
        currentConversationButton.setEnabled(!conversationUrl.isEmpty());
        currentLogsButton.setVisibility(View.VISIBLE);
        refreshNextInput(runId);
    }

    private String workProfileLabel() {
        ProfileRegistry.Profile profile = ProfileRegistry.resolveWork(store.pendingModel(), store.pendingReasoning());
        return profile == null ? dash(store.pendingModel()) + " / " + dash(store.pendingReasoning()) : profile.displayLabel();
    }

    private String displayStatus(boolean paused, boolean terminal) {
        if (store.userStopped()) return "사용자 중지";
        if (paused) return store.lastErrorCode().isEmpty() ? "일시정지 · 상태 보존" : "일시정지 · 오류 확인 필요";
        if (SelfRunStore.PHASE_DONE.equals(store.phase())) return "작업 완료";
        if (SelfRunStore.PHASE_IDLE.equals(store.phase())) return "실행 종료";
        if (!store.lastErrorCode().isEmpty()) return "오류 확인 필요";
        String status = store.status();
        return status == null || status.isEmpty() ? (terminal ? "실행 종료" : "실행 중") : status;
    }

    private void refreshNextInput(String runId) {
        if (nextInputEditor == null) return;
        boolean editable = !runId.isEmpty() && UserNextInputStore.editable(runId);
        String stored = runId.isEmpty() ? "" : UserNextInputStore.current(runId);
        boolean locked = !runId.isEmpty() && UserNextInputStore.submissionLocked(runId);
        boolean unavailable = !runId.isEmpty() && !editable && !locked;
        boolean runChanged = !runId.equals(lastNextInputRunId);
        boolean consumed = runId.equals(lastNextInputRunId) && !lastNextInputStored.isEmpty() && stored.isEmpty();
        if (runChanged || !nextInputEditor.hasFocus() || consumed) nextInputEditor.setText(stored);
        lastNextInputRunId = runId;
        lastNextInputStored = stored;
        nextInputEditor.setEnabled(editable);
        nextInputEditor.setVisibility(unavailable ? View.GONE : View.VISIBLE);
        nextInputSaveButton.setEnabled(editable);
        nextInputSaveButton.setVisibility(unavailable ? View.GONE : View.VISIBLE);
        nextInputDeleteButton.setEnabled(editable && !stored.isEmpty());
        nextInputDeleteButton.setVisibility(!unavailable && !stored.isEmpty() ? View.VISIBLE : View.GONE);
        if (runId.isEmpty() || unavailable) nextInputStatus.setText("");
        else if (locked) nextInputStatus.setText("전송 준비 중 · 수정할 수 없음");
        else if (stored.isEmpty()) nextInputStatus.setText("");
        else nextInputStatus.setText("다음 새 대화에 반영됩니다.");
    }

    private void saveNextInput() {
        String runId = store.runId();
        if (!UserNextInputStore.save(runId, nextInputEditor.getText().toString())) {
            Toast.makeText(this, "현재 다음 실행 입력을 저장할 수 없습니다.", Toast.LENGTH_SHORT).show();
            refreshCurrent();
            return;
        }
        nextInputEditor.clearFocus();
        Toast.makeText(this, "다음 실행 입력을 저장했습니다.", Toast.LENGTH_SHORT).show();
        refreshCurrent();
    }

    private void deleteNextInput() {
        String runId = store.runId();
        if (!UserNextInputStore.delete(runId)) {
            Toast.makeText(this, "현재 예약 입력을 삭제할 수 없습니다.", Toast.LENGTH_SHORT).show();
            refreshCurrent();
            return;
        }
        nextInputEditor.setText("");
        nextInputEditor.clearFocus();
        Toast.makeText(this, "다음 실행 입력을 삭제했습니다.", Toast.LENGTH_SHORT).show();
        refreshCurrent();
    }

    private String errorSummary() {
        return store.lastErrorCode().isEmpty() ? "없음" : store.lastErrorCode() + " · " + store.lastErrorMessage();
    }

    private void pauseSelfRun() {
        if (store.active() && !store.paused() && !store.userStopped() && !store.runId().isEmpty())
            sendRunnerAction(SelfRunService.ACTION_PAUSE);
    }

    private void resumeSelfRun() {
        if (store.paused() && !store.userStopped() && !store.runId().isEmpty())
            sendRunnerAction(SelfRunService.ACTION_RESUME);
    }

    private void stopSelfRun() {
        if (store.runId().isEmpty()) return;
        store.stopByUser();
        runLog.record(store, "UI_STOP", "user_stop");
        stopService(new Intent(this, SelfRunService.class));
        refreshCurrent();
    }

    private void openCurrentConversation() {
        String url = SelfRunDetailActivity.canonicalConversationUrl(store.conversationUrl());
        if (url.isEmpty()) {
            currentConversationButton.setEnabled(false);
            return;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException error) {
            Toast.makeText(this, "대화를 열 수 있는 앱이나 브라우저가 없습니다.", Toast.LENGTH_LONG).show();
        }
    }

    private void openNewRun() { startActivity(new Intent(this, SelfRunNewActivity.class)); }

    private void sendRunnerAction(String action) {
        Intent intent = new Intent(this, SelfRunService.class);
        intent.setAction(action);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent); else startService(intent);
    }

    private static String dash(String value) { return value == null || value.isEmpty() ? "-" : value; }
}
