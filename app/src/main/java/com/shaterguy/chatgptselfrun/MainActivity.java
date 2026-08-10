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
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;

public final class MainActivity extends Activity {
    private static final String[] MODE_LABELS = {"Work · 모델/추론 동적 전환", "일반 Chat · 모델 변경 없음"};
    private static final String[] MODE_VALUES = {SelfRunStore.MODE_WORK, SelfRunStore.MODE_CHAT};

    private final Handler refreshHandler = new Handler(Looper.getMainLooper());
    private final Runnable refreshRunnable = new Runnable() {
        @Override public void run() {
            refreshStatus();
            refreshBackgroundStatus();
            refreshHandler.postDelayed(this, 1000L);
        }
    };

    private SelfRunStore store;
    private SelfRunRunLog runLog;
    private EditText projectUrl;
    private EditText requirement;
    private Spinner mode;
    private TextView status;
    private TextView backgroundStatus;
    private Button resumeButton;
    private Button stopButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new SelfRunStore(this);
        runLog = new SelfRunRunLog(this);
        NotificationHelper.ensureChannel(this);
        createViews();
        requestNotificationPermission();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshHandler.removeCallbacks(refreshRunnable);
        refreshStatus();
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
        root.setPadding(Ui.dp(this, 18), Ui.dp(this, 14), Ui.dp(this, 18), Ui.dp(this, 24));
        scroll.addView(root);

        root.addView(Ui.title(this, "ChatGPT SelfRun"));
        root.addView(Ui.body(this, "v0.1.0-dev2 · 앱과 로그인 화면은 모바일 UI이며, 사용자에게 보이지 않는 자동화 WebView만 1440×900 데스크톱 가로 환경으로 동작합니다."));
        root.addView(Ui.body(this, "Work는 첫 턴 Sol xHigh 후 작업 성격에 따라 모델/추론을 동적으로 전환하며 최저 조합은 Luna Max입니다. 일반 Chat은 시작 모델을 끝까지 유지하고 역할만 전환합니다."));

        root.addView(Ui.section(this, "작업 이력 · 로그"));
        root.addView(Ui.button(this, "지난 SelfRun 작업 보기", v ->
                startActivity(new Intent(this, SelfRunHistoryActivity.class))));

        root.addView(Ui.section(this, "ChatGPT 로그인/세션"));
        root.addView(Ui.body(this, "이 화면은 일반 모바일 WebView로 표시합니다. 로그인 쿠키만 내부 자동화 WebView와 공유합니다."));
        root.addView(Ui.button(this, "로그인 화면 열기", v -> startActivity(new Intent(this, LoginActivity.class))));

        root.addView(Ui.section(this, "백그라운드 실행"));
        backgroundStatus = Ui.body(this, "");
        root.addView(backgroundStatus);
        root.addView(Ui.row(this,
                Ui.button(this, "알림 권한", v -> requestNotificationPermission()),
                Ui.button(this, "배터리 최적화 제외", v -> requestBatteryExemption())));
        root.addView(Ui.body(this, "SelfRun은 예약 알람을 사용하지 않습니다. 실행 중에는 Foreground Service와 WakeLock을 사용하고, 절전 정책에 의한 중단을 줄이기 위해 배터리 최적화 제외를 권장합니다."));

        root.addView(Ui.section(this, "프로젝트 주소"));
        projectUrl = new EditText(this);
        projectUrl.setSingleLine(true);
        projectUrl.setHint("https://chatgpt.com/g/<project-id>");
        projectUrl.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        root.addView(projectUrl);

