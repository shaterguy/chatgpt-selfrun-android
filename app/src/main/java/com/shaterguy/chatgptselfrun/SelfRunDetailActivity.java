package com.shaterguy.chatgptselfrun;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.DateFormat;
import java.util.Date;

/** SelfRun 3.1 detail view with addressable per-execution conversation history. */
public final class SelfRunDetailActivity extends Activity {
    public static final String EXTRA_RUN_ID = "selfrun.runId";
    private LinearLayout conversations;
    private String detailRunId = "";
    private int historyGeneration;

    @Override protected void onCreate(Bundle savedInstanceState) {
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
        if (runHealth != null && !runHealth.recommendedAction.isEmpty()) page.addView(Ui.body(this, runHealth.recommendedAction));
        page.addView(Ui.section(this, "원본 요청"));
        android.widget.TextView requirement = Ui.body(this, empty(item.optString("requirement")));
        requirement.setTextIsSelectable(true);
        page.addView(requirement);
        page.addView(Ui.section(this, "실행 정보"));
        page.addView(Ui.keyValue(this, "엔진", "SelfRun 3 ledger"));
        page.addView(Ui.keyValue(this, "시작", time(item.optLong("createdAt"))));
        page.addView(Ui.keyValue(this, "마지막 실행", time(item.optLong("updatedAt"))));
        page.addView(Ui.keyValue(this, "모드", item.optString("mode", "-")));
        page.addView(Ui.keyValue(this, "턴", String.valueOf(item.optInt("turn"))));
        page.addView(Ui.keyValue(this, "모델 조합", model(item)));
        if (SelfRunStoppedResume.isEligible(item)) {
            page.addView(Ui.button(this, "중지된 작업 재개", v -> {
                if (SelfRunStoppedResume.request(this, item.optString("runId"))) {
                    v.setEnabled(false);
                    v.setVisibility(android.view.View.GONE);
                }
            }));
        }

        detailRunId = item.optString("runId");
        page.addView(Ui.section(this, "대화 이력"));
        conversations = new LinearLayout(this);
        conversations.setOrientation(LinearLayout.VERTICAL);
        page.addView(conversations);

        page.addView(Ui.section(this, "로그"));
        page.addView(Ui.setting(this, R.drawable.ic_history, "실행 로그", "",
                v -> openLogs(detailRunId, SelfRunLogsActivity.KIND_EXECUTION)));
        page.addView(Ui.setting(this, R.drawable.ic_history, "디버그 로그", "",
                v -> openLogs(detailRunId, SelfRunLogsActivity.KIND_DEBUG)));

        LinearLayout diagnostics = new LinearLayout(this);
        diagnostics.setOrientation(LinearLayout.VERTICAL);
        diagnostics.addView(Ui.keyValue(this, "Run ID", detailRunId));
        diagnostics.addView(Ui.keyValue(this, "Phase", item.optString("phase")));
        diagnostics.addView(Ui.keyValue(this, "프로젝트", item.optString("projectUrl")));
        diagnostics.addView(Ui.keyValue(this, "현재 대화", item.optString("conversationUrl")));
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
        Ui.setContent(this, scroll);
    }

