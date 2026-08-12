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

        root.addView(Ui.title(this, "ChatGPT SelfRun"));
        root.addView(Ui.body(this, "v0.2.3-dev3 · 첫 화면은 작업 대시보드입니다. 입력은 ‘새 작업’을 눌렀을 때만 열립니다."));

        root.addView(Ui.section(this, "메뉴"));
        root.addView(Ui.row(this,
                Ui.button(this, "새 작업", v -> startActivity(new Intent(this, SelfRunNewActivity.class))),
                Ui.button(this, "작업 이력", v -> startActivity(new Intent(this, SelfRunHistoryActivity.class)))));
        root.addView(Ui.row(this,
                Ui.button(this, "로그", v -> startActivity(new Intent(this, SelfRunLogMenuActivity.class))),
                Ui.button(this, "로그인/세션", v -> startActivity(new Intent(this, LoginActivity.class)))));

        root.addView(Ui.section(this, "현재 SelfRun"));
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
        root.addView(Ui.body(this, "실행 중에는 Foreground Service를 유지하고, partial WakeLock은 SelfRun 작업에 필요한 동안만 사용합니다. 예약 알람이나 부팅 자동실행은 사용하지 않습니다."));

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
                + "\n현재/다음 역할: " + dash(store.role())
                + "\n모델/추론: " + prefs
                + "\n턴: " + store.turn()
                + "\nconversation: " + dash(store.conversationUrl())
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
        store.setActive(false);
        store.setPaused(false);
        store.setUserStopped(true);
        store.setPhase(SelfRunStore.PHASE_IDLE);
        store.setStatus("사용자 중지");
        runLog.record(store, "UI_STOP", "user_stop");
        history.sync(store);
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
        backgroundStatus.setText((notifications ? "✓" : "✕") + " 실행 알림 권한"
                + "\n" + (battery ? "✓" : "△") + " 배터리 최적화 제외");
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