        root.addView(Ui.section(this, "실행 모드"));
        mode = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, MODE_LABELS);
        mode.setAdapter(adapter);
        root.addView(mode);

        root.addView(Ui.section(this, "셀프런 명령"));
        requirement = new EditText(this);
        requirement.setHint("작업 요구사항을 입력하세요. MODE bootstrap은 앱이 자동으로 앞에 붙입니다.");
        requirement.setSingleLine(false);
        requirement.setMinLines(8);
        requirement.setMaxLines(22);
        requirement.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        requirement.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        root.addView(requirement);

        root.addView(Ui.section(this, "실행"));
        root.addView(Ui.row(this,
                Ui.button(this, "셀프런 시작", v -> startSelfRun()),
                Ui.button(this, "입력 초기화", v -> resetForm())));

        root.addView(Ui.section(this, "현재 상태"));
        status = Ui.body(this, "");
        root.addView(status);
        resumeButton = Ui.button(this, "재개", v -> resumeSelfRun());
        stopButton = Ui.button(this, "중지", v -> stopSelfRun());
        root.addView(Ui.row(this, resumeButton, stopButton));

        Ui.setContent(this, scroll);
    }

    private void startSelfRun() {
        if (store.active() && !store.paused()) {
            Toast.makeText(this, "현재 SelfRun이 실행 중입니다. 먼저 중지하거나 완료될 때까지 유지하세요.", Toast.LENGTH_LONG).show();
            return;
        }
        String project = projectUrl.getText().toString().trim();
        String request = requirement.getText().toString().trim();
        if (SelfRunScript.projectId(project).isEmpty()) {
            Toast.makeText(this, "ChatGPT 프로젝트 주소를 확인하세요.", Toast.LENGTH_LONG).show();
            return;
        }
        if (request.isEmpty()) {
            Toast.makeText(this, "셀프런 명령을 입력하세요.", Toast.LENGTH_LONG).show();
            return;
        }
        String selectedMode = MODE_VALUES[mode.getSelectedItemPosition()];
        String runId = newRunId();
        store.start(runId, selectedMode, project, request);
        runLog.record(store, "UI_START", "mode=" + selectedMode);
        startRunner();
        Toast.makeText(this, "SelfRun을 시작했습니다: " + runId, Toast.LENGTH_LONG).show();
        refreshStatus();
    }

    private void resumeSelfRun() {
        if (!store.paused() || store.runId().isEmpty()) return;
        store.setPaused(false);
        store.setActive(true);
        store.setUserStopped(false);
        store.clearLastError();
        store.setLastSignal("USER_RESUME");
        store.setPhase(SelfRunStore.PHASE_SEND_CONTINUE);
        store.setStatus("사용자 재개 · 같은 conversation 확인 중");
        runLog.record(store, "UI_RESUME", "resume");
        startRunner();
        refreshStatus();
    }

    private void stopSelfRun() {
        if (store.runId().isEmpty()) return;
        store.setActive(false);
        store.setPaused(false);
        store.setUserStopped(true);
        store.setPhase(SelfRunStore.PHASE_IDLE);
        store.setStatus("사용자 중지");
        runLog.record(store, "UI_STOP", "user_stop");
        stopService(new Intent(this, SelfRunService.class));
        refreshStatus();
    }

    private void resetForm() {
        if (store.active() && !store.paused()) {
            Toast.makeText(this, "실행 중에는 현재 Run 상태를 초기화하지 않습니다.", Toast.LENGTH_LONG).show();
            return;
        }
        projectUrl.setText("");
        requirement.setText("");
        mode.setSelection(0);
        store.clear();
        refreshStatus();
    }

    private void startRunner() {
        Intent intent = new Intent(this, SelfRunService.class);
        intent.setAction(SelfRunService.ACTION_RUN);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent); else startService(intent);
    }

    private void refreshStatus() {
        if (status == null) return;
        String runId = store.runId();
        if (runId.isEmpty()) {
            status.setText("실행 중인 SelfRun이 없습니다.");
            resumeButton.setEnabled(false);
            stopButton.setEnabled(false);
            return;
        }
        String prefs = SelfRunStore.MODE_WORK.equals(store.mode())
                ? store.pendingModel() + " / " + store.pendingReasoning() : "모델 변경 없음";
        status.setText("Run ID: " + runId
                + "\n모드: " + store.mode()
                + "\n상태: " + store.status()
                + "\n단계: " + store.phase()
                + "\n현재/다음 역할: " + dash(store.role())
                + "\n모델/추론: " + prefs
                + "\n턴: " + store.turn()
                + "\nconversation: " + dash(store.conversationUrl())
                + "\n마지막 신호: " + dash(store.lastSignal()));
        resumeButton.setEnabled(store.paused() && !store.userStopped());
        stopButton.setEnabled(store.active() || store.paused());
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
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception error) {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        }
    }

    private static String dash(String value) {
        return value == null || value.isEmpty() ? "-" : value;
    }

    private static String newRunId() {
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase(Locale.US);
        return "SR-" + stamp + "-" + suffix;
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1001);
        }
    }
}
