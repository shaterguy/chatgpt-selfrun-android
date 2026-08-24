package com.shaterguy.chatgptselfrun;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

public final class SelfRunLogsActivity extends Activity {
    public static final String EXTRA_RUN_ID = "selfrun.logs.runId";
    public static final String EXTRA_KIND = "selfrun.logs.kind";
    public static final String KIND_EXECUTION = "execution";
    public static final String KIND_DEBUG = "debug";
    private static final int REQUEST_EXPORT = 3401;
    private static final int DISPLAY_LINES = 2_000;
    private static final int EXPORT_LINES = 20_000;

    private final DateTimeFormatter fileFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneId.of("Asia/Seoul"));
    private String runId;
    private String kind;
    private String pendingExportText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        runId = safeRunId(getIntent().getStringExtra(EXTRA_RUN_ID));
        kind = KIND_DEBUG.equals(getIntent().getStringExtra(EXTRA_KIND)) ? KIND_DEBUG : KIND_EXECUTION;
        render();
    }

    private void render() {
        boolean debug = KIND_DEBUG.equals(kind);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int horizontal = Ui.isMedium(this) ? Ui.dp(this, 24) : Ui.dp(this, 14);
        root.setPadding(horizontal, Ui.dp(this, 10), horizontal, Ui.dp(this, 14));

        root.addView(Ui.topBar(this,
                debug ? "디버그 로그" : "실행 로그",
                runId.isEmpty() ? "Run ID 없음" : runId,
                Ui.textButton(this, "닫기", v -> finish())));

        TextView note = Ui.muted(this, debug
                ? "진단용 redacted JSONL · 프롬프트 원문, URL, 쿠키, 토큰, 비밀번호는 기록하지 않습니다."
                : "사용자에게 의미 있는 실행 단계와 상태 전이를 표시합니다.");
        note.setTextIsSelectable(false);
        root.addView(note);

        LinearLayout actions = Ui.actionStrip(this,
                Ui.textButton(this, "새로고침", v -> render()),
                Ui.outlinedButton(this, "로그 저장", v -> exportLogs()));
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        actionParams.bottomMargin = Ui.dp(this, 8);
        root.addView(actions, actionParams);
        root.addView(Ui.divider(this));

        SelfRunRunLog log = new SelfRunRunLog(this);
        List<String> lines = debug ? log.readDebug(runId, DISPLAY_LINES) : log.readExecution(runId, DISPLAY_LINES);
        TextView body = Ui.body(this, lines.isEmpty() ? "저장된 로그가 없습니다." : String.join("\n", lines));
        body.setTextIsSelectable(true);
        body.setTextSize(debug ? 11f : 13f);
        body.setGravity(Gravity.TOP | Gravity.START);
        if (debug) body.setTypeface(Typeface.MONOSPACE);
        body.setPadding(Ui.dp(this, 4), Ui.dp(this, 10), Ui.dp(this, 4), Ui.dp(this, 18));

        ScrollView viewer = new ScrollView(this);
        viewer.setFillViewport(true);
        viewer.addView(body, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(viewer, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        Ui.setContent(this, root);
    }

    private void exportLogs() {
        if (runId.isEmpty()) {
            toast("저장할 Run을 찾을 수 없습니다.");
            return;
        }
        SelfRunRunLog log = new SelfRunRunLog(this);
        List<String> lines = KIND_DEBUG.equals(kind)
                ? log.readDebug(runId, EXPORT_LINES) : log.readExecution(runId, EXPORT_LINES);
        if (lines.isEmpty()) {
            toast("저장할 로그가 없습니다.");
            return;
        }
        pendingExportText = String.join("\n", lines) + "\n";
        boolean debug = KIND_DEBUG.equals(kind);
        String extension = debug ? ".jsonl" : ".txt";
        String name = "chatgpt-selfrun-drive-" + (debug ? "debug" : "execution") + "-" + runId + "-"
                + fileFormatter.format(ZonedDateTime.now(ZoneId.of("Asia/Seoul"))) + extension;
        try {
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                    .setType(debug ? "application/x-ndjson" : "text/plain")
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .putExtra(Intent.EXTRA_TITLE, name);
            startActivityForResult(intent, REQUEST_EXPORT);
        } catch (Exception error) {
            pendingExportText = null;
            toast("로그 저장 화면을 열지 못했습니다.");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_EXPORT || resultCode != RESULT_OK || data == null || data.getData() == null) {
            pendingExportText = null;
            return;
        }
        String text = pendingExportText;
        pendingExportText = null;
        if (text == null) return;
        try (OutputStream output = getContentResolver().openOutputStream(data.getData())) {
            if (output == null) throw new IllegalStateException("output unavailable");
            output.write(text.getBytes(StandardCharsets.UTF_8));
            output.flush();
            toast("SelfRun Drive 로그를 저장했습니다.");
        } catch (Exception error) {
            toast("SelfRun Drive 로그 저장 실패");
        }
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    private static String safeRunId(String value) {
        if (value == null || !value.matches("[A-Za-z0-9._-]{1,80}")) return "";
        return value;
    }
}
