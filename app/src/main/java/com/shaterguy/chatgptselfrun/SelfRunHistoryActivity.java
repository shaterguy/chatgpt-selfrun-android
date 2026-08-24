package com.shaterguy.chatgptselfrun;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.DateFormat;
import java.util.Date;

public final class SelfRunHistoryActivity extends Activity {
    private SelfRunHistoryStore history;
    private LinearLayout detailPane;
    private String selectedRunId = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        history = new SelfRunHistoryStore(this);
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    private void render() {
        JSONArray runs = history.read();
        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);

        LinearLayout page = Ui.page(this);
        page.addView(Ui.topBar(this, "작업 이력", "Run Browser",
                Ui.textButton(this, "새로고침", v -> render())));

        if (runs.length() == 0) {
            page.addView(Ui.heroSurface(this,
                    Ui.statusPill(this, "EMPTY"),
                    Ui.headline(this, "저장된 SelfRun이 없습니다"),
                    Ui.body(this, "새 작업을 시작하면 실행 상태와 지난 기록이 이곳에 쌓입니다."),
                    Ui.button(this, "새 SelfRun 시작", v -> startActivity(new Intent(this, SelfRunNewActivity.class)))));
            screen.addView(page, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            Ui.setPrimaryContent(this, screen, Ui.DEST_HISTORY);
            return;
        }

        ScrollView listScroll = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        listScroll.addView(list);

        JSONObject first = null;
        JSONObject selected = null;
        for (int i = 0; i < runs.length(); i++) {
            JSONObject item = runs.optJSONObject(i);
            if (item == null) continue;
            if (first == null) first = item;
            if (item.optString("runId").equals(selectedRunId)) selected = item;
            addRunRow(list, item);
        }

        if (Ui.isExpanded(this)) {
            detailPane = new LinearLayout(this);
            detailPane.setOrientation(LinearLayout.VERTICAL);
            detailPane.setPadding(Ui.dp(this, 18), Ui.dp(this, 16), Ui.dp(this, 18), Ui.dp(this, 16));
            JSONObject target = selected != null ? selected : first;
            if (target != null) {
                selectedRunId = target.optString("runId");
                renderDetailPane(target);
            }
            LinearLayout panes = new LinearLayout(this);
            panes.setOrientation(LinearLayout.HORIZONTAL);
            panes.addView(listScroll, new LinearLayout.LayoutParams(0, 0, 0.92f));
            LinearLayout.LayoutParams detailParams = new LinearLayout.LayoutParams(0, 0, 1.08f);
            detailParams.setMarginStart(Ui.dp(this, 20));
            panes.addView(detailPane, detailParams);
            LinearLayout.LayoutParams panesParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
            page.addView(panes, panesParams);
        } else {
            page.addView(listScroll, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        }

        screen.addView(page, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        Ui.setPrimaryContent(this, screen, Ui.DEST_HISTORY);
    }

    private void addRunRow(LinearLayout list, JSONObject item) {
        String runId = item.optString("runId");
        String status = item.optString("status", "-");
        String supporting = "Turn " + item.optInt("turn")
                + " · " + item.optString("mode", "-")
                + " · " + time(item.optLong("updatedAt"))
                + "\n" + shortId(runId);
        LinearLayout row = Ui.listItem(this,
                status,
                preview(item.optString("requirement")),
                supporting,
                v -> {
                    if (Ui.isExpanded(this)) {
                        selectedRunId = runId;
                        renderDetailPane(item);
                    } else {
                        openDetail(runId);
                    }
                });
        list.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        list.addView(Ui.divider(this));
    }

    private void renderDetailPane(JSONObject item) {
        if (detailPane == null) return;
        detailPane.removeAllViews();
        String runId = item.optString("runId");
        detailPane.addView(Ui.statusPill(this, item.optString("status", "STATE")));
        detailPane.addView(Ui.headline(this, preview(item.optString("requirement"))));
        detailPane.addView(Ui.keyValue(this, "Run ID", runId));
        detailPane.addView(Ui.keyValue(this, "Phase", item.optString("phase", "-")));
        detailPane.addView(Ui.keyValue(this, "Turn", String.valueOf(item.optInt("turn"))));
        detailPane.addView(Ui.keyValue(this, "Mode", item.optString("mode", "-")));
        detailPane.addView(Ui.keyValue(this, "Profile", model(item)));
        detailPane.addView(Ui.keyValue(this, "Updated", time(item.optLong("updatedAt"))));
        detailPane.addView(Ui.section(this, "ACTIONS"));
        detailPane.addView(Ui.actionStrip(this,
                Ui.outlinedButton(this, "상세", v -> openDetail(runId)),
                Ui.outlinedButton(this, "실행 로그", v -> openLogs(runId, SelfRunLogsActivity.KIND_EXECUTION)),
                Ui.outlinedButton(this, "디버그", v -> openLogs(runId, SelfRunLogsActivity.KIND_DEBUG))));
        if (SelfRunRestartPolicy.restartable(item)) {
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.topMargin = Ui.dp(this, 10);
            detailPane.addView(Ui.button(this, "중지 작업 재시작", v -> openRestart(runId)), params);
        }
    }

    private void openDetail(String runId) {
        startActivity(new Intent(this, SelfRunDetailActivity.class)
                .putExtra(SelfRunDetailActivity.EXTRA_RUN_ID, runId));
    }

    private void openRestart(String runId) {
        startActivity(new Intent(this, SelfRunRestartActivity.class)
                .putExtra(SelfRunRestartActivity.EXTRA_RUN_ID, runId));
    }

    private void openLogs(String runId, String kind) {
        startActivity(new Intent(this, SelfRunLogsActivity.class)
                .putExtra(SelfRunLogsActivity.EXTRA_RUN_ID, runId)
                .putExtra(SelfRunLogsActivity.EXTRA_KIND, kind));
    }

    private String model(JSONObject item) {
        return SelfRunStore.MODE_WORK.equals(item.optString("mode"))
                ? empty(item.optString("pendingModel")) + " / " + empty(item.optString("pendingReasoning"))
                : BootstrapRunStateStore.summary(item);
    }

    private static String preview(String text) {
        if (text == null || text.trim().isEmpty()) return "요청 내용 없음";
        String oneLine = text.replace('\n', ' ').replace('\r', ' ').trim();
        return oneLine.length() <= 120 ? oneLine : oneLine.substring(0, 120) + "…";
    }

    private static String shortId(String runId) {
        if (runId == null || runId.isEmpty()) return "Run ID 없음";
        return "Run · " + (runId.length() <= 34 ? runId : runId.substring(0, 34) + "…");
    }

    private static String empty(String value) {
        return value == null || value.isEmpty() ? "-" : value;
    }

    private static String time(long value) {
        return value <= 0L ? "-" : DateFormat.getDateTimeInstance().format(new Date(value));
    }
}
