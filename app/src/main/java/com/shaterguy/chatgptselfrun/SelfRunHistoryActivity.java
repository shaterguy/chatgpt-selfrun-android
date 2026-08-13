package com.shaterguy.chatgptselfrun;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.DateFormat;
import java.util.Date;

public final class SelfRunHistoryActivity extends Activity {
    private LinearLayout root;
    private SelfRunHistoryStore history;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        history = new SelfRunHistoryStore(this);
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (root != null) render();
    }

    private void render() {
        ScrollView scroll = new ScrollView(this);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(Ui.dp(this, 18), Ui.dp(this, 14), Ui.dp(this, 18), Ui.dp(this, 24));
        scroll.addView(root);
        root.addView(Ui.title(this, "SelfRun Drive 작업 이력"));
        root.addView(Ui.row(this,
                Ui.button(this, "뒤로", v -> finish()),
                Ui.button(this, "새로고침", v -> render())));
        root.addView(Ui.body(this, "현재 작업과 지난 작업을 함께 보존합니다. 각 작업의 실행 로그와 디버그 로그를 별도로 확인할 수 있습니다."));

        JSONArray runs = history.read();
        if (runs.length() == 0) {
            root.addView(Ui.section(this, "작업 없음"));
            root.addView(Ui.body(this, "아직 저장된 SelfRun Drive 작업이 없습니다."));
        }
        for (int i = 0; i < runs.length(); i++) {
            JSONObject item = runs.optJSONObject(i);
            if (item == null) continue;
            addRun(item);
        }
        Ui.setContent(this, scroll);
    }

    private void addRun(JSONObject item) {
        String runId = item.optString("runId");
        root.addView(Ui.section(this, runId));
        String model = SelfRunStore.MODE_WORK.equals(item.optString("mode"))
                ? item.optString("pendingModel", "-") + " / " + item.optString("pendingReasoning", "-")
                : "모델 변경 없음";
        TextView summary = Ui.body(this,
                "모드: " + item.optString("mode", "-")
                        + "\n상태: " + item.optString("status", "-")
                        + "\n역할: " + empty(item.optString("role"))
                        + "\n모델/추론: " + model
                        + "\n턴: " + item.optInt("turn")
                        + "\n최종 갱신: " + time(item.optLong("updatedAt"))
                        + "\n요청: " + preview(item.optString("requirement")));
        root.addView(summary);
        root.addView(Ui.row(this,
                Ui.button(this, "작업 보기", v -> openDetail(runId)),
                Ui.button(this, "실행 로그", v -> openLogs(runId, SelfRunLogsActivity.KIND_EXECUTION)),
                Ui.button(this, "디버그 로그", v -> openLogs(runId, SelfRunLogsActivity.KIND_DEBUG))));
    }

    private void openDetail(String runId) {
        Intent intent = new Intent(this, SelfRunDetailActivity.class);
        intent.putExtra(SelfRunDetailActivity.EXTRA_RUN_ID, runId);
        startActivity(intent);
    }

    private void openLogs(String runId, String kind) {
        Intent intent = new Intent(this, SelfRunLogsActivity.class);
        intent.putExtra(SelfRunLogsActivity.EXTRA_RUN_ID, runId);
        intent.putExtra(SelfRunLogsActivity.EXTRA_KIND, kind);
        startActivity(intent);
    }

    private static String preview(String text) {
        if (text == null || text.trim().isEmpty()) return "-";
        String oneLine = text.replace('\n', ' ').replace('\r', ' ').trim();
        return oneLine.length() <= 120 ? oneLine : oneLine.substring(0, 120) + "…";
    }

    private static String empty(String value) { return value == null || value.isEmpty() ? "-" : value; }
    private static String time(long value) {
        return value <= 0L ? "-" : DateFormat.getDateTimeInstance().format(new Date(value));
    }
}
