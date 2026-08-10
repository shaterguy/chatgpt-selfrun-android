package com.shaterguy.chatgptselfrun;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import org.json.JSONObject;

import java.text.DateFormat;
import java.util.Date;

public final class SelfRunDetailActivity extends Activity {
    public static final String EXTRA_RUN_ID = "selfrun.runId";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        String runId = getIntent().getStringExtra(EXTRA_RUN_ID);
        JSONObject item = new SelfRunHistoryStore(this).get(runId);
        ScrollView scroll = Ui.scroll(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(Ui.dp(this, 18), Ui.dp(this, 14), Ui.dp(this, 18), Ui.dp(this, 24));
        scroll.addView(root);
        root.addView(Ui.title(this, "SelfRun 작업 상세"));
        root.addView(Ui.button(this, "뒤로", v -> finish()));
        if (item == null) {
            root.addView(Ui.body(this, "저장된 작업을 찾을 수 없습니다."));
            Ui.setContent(this, scroll);
            return;
        }
        root.addView(Ui.section(this, item.optString("runId")));
        root.addView(Ui.body(this,
                "생성: " + time(item.optLong("createdAt"))
                        + "\n갱신: " + time(item.optLong("updatedAt"))
                        + "\n모드: " + item.optString("mode", "-")
                        + "\n상태: " + item.optString("status", "-")
                        + "\n단계: " + item.optString("phase", "-")
                        + "\n역할: " + empty(item.optString("role"))
                        + "\n턴: " + item.optInt("turn")
                        + "\n모델/추론: " + model(item)
                        + "\n프로젝트: " + empty(item.optString("projectUrl"))
                        + "\nconversation: " + empty(item.optString("conversationUrl"))
                        + "\n마지막 신호: " + empty(item.optString("lastSignal"))
                        + "\n오류: " + error(item)));
        root.addView(Ui.section(this, "원본 요청"));
        root.addView(Ui.body(this, empty(item.optString("requirement"))));
        root.addView(Ui.section(this, "로그"));
        root.addView(Ui.row(this,
                Ui.button(this, "실행 로그", v -> openLogs(item.optString("runId"), SelfRunLogsActivity.KIND_EXECUTION)),
                Ui.button(this, "디버그 로그", v -> openLogs(item.optString("runId"), SelfRunLogsActivity.KIND_DEBUG))));
        Ui.setContent(this, scroll);
    }

    private void openLogs(String runId, String kind) {
        Intent intent = new Intent(this, SelfRunLogsActivity.class);
        intent.putExtra(SelfRunLogsActivity.EXTRA_RUN_ID, runId);
        intent.putExtra(SelfRunLogsActivity.EXTRA_KIND, kind);
        startActivity(intent);
    }

    private static String model(JSONObject item) {
        return SelfRunStore.MODE_WORK.equals(item.optString("mode"))
                ? empty(item.optString("pendingModel")) + " / " + empty(item.optString("pendingReasoning"))
                : "모델 변경 없음";
    }

    private static String error(JSONObject item) {
        String code = item.optString("lastErrorCode");
        if (code.isEmpty()) return "없음";
        return code + " · " + empty(item.optString("lastErrorMessage"));
    }

    private static String empty(String value) { return value == null || value.isEmpty() ? "-" : value; }
    private static String time(long value) {
        return value <= 0L ? "-" : DateFormat.getDateTimeInstance().format(new Date(value));
    }
}