    @Override protected void onResume() {
        super.onResume();
        if (conversations == null) return;
        final int generation = ++historyGeneration;
        conversations.removeAllViews();
        conversations.addView(Ui.muted(this, "대화 이력을 불러오는 중입니다."));
        new Thread(() -> {
            JSONArray entries = new JSONArray();
            String taskMode = "";
            String failure = "";
            try (SelfRun3Ledger ledger = new SelfRun3Ledger(getApplicationContext())) {
                SelfRun3Engine.State state = ledger.load(detailRunId);
                if (state != null) {
                    entries = state.history();
                    taskMode = state.taskMode();
                }
            } catch (RuntimeException error) {
                failure = "대화 이력을 읽지 못했습니다. 다시 열어 확인하세요.";
            }
            final JSONArray result = entries;
            final String selectedMode = taskMode;
            final String message = failure;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || generation != historyGeneration) return;
                conversations.removeAllViews();
                if (!selectedMode.isEmpty()) conversations.addView(Ui.keyValue(this, "작업 모드", selectedMode));
                if (!message.isEmpty()) {
                    conversations.addView(Ui.body(this, message));
                    return;
                }
                if (result.length() == 0) {
                    JSONObject old = new SelfRunHistoryStore(this).get(detailRunId);
                    String url = old == null ? "" : canonicalConversationUrl(old.optString("conversationUrl"));
                    if (!url.isEmpty()) conversations.addView(Ui.button(this, "ChatGPT 대화 열기", v -> openConversation(url)));
                    else conversations.addView(Ui.muted(this, "아직 저장된 대화가 없습니다."));
                    return;
                }
                for (int i = 0; i < result.length(); i++) {
                    JSONObject entry = result.optJSONObject(i);
                    if (entry != null) renderConversation(entry);
                }
            });
        }, "selfrun-history").start();
    }

    private void renderConversation(JSONObject entry) {
        String kind = entry.optString("executionKind", "NORMAL");
        String label = switch (kind) {
            case "PARALLEL_BRANCH" -> "병렬작업 " + entry.optString("branchId");
            case "PARALLEL_MERGE" -> "병렬 결과 통합";
            case "USER_INTERVENTION" -> "사용자 개입";
            case "REPAIR" -> "결과 복구";
            default -> entry.optString("phase");
        };
        conversations.addView(Ui.body(this, "TURN " + entry.optInt("turn") + " · " + label));
        conversations.addView(Ui.muted(this, entry.optString("mode") + " · "
                + empty(entry.optString("model")) + " · " + empty(entry.optString("reasoning"))));
        conversations.addView(Ui.muted(this, time(entry.optLong("submittedAt", entry.optLong("createdAt")))));
        conversations.addView(Ui.muted(this, entry.optString("dispatchStatus") + " · " + entry.optString("resultStatus")));
        if ("USER_ACTION_RESOLVED".equals(entry.optString("resultStatus")))
            conversations.addView(Ui.body(this, "사용자 개입 완료"));
        else if (entry.optBoolean("userIntervention") || "USER_ACTION_REQUIRED".equals(entry.optString("resultStatus")))
            conversations.addView(Ui.body(this, "사용자 조치가 필요한 대화입니다."));
        String url = canonicalConversationUrl(entry.optString("conversationUrl"));
        if (!url.isEmpty()) conversations.addView(Ui.button(this, "ChatGPT 대화 열기", v -> openConversation(url)));
        else conversations.addView(Ui.muted(this, "대화 주소 확인 중"));
        conversations.addView(Ui.divider(this));
    }

    static String canonicalConversationUrl(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        try {
            Uri uri = Uri.parse(raw);
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || !("chatgpt.com".equalsIgnoreCase(uri.getHost()) || "www.chatgpt.com".equalsIgnoreCase(uri.getHost()))
                    || uri.getUserInfo() != null || (uri.getPort() != -1 && uri.getPort() != 443)) return "";
            String id = SelfRunScript.conversationId(raw);
            if (id.isEmpty() || !id.matches("[A-Za-z0-9_-]{1,200}")) return "";
            return "https://chatgpt.com/c/" + id;
        } catch (RuntimeException invalid) {
            return "";
        }
    }

    private void openConversation(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (android.content.ActivityNotFoundException error) {
            Toast.makeText(this, "대화를 열 수 있는 앱이나 브라우저가 없습니다.", Toast.LENGTH_LONG).show();
        }
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

    private static String error(JSONObject item) {
        String code = item.optString("lastErrorCode");
        return code.isEmpty() ? "없음" : code + " · " + empty(item.optString("lastErrorMessage"));
    }

    private static String preview(String text) {
        if (text == null || text.trim().isEmpty()) return "요청 내용 없음";
        String oneLine = text.replace('\n', ' ').replace('\r', ' ').trim();
        return oneLine.length() <= 120 ? oneLine : oneLine.substring(0, 120) + "…";
    }

    private static String empty(String value) { return value == null || value.isEmpty() ? "-" : value; }
    private static String time(long value) {
        return value <= 0L ? "-" : DateFormat.getDateTimeInstance().format(new Date(value));
    }
}
