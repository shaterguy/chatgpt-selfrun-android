package com.shaterguy.chatgptselfrun;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.DateFormat;
import java.util.Date;

public final class SelfRunLogMenuActivity extends Activity {
    private SelfRunStore current;
    private SelfRunHistoryStore history;
    private TextView runtimeStatus;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        current = new SelfRunStore(this);
        history = new SelfRunHistoryStore(this);
    }

    @Override protected void onResume() {
        super.onResume();
        history.sync(current);
        render();
    }

    private void render() {
        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(this);
        LinearLayout page = Ui.page(this);
        scroll.addView(page);
        page.addView(Ui.topBar(this, "도구", "Connections · Runtime · Profiles · Diagnostics",
                Ui.textButton(this, "새로고침", v -> { history.sync(current); render(); })));

        page.addView(Ui.section(this, "CONNECTIONS"));
        page.addView(Ui.settingsRow(this,
                "ChatGPT 세션 · 프로젝트",
                "로그인 상태를 확인하고 사용할 프로젝트를 직접 등록합니다.",
                Ui.outlinedButton(this, "열기", v -> startActivity(new Intent(this, LoginActivity.class)))));
        page.addView(Ui.divider(this));
        page.addView(Ui.settingsRow(this,
                "Drive 실행문서 위치",
                current.driveRunsBaseFolderId().isEmpty()
                        ? "아직 Runs 저장 위치가 연결되지 않았습니다."
                        : current.driveRunsBaseFolderName() + " · 연결됨",
                Ui.outlinedButton(this, "설정", v -> startActivity(new Intent(this, DriveSetupActivity.class)))));

        page.addView(Ui.section(this, "RUNTIME"));
        runtimeStatus = Ui.body(this, runtimeSummary());
        page.addView(runtimeStatus);
        page.addView(Ui.settingsRow(this,
                "실행 알림",
                notificationReady() ? "알림 권한 준비됨" : "포그라운드 실행 알림 권한이 필요합니다.",
                Ui.outlinedButton(this, notificationReady() ? "확인됨" : "허용", v -> requestNotificationPermission())));
        page.addView(Ui.divider(this));
        page.addView(Ui.settingsRow(this,
                "배터리 최적화",
                batteryReady() ? "SelfRun Drive가 최적화 제외 상태입니다." : "장기 실행 안정성을 위해 제외를 권장합니다.",
                Ui.outlinedButton(this, batteryReady() ? "확인됨" : "설정", v -> requestBatteryExemption())));

        page.addView(Ui.section(this, "PROFILES"));
        page.addView(Ui.settingsRow(this,
                "모델 및 추론수준 관리",
                "Chat/Work 운영 신호와 실제 request profile을 조회·캡처·삭제하고 Work Registry를 내보냅니다.",
                Ui.outlinedButton(this, "열기", v -> startActivity(new Intent(this, ProfileRegistryActivity.class)))));

        page.addView(Ui.section(this, "RUN LOGS"));
        JSONArray runs = history.read();
        if (runs.length() == 0) {
            page.addView(Ui.muted(this, "저장된 Run 로그가 없습니다."));
        } else {
            for (int i = 0; i < runs.length(); i++) {
                JSONObject item = runs.optJSONObject(i);
                if (item != null) addRunLogRow(page, item);
            }
        }

        screen.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        Ui.setPrimaryContent(this, screen, Ui.DEST_TOOLS);
    }

    private void addRunLogRow(LinearLayout page, JSONObject item) {
        String runId = item.optString("runId");
        boolean isCurrent = runId.equals(current.runId());
        page.addView(Ui.listItem(this,
                isCurrent ? "CURRENT RUN" : item.optString("status", "RUN"),
                preview(item.optString("requirement")),
                "Turn " + item.optInt("turn") + " · " + time(item.optLong("updatedAt")),
                null));
        page.addView(Ui.actionStrip(this,
                Ui.textButton(this, "실행 로그", v -> open(runId, SelfRunLogsActivity.KIND_EXECUTION)),
                Ui.textButton(this, "디버그", v -> open(runId, SelfRunLogsActivity.KIND_DEBUG))));
        page.addView(Ui.divider(this));
    }

    private void open(String runId, String kind) {
        startActivity(new Intent(this, SelfRunLogsActivity.class)
                .putExtra(SelfRunLogsActivity.EXTRA_RUN_ID, runId)
                .putExtra(SelfRunLogsActivity.EXTRA_KIND, kind));
    }

    private boolean notificationReady() {
        return Build.VERSION.SDK_INT < 33
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean batteryReady() {
        PowerManager power = getSystemService(PowerManager.class);
        return Build.VERSION.SDK_INT < 23 || power.isIgnoringBatteryOptimizations(getPackageName());
    }

    private String runtimeSummary() {
        return (notificationReady() ? "✓ 알림 준비" : "! 알림 권한 필요")
                + "   " + (batteryReady() ? "✓ 배터리 준비" : "△ 배터리 설정 권장");
    }

    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && !notificationReady()) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1001);
        } else {
            Toast.makeText(this, "알림 권한이 이미 준비되어 있습니다.", Toast.LENGTH_SHORT).show();
        }
    }

    private void requestBatteryExemption() {
        if (Build.VERSION.SDK_INT < 23 || batteryReady()) {
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

    private static String preview(String text) {
        if (text == null || text.trim().isEmpty()) return "요청 내용 없음";
        String oneLine = text.replace('\n', ' ').replace('\r', ' ').trim();
        return oneLine.length() <= 90 ? oneLine : oneLine.substring(0, 90) + "…";
    }

    private static String time(long value) {
        return value <= 0L ? "-" : DateFormat.getDateTimeInstance().format(new Date(value));
    }
}
