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
        page.addView(Ui.topBar(this, "Run Inspector", "실행 상태와 원본 요청",
                Ui.textButton(this, "뒤로", v -> finish())));

        if (item == null) {
            page.addView(Ui.heroSurface(this,
                    Ui.statusPill(this, "NOT FOUND"),
                    Ui.headline(this, "저장된 작업을 찾을 수 없습니다"),
                    Ui.body(this, "작업 이력이 삭제되었거나 현재 Run ID와 일치하지 않습니다.")));
            Ui.setContent(this, scroll);
            return;
        }

        String status = item.optString("status", "STATE");
        page.addView(Ui.heroSurface(this,
                Ui.statusPill(this, status),
                Ui.headline(this, preview(item.optString("requirement"))),
                Ui.muted(this, item.optString("runId"))));

        page.addView(Ui.section(this, "EXECUTION SNAPSHOT"));
        LinearLayout snapshot = new LinearLayout(this);
        snapshot.setOrientation(LinearLayout.VERTICAL);
        snapshot.addView(Ui.keyValue(this, "Created", time(item.optLong("createdAt"))));
        snapshot.addView(Ui.keyValue(this, "Updated", time(item.optLong("updatedAt"))));
        snapshot.addView(Ui.keyValue(this, "Mode", item.optString("mode", "-")));
        snapshot.addView(Ui.keyValue(this, "Phase", item.optString("phase", "-")));
        snapshot.addView(Ui.keyValue(this, "Turn", String.valueOf(item.optInt("turn"))));
        snapshot.addView(Ui.keyValue(this, "Profile", model(item)));
        page.addView(snapshot);

        page.addView(Ui.section(this, "SOURCE & DIAGNOSTIC"));
        LinearLayout source = new LinearLayout(this);
        source.setOrientation(LinearLayout.VERTICAL);
        source.addView(Ui.keyValue(this, "Project", empty(item.optString("projectUrl"))));
        source.addView(Ui.keyValue(this, "Conversation", empty(item.optString("conversationUrl"))));
        source.addView(Ui.keyValue(this, "Error", error(item)));
        page.addView(source);

        page.addView(Ui.section(this, "ORIGINAL MISSION"));
        page.addView(Ui.card(this, Ui.body(this, empty(item.optString("requirement")))));

        String resolvedRunId = item.optString("runId");
        page.addView(Ui.section(this, "RELATED ACTIONS"));
        page.addView(Ui.actionStrip(this,
                Ui.outlinedButton(this, "실행 로그", v -> openLogs(resolvedRunId, SelfRunLogsActivity.KIND_EXECUTION)),
                Ui.outlinedButton(this, "디버그 로그", v -> openLogs(resolvedRunId, SelfRunLogsActivity.KIND_DEBUG))));
        if (SelfRunRestartPolicy.restartable(item)) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.topMargin = Ui.dp(this, 10);
            page.addView(Ui.button(this, "중지 작업 재시작", v -> openRestart(resolvedRunId)), params);
        }
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
