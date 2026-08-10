package com.shaterguy.chatgptselfrun;

import android.app.Activity;
import android.graphics.Typeface;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

public final class SelfRunLogsActivity extends Activity {
    public static final String EXTRA_RUN_ID = "selfrun.logs.runId";
    public static final String EXTRA_KIND = "selfrun.logs.kind";
    public static final String KIND_EXECUTION = "execution";
    public static final String KIND_DEBUG = "debug";

    private String runId;
    private String kind;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        runId = getIntent().getStringExtra(EXTRA_RUN_ID);
        kind = getIntent().getStringExtra(EXTRA_KIND);
        if (!KIND_DEBUG.equals(kind)) kind = KIND_EXECUTION;
        render();
    }

    private void render() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(Ui.dp(this, 18), Ui.dp(this, 14), Ui.dp(this, 18), Ui.dp(this, 24));
        scroll.addView(root);
        root.addView(Ui.title(this, KIND_DEBUG.equals(kind) ? "SelfRun 디버그 로그" : "SelfRun 실행 로그"));
        root.addView(Ui.body(this, runId == null ? "-" : runId));
        root.addView(Ui.row(this,
                Ui.button(this, "뒤로", v -> finish()),
                Ui.button(this, "새로고침", v -> render())));

        SelfRunRunLog log = new SelfRunRunLog(this);
        List<String> lines = KIND_DEBUG.equals(kind)
                ? log.readDebug(runId, 1_000) : log.readExecution(runId, 1_000);
        TextView body = Ui.body(this, lines.isEmpty() ? "저장된 로그가 없습니다." : String.join("\n", lines));
        body.setTextIsSelectable(true);
        if (KIND_DEBUG.equals(kind)) body.setTypeface(Typeface.MONOSPACE);
        root.addView(body);
        Ui.setContent(this, scroll);
    }
}
