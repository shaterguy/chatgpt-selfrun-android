package com.shaterguy.chatgptselfrun;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.view.WindowManager;
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

public final class SelfRunNewActivity extends Activity {
    private static final String[] MODE_LABELS = {"Work · 모델/추론 동적 전환", "일반 Chat · 모델 변경 없음"};
    private static final String[] MODE_VALUES = {SelfRunStore.MODE_WORK, SelfRunStore.MODE_CHAT};

    private SelfRunStore store;
    private SelfRunHistoryStore history;
    private SelfRunRunLog runLog;
    private EditText projectUrl;
    private EditText requirement;
    private Spinner mode;
    private Button latestCommandButton;
    private TextView latestCommandStatus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        store = new SelfRunStore(this);
        history = new SelfRunHistoryStore(this);
        runLog = new SelfRunRunLog(this);
        createViews();
    }

    private void createViews() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFocusableInTouchMode(true);
        root.setPadding(Ui.dp(this, 18), Ui.dp(this, 14), Ui.dp(this, 18), Ui.dp(this, 24));
        scroll.addView(root);

        root.addView(Ui.title(this, "새 SelfRun 작업"));
        root.addView(Ui.button(this, "작업 목록", v -> finish()));
        root.addView(Ui.body(this, "새 작업은 빈 요구사항에서 시작합니다. 이전 Run의 입력 내용·신호·오류는 이 화면에 불러오지 않습니다."));

        root.addView(Ui.section(this, "프로젝트 주소"));
        projectUrl = new EditText(this);
        projectUrl.setSingleLine(true);
        projectUrl.setHint("https://chatgpt.com/g/<project-id>");
        projectUrl.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        projectUrl.setText(store.defaultProjectUrl());
        root.addView(projectUrl);

        root.addView(Ui.section(this, "실행 모드"));
        mode = new Spinner(this);
        mode.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, MODE_LABELS));
        root.addView(mode);

        root.addView(Ui.section(this, "셀프런 명령"));
        requirement = new EditText(this);
        requirement.setHint("작업 요구사항");
        requirement.setSingleLine(false);
        requirement.setMinLines(8);
        requirement.setMaxLines(24);
        requirement.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        requirement.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        root.addView(requirement);

        latestCommandButton = Ui.button(this, "최신 명령 불러오기", v -> loadLatestCommand());
        root.addView(latestCommandButton);
        latestCommandStatus = Ui.body(this, "브리지에서 명령을 불러오면 현재 요구사항을 교체합니다. 자동으로 실행하지 않습니다.");
        root.addView(latestCommandStatus);

        root.addView(Ui.section(this, "시작"));
        root.addView(Ui.button(this, "SelfRun 시작", v -> startSelfRun()));

        Ui.setContent(this, scroll);
        projectUrl.clearFocus();
        requirement.clearFocus();
        root.requestFocus();
    }

    private void loadLatestCommand() {
        latestCommandButton.setEnabled(false);
        latestCommandStatus.setText("최신 명령을 불러오는 중입니다.");
        String endpoint = BuildConfig.SELF_RUN_COMMAND_BRIDGE_URL + "/api/selfrun/latest";
        String token = BuildConfig.SELF_RUN_ANDROID_READ_TOKEN;
        new Thread(() -> {
            SelfRunCommandBridgeClient.Result result =
                    SelfRunCommandBridgeClient.fetch(endpoint, token);
            runOnUiThread(() -> {
                latestCommandButton.setEnabled(true);
                if (result.status == SelfRunCommandBridgeClient.Status.SUCCESS) {
                    requirement.setText(result.command);
                    requirement.setSelection(requirement.length());
                    latestCommandStatus.setText("최신 명령을 불러왔습니다. 저장 시각: " + result.savedAt);
                } else {
                    latestCommandStatus.setText(result.message);
                }
            });
        }, "selfrun-command-bridge").start();
    }

    private void startSelfRun() {
        if (store.active() && !store.paused()) {
            Toast.makeText(this, "현재 SelfRun이 실행 중입니다. 현재 작업을 먼저 중지하세요.", Toast.LENGTH_LONG).show();
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
        store.setDefaultProjectUrl(project);
        if (!store.runId().isEmpty()) history.sync(store);
        stopService(new Intent(this, SelfRunService.class));
        String selectedMode = MODE_VALUES[mode.getSelectedItemPosition()];
        String runId = newRunId();
        store.start(runId, selectedMode, project, request);
        runLog.record(store, "UI_START", "mode=" + selectedMode);
        startRunner();
        Toast.makeText(this, "SelfRun을 시작했습니다: " + runId, Toast.LENGTH_LONG).show();
        finish();
    }

    private void startRunner() {
        Intent intent = new Intent(this, SelfRunService.class);
        intent.setAction(SelfRunService.ACTION_RUN);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent); else startService(intent);
    }

    private static String newRunId() {
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase(Locale.US);
        return "SR-" + stamp + "-" + suffix;
    }
}
