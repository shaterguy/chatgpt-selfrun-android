package com.shaterguy.chatgptselfrun;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.DateFormat;
import java.util.Date;

public final class SelfRunLogMenuActivity extends Activity {
    private SelfRunStore current;
    private SelfRunHistoryStore history;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        current = new SelfRunStore(this);
        history = new SelfRunHistoryStore(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        history.sync(current);
        render();
    }

    private void render() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(Ui.dp(this, 18), Ui.dp(this, 14), Ui.dp(this, 18), Ui.dp(this, 24));
        root.addView(Ui.title(this, "SelfRun 로그"));
        root.addView(Ui.body(this, "현재 실행 중인 작업과 과거 작업의 실행 로그·디버그 로그를 확인하고 각 로그를 파일로 저장할 수 있습니다."));
        root.addView(Ui.row(this,
                Ui.button(this, "새로고침", v -> { history.sync(current); render(); }),
                Ui.button(this, "닫기", v -> finish())));

        JSONArray runs = history.read();
        if (runs.length() == 0) {
            root.addView(Ui.section(this, "로그 없음"));
            root.addView(Ui.body(this, "저장된 SelfRun 작업이 없습니다."));
        } else {
            for (int i = 0; i < runs.length(); i++) {
                JSONObject item = runs.optJSONObject(i);
                if (item != null) root.addView(runCard(item));
            }
        }
        scroll.addView(root);
        Ui.setContent(this, scroll);
    }

    private LinearLayout runCard(JSONObject item) {
        String runId = item.optString("runId");
        boolean isCurrent = runId.equals(current.runId());
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(Ui.dp(this, 12), Ui.dp(this, 10), Ui.dp(this, 12), Ui.dp(this, 10));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, Ui.dp(this, 5), 0, Ui.dp(this, 8));
        card.setLayoutParams(params);

        TextView title = Ui.body(this, (isCurrent ? "현재 작업" : "과거 작업") + " · " + runId);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        card.addView(title);
        card.addView(Ui.body(this,
                "상태: " + item.optString("status", "-")
                        + "\n모드: " + item.optString("mode", "-")
                        + "\n턴: " + item.optInt("turn")
                        + "\n최종 갱신: " + time(item.optLong("updatedAt"))));
        card.addView(Ui.row(this,
                Ui.button(this, "실행 로그", v -> open(runId, SelfRunLogsActivity.KIND_EXECUTION)),
                Ui.button(this, "디버그 로그", v -> open(runId, SelfRunLogsActivity.KIND_DEBUG))));
        return card;
    }

    private void open(String runId, String kind) {
        startActivity(new Intent(this, SelfRunLogsActivity.class)
                .putExtra(SelfRunLogsActivity.EXTRA_RUN_ID, runId)
                .putExtra(SelfRunLogsActivity.EXTRA_KIND, kind));
    }

    private static String time(long value) {
        return value <= 0L ? "-" : DateFormat.getDateTimeInstance().format(new Date(value));
    }
}
