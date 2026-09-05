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
        ScrollView scroll = new ScrollView(this);
        LinearLayout page = Ui.page(this);
        scroll.addView(page);
        page.addView(Ui.topBar(this, "설정", "", null));
        page.addView(Ui.section(this, "연결"));
        page.addView(Ui.setting(this, R.drawable.ic_play_circle, "ChatGPT · 프로젝트", "",
                v -> startActivity(new Intent(this, LoginActivity.class))));
        page.addView(Ui.divider(this));
        page.addView(Ui.setting(this, R.drawable.ic_folder, "Drive 저장 위치",
                current.driveRunsBaseFolderId().isEmpty() ? "연결 안 됨" : current.driveRunsBaseFolderName(),
                v -> startActivity(new Intent(this, DriveSetupActivity.class))));
        page.addView(Ui.section(this, "실행"));
        page.addView(Ui.setting(this, R.drawable.ic_play_circle, "실행 알림",
                notificationReady() ? "허용됨" : "꺼짐", v -> requestNotificationPermission()));
        page.addView(Ui.divider(this));
        page.addView(Ui.setting(this, R.drawable.ic_settings, "배터리 최적화",
                batteryReady() ? "제외됨" : "사용 중", v -> requestBatteryExemption()));
        page.addView(Ui.section(this, "모델"));
        page.addView(Ui.setting(this, R.drawable.ic_settings, "모델 조합", "",
                v -> startActivity(new Intent(this, ProfileRegistryActivity.class))));
        page.addView(Ui.section(this, "SelfRun"));
        page.addView(Ui.muted(this, "버전 " + BuildConfig.VERSION_NAME));
        Ui.setPrimaryContent(this, scroll, Ui.DEST_TOOLS);
    }

    private boolean notificationReady() {
        return Build.VERSION.SDK_INT < 33
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean batteryReady() {
        PowerManager power = getSystemService(PowerManager.class);
        return Build.VERSION.SDK_INT < 23 || power.isIgnoringBatteryOptimizations(getPackageName());
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
