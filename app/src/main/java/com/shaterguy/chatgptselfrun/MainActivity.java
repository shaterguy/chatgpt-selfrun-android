package com.shaterguy.chatgptselfrun;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.InputType;
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
            refreshBackgroundStatus();
            refreshHandler.postDelayed(this, 1000L);
        }
    };

    private SelfRunStore store;
    private SelfRunHistoryStore history;
    private SelfRunRunLog runLog;
    private TextView currentStatus;
    private TextView backgroundStatus;
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
        refreshBackgroundStatus();
        refreshHandler.postDelayed(refreshRunnable, 1000L);
    }

    @Override
    protected void onPause() {
        refreshHandler.removeCallbacks(refreshRunnable);
        super.onPause();
    }

    private void createViews() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFocusableInTouchMode(true);
        root.setPadding(Ui.dp(this, 20), Ui.dp(this, 16), Ui.dp(this, 20), Ui.dp(this, 28));
        scroll.addView(root);

        root.addView(Ui.title(this, "SelfRun Drive"));
        root.addView(Ui.subtitle(this, "v" + BuildConfig.VERSION_NAME + " · Drive 신호 기반으로 하나의 대화를 끝까지 이어갑니다."));

        root.addView(Ui.section(this, "빠른 실행"));
        root.addView(Ui.row(this,
                Ui.button(this, "새 작업", v -> startActivity(new Intent(this, SelfRunNewActivity.class))),
                Ui.outlinedButton(this, "작업 이력", v -> startActivity(new Intent(this, SelfRunHistoryActivity.class)))));
        root.addView(Ui.row(this,
                Ui.tonalButton(this, "로그", v -> startActivity(new Intent(this, SelfRunLogMenuActivity.class))),
                Ui.tonalButton(this, "로그인 · 세션", v -> startActivity(new Intent(this, LoginActivity.class)))));
        root.addView(Ui.row(this,
                Ui.tonalButton(this, "Drive 실행문서 위치", v -> startActivity(new Intent(this, DriveSetupActivity.class))),
                Ui.tonalButton(this, "웹 UI 보정", v -> startActivity(new Intent(this, WebUiCalibrationActivity.class)))));

        currentStatus = Ui.body(this, "");
        pauseButton = Ui.button(this, "일시정지", v -> pauseSelfRun());
        resumeButton = Ui.button(this, "재개", v -> resumeSelfRun());
        stopButton = Ui.dangerButton(this, "중지", v -> stopSelfRun());
        currentLogsButton = Ui.outlinedButton(this, "현재 작업 로그", v -> openCurrentLogs());
        root.addView(Ui.card(this,
                Ui.cardTitle(this, "현재 SelfRun"),
                currentStatus,
                Ui.row(this, pauseButton, resumeButton, stopButton),
                currentLogsButton));

        nextInputStatus = Ui.body(this, "");
        nextInputEditor = new EditText(this);
        nextInputEditor.setHint("차기 턴에 함께 보낼 문구");
        nextInputEditor.setMinLines(2);
        nextInputEditor.setMaxLines(8);
        nextInputEditor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        nextInputSaveButton = Ui.button(this, "저장 · 수정", v -> saveNextInput());
        nextInputDeleteButton = Ui.dangerButton(this, "삭제", v -> deleteNextInput());
        root.addView(Ui.card(this,
                Ui.cardTitle(this, "차기 턴 사용자 입력"),
                nextInputStatus,
                nextInputEditor,
                Ui.row(this, nextInputSaveButton, nextInputDeleteButton),
                Ui.muted(this, "저장한 문구는 정확히 다음 SelfRun continuation에만 적용됩니다. Drive NEXT_INPUT이 있으면 그 뒤에 함께 전달됩니다.")));

        backgroundStatus = Ui.body(this, "");
        root.addView(Ui.card(this,
                Ui.cardTitle(this, "백그라운드 실행 준비"),
                backgroundStatus,
                Ui.row(this,
                        Ui.tonalButton(this, "알림 권한", v -> requestNotificationPermission()),
                        Ui.tonalButton(this, "배터리 최적화 제외", v -> requestBatteryExemption())),
                Ui.muted(this, "Drive 대기 중에는 WakeLock을 유지하지 않습니다. Drive 요청·WebView 입력 같은 짧은 실행 구간에서만 사용합니다.")));

        Ui.setContent(this, scroll);
        root.requestFocus();
    }

    private void refreshCurrent() {
        if (currentStatus == null) return;
        String runId = store.runId();
        if (runId.isEmpty()) {
            currentStatus.setText("실행 중이거나 선택된 SelfRun이 없습니다.\n새 작업을 시작하면 현재 상태와 다음 행동이 여기에 표시됩니다.");
            pauseButton.setEnabled(false);
            resumeButton.setEnabled(false);
            stopButton.setEnabled(false);
            currentLogsButton.setEnabled(false);
            refreshNextInput("");
            return;
        }
        String prefs = SelfRunStore.MODE_WORK.equals(store.mode())
                ? store.pendingModel() + " / " + store.pendingReasoning()
                : ChatReasoningPreferenceStore.summary(this, runId, store.phase(), store.lastErrorCode());
        currentStatus.setText(store.status()
                + "\n" + store.mode() + " · " + prefs + " · Turn " + store.turn()
                + "\n단계: " + store.phase()
                + "\nRun ID: " + runId
                + "\nconversation: " + dash(store.conversationUrl())
                + "\nDrive 문서: " + dash(store.turnDocumentUrl())
                + "\nDrive signal cursor: " + store.driveSignalCursor()
                + "\n마지막 Drive signal: " + dash(store.lastDriveSignalType())
                + "\n마지막 오류: " + errorSummary());
        boolean paused = store.paused() && !store.userStopped();
        boolean running = store.active() && !store.paused() && !store.userStopped()
                && !SelfRunStore.PHASE_DONE.equals(store.phase())
                && !SelfRunStore.PHASE_IDLE.equals(store.phase());
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
        if (runId.isEmpty()) nextInputStatus.setText("실행 중인 SelfRun이 없습니다.");
        else if (editable) nextInputStatus.setText(stored.isEmpty()
                ? "차기 턴 실제 제출이 시작되기 전까지 입력·수정·삭제할 수 있습니다."
                : "차기 턴에 사용자 문구가 예약되어 있습니다. 실제 제출 시작 전까지 수정·삭제할 수 있습니다.");
        else if (locked) nextInputStatus.setText("차기 턴 제출이 시작되어 현재 예약 문구는 잠겼습니다.");
        else nextInputStatus.setText("현재 단계에서는 차기 턴 입력을 예약할 수 없습니다.");
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

    private void startRunner() {
        sendRunnerAction(SelfRunService.ACTION_RUN);
    }

    private void sendRunnerAction(String action) {
        Intent intent = new Intent(this, SelfRunService.class);
        intent.setAction(action);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent); else startService(intent);
    }

    private void refreshBackgroundStatus() {
        if (backgroundStatus == null) return;
        boolean notifications = Build.VERSION.SDK_INT < 33
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        PowerManager power = getSystemService(PowerManager.class);
        boolean battery = Build.VERSION.SDK_INT < 23 || power.isIgnoringBatteryOptimizations(getPackageName());
        backgroundStatus.setText((notifications ? "✓ 알림 권한 준비됨" : "! 실행 알림 권한 필요")
                + "\n" + (battery ? "✓ 배터리 최적화 제외됨" : "△ 배터리 최적화 제외 권장"));
    }

    private void requestBatteryExemption() {
        if (Build.VERSION.SDK_INT < 23) return;
        PowerManager power = getSystemService(PowerManager.class);
        if (power.isIgnoringBatteryOptimizations(getPackageName())) {
            Toast.makeText(this, "이미 배터리 최적화 제외 상태입니다.", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        } catch (Exception error) {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        }
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1001);
        }
    }

    private static String dash(String value) {
        return value == null || value.isEmpty() ? "-" : value;
    }
}
