package com.shaterguy.chatgptselfrun;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.ViewGroup;
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
        ScrollView scroll = new ScrollView(this);
        LinearLayout page = Ui.page(this);
        scroll.addView(page);
        page.addView(Ui.toolbar(this, "작업 상세", null));
        if (item == null) {
            page.addView(Ui.headline(this, "저장된 작업을 찾을 수 없습니다"));
            Ui.setContent(this, scroll);
            return;
        }
        SelfRunHealthSnapshot runHealth = new SelfRunHealthObservationStore(this).currentFor(item);
        page.addView(Ui.headline(this, preview(item.optString("requirement"))));
        page.addView(Ui.body(this, runHealth == null ? item.optString("status") : runHealth.rowLabel()));
        if (runHealth != null && !runHealth.recommendedAction.isEmpty())
            page.addView(Ui.body(this, runHealth.recommendedAction));
        page.addView(Ui.section(this, "원본 요청"));
        android.widget.TextView requirement = Ui.body(this, empty(item.optString("requirement")));
        requirement.setTextIsSelectable(true);
        page.addView(requirement);
        page.addView(Ui.section(this, "실행 정보"));
        page.addView(Ui.keyValue(this, "시작", time(item.optLong("createdAt"))));
        page.addView(Ui.keyValue(this, "마지막 실행", time(item.optLong("updatedAt"))));
        page.addView(Ui.keyValue(this, "모드", item.optString("mode", "-")));
        page.addView(Ui.keyValue(this, "턴", String.valueOf(item.optInt("turn"))));
        page.addView(Ui.keyValue(this, "모델 조합", model(item)));
        String resolvedRunId = item.optString("runId");
        page.addView(Ui.section(this, "로그"));
        page.addView(Ui.setting(this, R.drawable.ic_history, "실행 로그", "", v -> openLogs(resolvedRunId, SelfRunLogsActivity.KIND_EXECUTION)));
        page.addView(Ui.setting(this, R.drawable.ic_history, "디버그 로그", "", v -> openLogs(resolvedRunId, SelfRunLogsActivity.KIND_DEBUG)));
        LinearLayout diagnostics = new LinearLayout(this);
        diagnostics.setOrientation(LinearLayout.VERTICAL);
        diagnostics.addView(Ui.keyValue(this, "Run ID", resolvedRunId));
        diagnostics.addView(Ui.keyValue(this, "Phase", item.optString("phase")));
        diagnostics.addView(Ui.keyValue(this, "프로젝트", item.optString("projectUrl")));
        diagnostics.addView(Ui.keyValue(this, "대화", item.optString("conversationUrl")));
        diagnostics.addView(Ui.keyValue(this, "오류", error(item)));
        if (runHealth != null) {
            diagnostics.addView(Ui.keyValue(this, "진단", runHealth.description));
            diagnostics.addView(Ui.keyValue(this, "신뢰도", runHealth.confidence));
            diagnostics.addView(Ui.keyValue(this, "진단 근거", runHealth.internalReason));
        }
        diagnostics.setVisibility(android.view.View.GONE);
        page.addView(Ui.textButton(this, "진단 정보", v -> diagnostics.setVisibility(
                diagnostics.getVisibility() == android.view.View.VISIBLE ? android.view.View.GONE : android.view.View.VISIBLE)));
        page.addView(diagnostics);
        if (SelfRunRestartPolicy.restartable(item))
            page.addView(Ui.button(this, "중지 작업 재시작", v -> openRestart(resolvedRunId)));
        Ui.setContent(this, scroll);
    }

    private void openLogs(String runId, String kind) {
        startActivity(new Intent(this, SelfRunLogsActivity.class)
                .putExtra(SelfRunLogsActivity.EXTRA_RUN_ID, runId)
                .putExtra(SelfRunLogsActivity.EXTRA_KIND, kind));
    }

    private void openRestart(String runId) {
        startActivity(new Intent(this, SelfRunRestartActivity.class)
                .putExtra(SelfRunRestartActivity.EXTRA_RUN_ID, runId));
    }

    private String model(JSONObject item) {
        return SelfRunStore.MODE_WORK.equals(item.optString("mode"))
                ? empty(item.optString("pendingModel")) + " / " + empty(item.optString("pendingReasoning"))
                : BootstrapRunStateStore.summary(item);
    }

    private static String error(JSONObject item) {
        String code = item.optString("lastErrorCode");
        if (code.isEmpty()) return "없음";
        return code + " · " + empty(item.optString("lastErrorMessage"));
    }

    private static String preview(String text) {
        if (text == null || text.trim().isEmpty()) return "요청 내용 없음";
        String oneLine = text.replace('\n', ' ').replace('\r', ' ').trim();
        return oneLine.length() <= 120 ? oneLine : oneLine.substring(0, 120) + "…";
    }

    private static String empty(String value) {
        return value == null || value.isEmpty() ? "-" : value;
    }

    private static String time(long value) {
        return value <= 0L ? "-" : DateFormat.getDateTimeInstance().format(new Date(value));
    }
}
