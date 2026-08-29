package com.shaterguy.chatgptselfrun;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONTokener;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** Read-only signal vocabulary management plus one-shot real submission capture. */
public final class ProfileRegistryActivity extends Activity {
    private static final int REQUEST_EXPORT_WORK = 7201;
    private static final long CAPTURE_POLL_MS = 350L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable pollRunnable = this::pollCapture;
    private LinearLayout chatList;
    private LinearLayout workList;
    private ScrollView registryScroll;
    private TextView status;
    private Button cancelCapture;
    private WebView webView;
    private ProfileRegistry.Mode captureMode;
    private boolean pageReady;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ProfileRegistry.initialize(this);
        createViews();
    }

    @Override protected void onResume() {
        super.onResume();
        if (captureMode == null) renderRegistry();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(pollRunnable);
        if (webView != null) {
            webView.evaluateJavascript(RequestProfileScript.cancelCapture(), ignored -> {});
            webView.destroy();
        }
        super.onDestroy();
    }

    private void createViews() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        LinearLayout page = Ui.page(this);
        page.addView(Ui.topBar(this, "모델 및 추론수준 관리",
                "운영 신호와 실제 ChatGPT request profile을 함께 관리합니다.",
                Ui.textButton(this, "닫기", v -> finish())));
        status = Ui.body(this, "");
        page.addView(status);

        registryScroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        registryScroll.addView(content);

        content.addView(Ui.section(this, "일반 Chat"));
        content.addView(Ui.muted(this,
                "Chat은 현재 프로토콜에서 REASONING 신호만 사용합니다. 표시명은 읽기 전용이며 신호명 수정 기능은 없습니다."));
        content.addView(Ui.outlinedButton(this, "새로운 조합 등록",
                v -> startCapture(ProfileRegistry.Mode.CHAT)));
        chatList = new LinearLayout(this);
        chatList.setOrientation(LinearLayout.VERTICAL);
        content.addView(chatList);

        content.addView(Ui.section(this, "Work"));
        content.addView(Ui.muted(this,
                "TURN_COMPLETED의 MODEL/REASONING 신호와 실제 outgoing request 조합을 동일 Registry에서 해석합니다."));
        content.addView(Ui.actionStrip(this,
                Ui.outlinedButton(this, "새로운 조합 등록", v -> startCapture(ProfileRegistry.Mode.WORK)),
                Ui.outlinedButton(this, "등록 조합 내보내기", v -> startExportWork())));
        workList = new LinearLayout(this);
        workList.setOrientation(LinearLayout.VERTICAL);
        content.addView(workList);
        page.addView(registryScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        cancelCapture = Ui.textButton(this, "캡처 취소", v -> endCapture("캡처를 취소했습니다."));
        cancelCapture.setVisibility(View.GONE);
        page.addView(cancelCapture);

        webView = new WebView(this);
        WebViewConfig.applyAutomation(webView);
        webView.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                pageReady = trustedUrl(url);
                if (pageReady) {
                    syncRegistryToWeb();
                    if (captureMode != null) armCaptureNow();
                }
            }

            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request == null ? null : request.getUrl();
                String host = uri == null ? "" : uri.getHost();
                return !("chatgpt.com".equals(host) || "www.chatgpt.com".equals(host));
            }
        });
        webView.setVisibility(View.GONE);
        page.addView(webView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        root.addView(page, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        Ui.setContent(this, root);
        renderRegistry();
        webView.loadUrl(SelfRunScript.GENERAL_CHAT_URL);
    }

    private void renderRegistry() {
        if (chatList == null || workList == null) return;
        chatList.removeAllViews();
        workList.removeAllViews();
        for (ProfileRegistry.Profile profile : ProfileRegistry.listChat()) {
            String title = profile.displayLabel() + " · 신호 REASONING=" + profile.signalReasoning;
            String supporting = "실제 조합 " + profile.actualCombination()
                    + (profile.builtIn ? " · 기본 등록" : " · 사용자 등록");
            chatList.addView(Ui.settingsRow(this, title, supporting,
                    Ui.dangerButton(this, "삭제", v -> confirmDelete(profile))));
            chatList.addView(Ui.divider(this));
        }
        if (ProfileRegistry.listChat().isEmpty()) {
            chatList.addView(Ui.muted(this, "등록된 Chat profile이 없습니다."));
        }

        for (ProfileRegistry.Profile profile : ProfileRegistry.listWork()) {
            String title = "MODEL=" + profile.signalModel + "  REASONING=" + profile.signalReasoning;
            String supporting = "실제 조합 " + profile.actualCombination()
                    + (profile.builtIn ? " · 기본 등록" : " · 사용자 등록");
            workList.addView(Ui.settingsRow(this, title, supporting,
                    Ui.dangerButton(this, "삭제", v -> confirmDelete(profile))));
            workList.addView(Ui.divider(this));
        }
        if (ProfileRegistry.listWork().isEmpty()) {
            workList.addView(Ui.muted(this, "등록된 Work profile이 없습니다."));
        }
        status.setText(ProfileRegistry.storageHealthy()
                ? "Registry schema " + ProfileRegistry.SCHEMA + " · 등록값은 앱 업데이트 후에도 유지됩니다."
                : "Registry 저장 데이터가 유효하지 않아 fail-closed 상태입니다.");
    }

    private void confirmDelete(ProfileRegistry.Profile profile) {
        new AlertDialog.Builder(this)
                .setTitle("등록 조합 삭제")
                .setMessage((profile.mode == ProfileRegistry.Mode.WORK
                        ? "MODEL=" + profile.signalModel + " REASONING=" + profile.signalReasoning
                        : "REASONING=" + profile.signalReasoning)
                        + "\n실제 조합 " + profile.actualCombination()
                        + "\n\n삭제 후 같은 신호는 즉시 미지원 처리됩니다. 이름을 바꾸려면 삭제 후 다시 등록하세요.")
                .setNegativeButton("취소", null)
                .setPositiveButton("삭제", (dialog, which) -> {
                    if (!ProfileRegistry.delete(profile.fingerprint)) {
                        Toast.makeText(this, "삭제 내용을 저장하지 못했습니다.", Toast.LENGTH_LONG).show();
                        return;
                    }
                    syncRegistryToWeb();
                    renderRegistry();
                }).show();
    }

    private void startCapture(ProfileRegistry.Mode mode) {
        captureMode = mode;
        handler.removeCallbacks(pollRunnable);
        registryScroll.setVisibility(View.GONE);
        webView.setVisibility(View.VISIBLE);
        cancelCapture.setVisibility(View.VISIBLE);
        status.setText((mode == ProfileRegistry.Mode.CHAT ? "CHAT" : "WORK")
                + " capture 대기 · ChatGPT에서 원하는 모델/추론을 직접 선택한 뒤 짧은 프롬프트를 실제로 한 번 전송하세요. 메뉴 클릭만으로는 캡처되지 않습니다.");
        if (pageReady) armCaptureNow();
        else webView.loadUrl(SelfRunScript.GENERAL_CHAT_URL);
    }

    private void armCaptureNow() {
        ProfileRegistry.Mode mode = captureMode;
        if (mode == null || !pageReady) return;
        webView.evaluateJavascript(RequestProfileScript.armCapture(
                mode == ProfileRegistry.Mode.CHAT ? "chat" : "work"), value -> {
            if (captureMode == mode) handler.postDelayed(pollRunnable, CAPTURE_POLL_MS);
        });
    }

    private void pollCapture() {
        if (captureMode == null || webView == null) return;
        webView.evaluateJavascript(RequestProfileScript.consumeCapture(), raw -> {
            if (captureMode == null) return;
            try {
                Object outer = new JSONTokener(raw == null ? "null" : raw).nextValue();
                String inner = outer == null ? "" : String.valueOf(outer);
                if (inner.isEmpty() || "null".equals(inner)) {
                    handler.postDelayed(pollRunnable, CAPTURE_POLL_MS);
                    return;
                }
                ProfileRegistry.CapturedProfile captured = ProfileRegistry.parseCaptured(inner);
                handleCaptured(captured);
            } catch (Exception error) {
                endCapture("캡처 결과를 안전하게 해석하지 못했습니다. 다시 등록하세요.");
            }
        });
    }

    private void handleCaptured(ProfileRegistry.CapturedProfile captured) {
        handler.removeCallbacks(pollRunnable);
        ProfileRegistry.Profile duplicate = ProfileRegistry.findByFingerprint(captured.mode, captured.fingerprint);
        if (duplicate != null) {
            String signal = duplicate.mode == ProfileRegistry.Mode.WORK
                    ? "MODEL=" + duplicate.signalModel + " REASONING=" + duplicate.signalReasoning
                    : "REASONING=" + duplicate.signalReasoning;
            new AlertDialog.Builder(this)
                    .setTitle("이미 등록된 조합입니다")
                    .setMessage("신호 " + signal + "\n실제 조합 " + duplicate.actualCombination())
                    .setPositiveButton("확인", (dialog, which) -> endCapture("기존 등록 조합을 사용합니다."))
                    .setOnCancelListener(dialog -> endCapture("기존 등록 조합을 사용합니다."))
                    .show();
            return;
        }
        showRegistrationDialog(captured);
    }

    private void showRegistrationDialog(ProfileRegistry.CapturedProfile captured) {
        LinearLayout fields = new LinearLayout(this);
        fields.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 18);
        fields.setPadding(pad, 0, pad, 0);
        fields.addView(Ui.body(this, "실제 캡처: " + captured.actualCombination()));

        EditText model = null;
        if (captured.mode == ProfileRegistry.Mode.WORK) {
            model = new EditText(this);
            model.setHint("모델 신호명 (예: nova)");
            model.setSingleLine(true);
            model.setInputType(InputType.TYPE_CLASS_TEXT);
            fields.addView(model);
        }
        EditText reasoning = new EditText(this);
        reasoning.setHint(captured.mode == ProfileRegistry.Mode.WORK
                ? "추론 신호명 (예: extreme)" : "추론 신호명");
        reasoning.setSingleLine(true);
        reasoning.setInputType(InputType.TYPE_CLASS_TEXT);
        fields.addView(reasoning);

        EditText finalModel = model;
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(captured.mode == ProfileRegistry.Mode.WORK ? "Work 신호 등록" : "Chat 신호 등록")
                .setView(fields)
                .setNegativeButton("취소", (d, which) -> endCapture("등록을 취소했습니다."))
                .setPositiveButton("등록", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                String modelSignal = finalModel == null ? "" : finalModel.getText().toString();
                ProfileRegistry.RegisterResult result = ProfileRegistry.registerCaptured(
                        captured, modelSignal, reasoning.getText().toString());
                Toast.makeText(this,
                        ProfileRegistry.RegisterResult.DUPLICATE_PROFILE.equals(result.status)
                                ? "이미 등록된 실제 profile입니다." : "Profile Registry에 등록했습니다.",
                        Toast.LENGTH_SHORT).show();
                dialog.dismiss();
                syncRegistryToWeb();
                endCapture("등록 내용을 즉시 반영했습니다.");
            } catch (IllegalArgumentException | IllegalStateException error) {
                Toast.makeText(this, error.getMessage(), Toast.LENGTH_LONG).show();
            }
        }));
        dialog.setOnCancelListener(d -> endCapture("등록을 취소했습니다."));
        dialog.show();
    }

    private void endCapture(String note) {
        handler.removeCallbacks(pollRunnable);
        if (webView != null) webView.evaluateJavascript(RequestProfileScript.cancelCapture(), ignored -> {});
        captureMode = null;
        webView.setVisibility(View.GONE);
        cancelCapture.setVisibility(View.GONE);
        registryScroll.setVisibility(View.VISIBLE);
        renderRegistry();
        if (note != null && !note.isEmpty()) status.setText(note);
    }

    private void syncRegistryToWeb() {
        if (!pageReady || webView == null) return;
        webView.evaluateJavascript(RequestProfileScript.syncRegistry(), ignored -> {});
    }

    private void startExportWork() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_TITLE, "selfrun-work-profile-registry-v1.json");
        startActivityForResult(intent, REQUEST_EXPORT_WORK);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_EXPORT_WORK || resultCode != RESULT_OK
                || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try (OutputStream output = getContentResolver().openOutputStream(uri, "w")) {
            if (output == null) throw new IllegalStateException("export output unavailable");
            output.write(ProfileRegistry.exportWorkJson(BuildConfig.VERSION_NAME)
                    .getBytes(StandardCharsets.UTF_8));
            output.flush();
            offerShare(uri);
        } catch (Exception error) {
            Toast.makeText(this, "Work Registry JSON을 저장하지 못했습니다.", Toast.LENGTH_LONG).show();
        }
    }

    private void offerShare(Uri uri) {
        new AlertDialog.Builder(this)
                .setTitle("Work Registry JSON 저장 완료")
                .setMessage("파일을 저장했습니다. 지금 공유할 수도 있습니다.")
                .setNegativeButton("닫기", null)
                .setPositiveButton("공유", (dialog, which) -> {
                    Intent share = new Intent(Intent.ACTION_SEND);
                    share.setType("application/json");
                    share.putExtra(Intent.EXTRA_STREAM, uri);
                    share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(Intent.createChooser(share, "Work Registry JSON 공유"));
                }).show();
    }

    private static boolean trustedUrl(String url) {
        try {
            Uri uri = Uri.parse(url == null ? "" : url);
            String host = uri.getHost();
            return "https".equals(uri.getScheme())
                    && ("chatgpt.com".equals(host) || "www.chatgpt.com".equals(host));
        } catch (Exception error) {
            return false;
        }
    }
}
