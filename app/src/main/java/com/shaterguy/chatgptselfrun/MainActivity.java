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
import android.view.WindowManager;
import android.widget.Button;
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
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFocusableInTouchMode(true);
        root.setPadding(Ui.dp(this, 18), Ui.dp(this, 14), Ui.dp(this, 18), Ui.dp(this, 24));
        scroll.addView(root);

        root.addView(Ui.title(this, "SelfRun Drive"));
        root.addView(Ui.body(this, "v" + BuildConfig.VERSION_NAME + " · Drive SelfRun 신호를 기준으로 다음 턴을 진행합니다."));

        root.addView(Ui.section(this, "메뉴"));
        root.addView(Ui.row(this,
                Ui.button(this, "새 작업", v -> startActivity(new Intent(this, SelfRunNewActivity.class))),
                Ui.button(this, "작업 이력", v -> startActivity(new Intent(this, SelfRunHistoryActivity.class)))));
        root.addView(Ui.row(this,
                Ui.button(this, "로그", v -> startActivity(new Intent(this, SelfRunLogMenuActivity.class))),
                Ui.button(this, "로그인/세션", v -> startActivity(new Intent(this, LoginActivity.class)))));
        root.addView(Ui.row(this,
                Ui.button(this, "Drive 실행문서 위치", v -> startActivity(new Intent(this, DriveSetupActivity.class))),
                Ui.button(this, "웹 UI 보정", v -> startActivity(new Intent(this, WebUiCalibrationActivity.class)))));

        root.addView(Ui.section(this, "현재 SelfRun Drive"));
        currentStatus = Ui.body(this, "");
        root.addView(currentStatus);
        pauseButton = Ui.button(this, "일시정지", v -> pauseSelfRun());
        resumeButton = Ui.button(this, "재개", v -> resumeSelfRun());
        stopButton = Ui.button(this, "중지", v -> stopSelfRun());
        root.addView(Ui.row(this, pauseButton, resumeButton, stopButton));
        currentLogsButton = Ui.button(this, "현재 작업 로그", v -> openCurrentLogs());
        root.addView(currentLogsButton);

        root.addView(Ui.section(this, "백그라운드 실행"));
        backgroundStatus = Ui.body(this, "");
        root.addView(backgroundStatus);
        root.addView(Ui.row(this,
                Ui.button(this, "알림 권한", v -> requestNotificationPermission()),
                Ui.button(this, "배터리 최적화 제외", v -> requestBatteryExemption())));
        root.addView(Ui.body(this, "Drive 대기와 60초 guard 중에는 WakeLock을 유지하지 않습니다. Drive 요청·WebView 입력 같은 짧은 실행 구간에서만 사용합니다."));

        Ui.setContent(this, scroll);
        root.requestFocus();
    }

    private void refreshCurrent() {
        if (currentStatus == null) return;
        String runId = store.runId();
        if (runId.isEmpty()) {
            currentStatus.setText("실행 중이거나 선택된 SelfRun이 없습니다.");
            pauseButton.setEnabled(false);
            resumeButton.setEnabled(false);
            stopButton.setEnabled(false);
            currentLogsButton.setEnabled(false);
            return;
        }
        String prefs = SelfRunStore.MODE_WORK.equals(store.mode())
                ? store.pendingModel() + " / " + store.pendingReasoning() : "모델 변경 없음";
        currentStatus.setText("Run ID: " + runId
                + "\n모드: " + store.mode()
                + "\n상태: " + store.status()
                + "\n단계: " + store.phase()
                + "\n모델/추론: " + prefs
                + "\n턴: " + store.turn()
                + "\nconversation: " + dash(store.conversationUrl())
                + "\nDrive 문서: " + dash(store.turnDocumentUrl())
                + "\nDrive signal cursor: " + store.driveSignalCursor()
                + "\n마지막 Drive signal: " + dash(store.lastDriveSignalType())
                + "\nCOMMAND_RECEIVED 대기: " + (store.awaitingCommandAck() ? "예" : "아니오")
                + "\n마지막 오류: " + errorSummary());
        boolean paused = store.paused() && !store.userStopped();
        boolean running = store.active() && !store.paused() && !store.userStopped()
                && !SelfRunStore.PHASE_DONE.equals(store.phase())
                && !SelfRunStore.PHASE_IDLE.equals(store.phase());
        pauseButton.setEnabled(running);
        resumeButton.setEnabled(paused);
        stopButton.setEnabled(running || paused);
        currentLogsButton.setEnabled(true);
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
        Intent intent = new Intent(this, SelfRunService.class).setAction(SelfRunService.ACTION_STOP);
        try {
            startService(intent);
        } catch (Throwable ignored) {
            store.stopByUser();
        }
        refreshCurrent();
    }

    private void sendRunnerAction(String action) {
        Intent intent = new Intent(this, SelfRunService.class).setAction(action);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent);
        else startService(intent);
    }

    private void openCurrentLogs() {
        if (store.runId().isEmpty()) return;
        Intent intent = new Intent(this, SelfRunLogsActivity.class);
        intent.putExtra(SelfRunLogsActivity.EXTRA_RUN_ID, store.runId());
        intent.putExtra(SelfRunLogsActivity.EXTRA_KIND, SelfRunLogsActivity.KIND_EXECUTION);
        startActivity(intent);
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1001);
        } else {
            Toast.makeText(this, "알림 권한이 이미 허용되어 있습니다.", Toast.LENGTH_SHORT).show();
        }
    }

    private void requestBatteryExemption() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return;
        PowerManager manager = getSystemService(PowerManager.class);
        if (manager != null && manager.isIgnoringBatteryOptimizations(getPackageName())) {
            Toast.makeText(this, "배터리 최적화 제외가 이미 적용되어 있습니다.", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Throwable error) {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        }
    }

    private void refreshBackgroundStatus() {
        if (backgroundStatus == null) return;
        String text;
        if (store.userStopped()) text = "사용자가 SelfRun Drive를 중지했습니다.";
        else if (store.paused()) text = "일시정지 상태입니다. WebView와 Drive 실행정보는 보존됩니다.";
        else if (store.active()) text = "SelfRun Drive 포그라운드 서비스가 실행 상태를 관리합니다.";
        else text = "실행 중인 SelfRun Drive가 없습니다.";
        backgroundStatus.setText(text);
    }

    private static String dash(String value) {
        return value == null || value.isEmpty() ? "-" : value;
    }
}
