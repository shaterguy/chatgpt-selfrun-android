package com.shaterguy.chatgptselfrun;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;
import org.json.JSONTokener;

/** Visible calibration WebView. It intentionally uses the same mobile profile as background automation. */
public final class WebUiCalibrationActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable pollRunnable = this::pollCapture;
    private WebUiCalibrationStore store;
    private WebView webView;
    private TextView status;
    private Button confirmButton;
    private String activePurpose = "";
    private String lastCandidate = "";
    private boolean resumed;

    @Override
    @SuppressWarnings("SetJavaScriptEnabled")
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new WebUiCalibrationStore(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(Ui.dp(this, 10), Ui.dp(this, 8), Ui.dp(this, 10), Ui.dp(this, 8));
        root.addView(Ui.title(this, "웹 UI 보정"));
        root.addView(Ui.body(this,
                "이 화면에서 직접 ChatGPT를 조작해 자동화 위치를 다시 확보합니다. 보정 WebView와 백그라운드 자동화 WebView는 같은 모바일 표시 정책을 사용합니다. 테스트 문구 내용은 보정 로그에 저장하지 않습니다."));

        status = Ui.body(this, statusText(""));
        root.addView(status);
        root.addView(Ui.row(this,
                task("일반채팅 메뉴", WebUiCalibrationStore.PURPOSE_MODE_CHAT),
                task("Work 메뉴", WebUiCalibrationStore.PURPOSE_MODE_WORK)));
        root.addView(Ui.row(this,
                task("Work 모델", WebUiCalibrationStore.PURPOSE_WORK_MODEL),
                task("Work 추론", WebUiCalibrationStore.PURPOSE_WORK_REASONING)));
        root.addView(Ui.row(this,
                task("프로젝트 새대화 제출", WebUiCalibrationStore.PURPOSE_PROJECT_NEW_CHAT),
                task("일반 새대화 제출", WebUiCalibrationStore.PURPOSE_GENERAL_NEW_CHAT)));

        confirmButton = Ui.button(this, "최근 터치 위치 저장", v -> confirmSimple());
        confirmButton.setEnabled(false);
        root.addView(Ui.row(this,
                confirmButton,
                Ui.button(this, "보정 로그", v -> showLogs())));
        root.addView(Ui.row(this,
                Ui.button(this, "뒤로", v -> { if (webView.canGoBack()) webView.goBack(); }),
                Ui.button(this, "ChatGPT 홈", v -> webView.loadUrl(SelfRunScript.GENERAL_CHAT_URL)),
                Ui.button(this, "새로고침", v -> webView.reload()),
                Ui.button(this, "닫기", v -> finish())));
        root.addView(Ui.button(this, "보정값 전체 초기화", v -> clearAll()));

        webView = new WebView(this);
        WebViewConfig.applyAutomation(webView);
        webView.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                seedProfile();
                if (!activePurpose.isEmpty()) armCurrentPurpose(120L);
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String host = request.getUrl() == null ? "" : request.getUrl().getHost();
                return !("chatgpt.com".equals(host) || "www.chatgpt.com".equals(host));
            }
        });
        root.addView(webView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        Ui.setContent(this, root);
        webView.loadUrl(SelfRunScript.GENERAL_CHAT_URL);
    }

    private Button task(String label, String purpose) {
        return Ui.button(this, label, v -> startPurpose(purpose));
    }

    private void startPurpose(String purpose) {
        activePurpose = purpose;
        lastCandidate = "";
        confirmButton.setEnabled(false);
        store.record(purpose, "CAPTURE_ARMED", "mobile_profile");
        status.setText(statusText(instruction(purpose)));
        if (webView != null) {
            webView.evaluateJavascript(WebUiCalibrationDom.clearCapture(), ignored -> armCurrentPurpose(50L));
        }
    }

    private void armCurrentPurpose(long delayMs) {
        String purpose = activePurpose;
        if (purpose.isEmpty() || webView == null) return;
        handler.postDelayed(() -> {
            if (!purpose.equals(activePurpose) || webView == null) return;
            webView.evaluateJavascript(WebUiCalibrationDom.install(purpose), ignored -> schedulePoll());
        }, delayMs);
    }

    private void schedulePoll() {
        handler.removeCallbacks(pollRunnable);
        if (resumed && !activePurpose.isEmpty()) handler.postDelayed(pollRunnable, 350L);
    }

    private void pollCapture() {
        if (!resumed || activePurpose.isEmpty() || webView == null) return;
        final String purpose = activePurpose;
        webView.evaluateJavascript(WebUiCalibrationDom.read(purpose), raw -> {
            if (!purpose.equals(activePurpose)) return;
            JSONObject capture = jsonResult(raw);
            if (capture == null) { schedulePoll(); return; }
            if (capture.optBoolean("ready", false)) {
                if (store.saveCapture(purpose, capture)) {
                    activePurpose = "";
                    lastCandidate = "";
                    confirmButton.setEnabled(false);
                    seedProfile();
                    status.setText(statusText("확보 완료: " + label(purpose)));
                    Toast.makeText(this, label(purpose) + " 보정값 저장 완료", Toast.LENGTH_SHORT).show();
                } else {
                    store.record(purpose, "CAPTURE_REJECTED", "invalid_capture");
                    status.setText(statusText("보정값 검증 실패 · 같은 항목을 다시 수행하세요."));
                    schedulePoll();
                }
                return;
            }
            JSONObject target = capture.optJSONObject("target");
            if (target != null && !isSubmitPurpose(purpose)) {
                String fingerprint = target.toString();
                if (!fingerprint.equals(lastCandidate)) {
                    lastCandidate = fingerprint;
                    store.record(purpose, "CANDIDATE_TOUCHED", "awaiting_native_confirmation");
                }
                confirmButton.setEnabled(true);
                status.setText(statusText("최근 터치가 잡혔습니다. 실제 목표를 터치한 것이 맞으면 ‘최근 터치 위치 저장’을 누르세요."));
            }
            schedulePoll();
        });
    }

    private void confirmSimple() {
        if (activePurpose.isEmpty() || isSubmitPurpose(activePurpose) || webView == null || lastCandidate.isEmpty()) return;
        String purpose = activePurpose;
        store.record(purpose, "CANDIDATE_CONFIRM_REQUESTED", "native_button");
        webView.evaluateJavascript(WebUiCalibrationDom.finalizeSimple(purpose), ignored -> {
            if (purpose.equals(activePurpose)) handler.postDelayed(pollRunnable, 50L);
        });
    }

    private void seedProfile() {
        if (webView == null) return;
        webView.evaluateJavascript(store.seedScript(), ignored -> {});
    }

    private void showLogs() {
        new AlertDialog.Builder(this)
                .setTitle("웹 UI 보정 로그")
                .setMessage(store.logText(80))
                .setPositiveButton("닫기", null)
                .show();
    }

    private void clearAll() {
        activePurpose = "";
        lastCandidate = "";
        confirmButton.setEnabled(false);
        store.clearAll();
        store.record("SYSTEM", "PROFILE_RESET", "user_requested");
        if (webView != null) {
            webView.evaluateJavascript("(()=>{try{localStorage.removeItem('" + WebUiCalibrationStore.STORAGE_KEY
                    + "');sessionStorage.removeItem('selfrun-drive:ui-calibration:capture');return 'OK';}catch(e){return 'ERROR';}})()", ignored -> {});
        }
        status.setText(statusText("모든 보정값을 초기화했습니다."));
    }

    private String statusText(String note) {
        StringBuilder out = new StringBuilder();
        if (!note.isEmpty()) out.append(note).append("\n\n");
        out.append("일반채팅 메뉴: ").append(store.purposeStatus(WebUiCalibrationStore.PURPOSE_MODE_CHAT));
        out.append("\nWork 메뉴: ").append(store.purposeStatus(WebUiCalibrationStore.PURPOSE_MODE_WORK));
        out.append("\nWork 모델: ").append(store.purposeStatus(WebUiCalibrationStore.PURPOSE_WORK_MODEL));
        out.append("\nWork 추론: ").append(store.purposeStatus(WebUiCalibrationStore.PURPOSE_WORK_REASONING));
        out.append("\n프로젝트 새대화 제출: ").append(store.purposeStatus(WebUiCalibrationStore.PURPOSE_PROJECT_NEW_CHAT));
        out.append("\n일반 새대화 제출: ").append(store.purposeStatus(WebUiCalibrationStore.PURPOSE_GENERAL_NEW_CHAT));
        return out.toString();
    }

    private static String instruction(String purpose) {
        return switch (purpose) {
            case WebUiCalibrationStore.PURPOSE_MODE_CHAT -> "일반채팅 메뉴의 실제 목표 항목을 한 번 터치한 뒤 최근 터치 위치를 저장하세요.";
            case WebUiCalibrationStore.PURPOSE_MODE_WORK -> "Work 메뉴의 실제 목표 항목을 한 번 터치한 뒤 최근 터치 위치를 저장하세요.";
            case WebUiCalibrationStore.PURPOSE_WORK_MODEL -> "Work에 들어가 실제 추론 모델 선택부를 한 번 터치한 뒤 최근 터치 위치를 저장하세요.";
            case WebUiCalibrationStore.PURPOSE_WORK_REASONING -> "Work에 들어가 실제 추론 정도 선택부를 한 번 터치한 뒤 최근 터치 위치를 저장하세요.";
            case WebUiCalibrationStore.PURPOSE_PROJECT_NEW_CHAT -> "아무 프로젝트에서 새 대화로 들어간 뒤 임의 문구를 입력하고 제출하세요. 입력창·전송·진입 위치를 자동 확보합니다.";
            case WebUiCalibrationStore.PURPOSE_GENERAL_NEW_CHAT -> "ChatGPT 메인 새 대화에서 임의 문구를 입력하고 제출하세요. 입력창·전송 위치를 자동 확보합니다.";
            default -> "";
        };
    }

    private static String label(String purpose) {
        return switch (purpose) {
            case WebUiCalibrationStore.PURPOSE_MODE_CHAT -> "일반채팅 메뉴";
            case WebUiCalibrationStore.PURPOSE_MODE_WORK -> "Work 메뉴";
            case WebUiCalibrationStore.PURPOSE_WORK_MODEL -> "Work 모델";
            case WebUiCalibrationStore.PURPOSE_WORK_REASONING -> "Work 추론";
            case WebUiCalibrationStore.PURPOSE_PROJECT_NEW_CHAT -> "프로젝트 새대화 제출";
            case WebUiCalibrationStore.PURPOSE_GENERAL_NEW_CHAT -> "일반 새대화 제출";
            default -> purpose;
        };
    }

    private static boolean isSubmitPurpose(String purpose) {
        return WebUiCalibrationStore.PURPOSE_PROJECT_NEW_CHAT.equals(purpose)
                || WebUiCalibrationStore.PURPOSE_GENERAL_NEW_CHAT.equals(purpose);
    }

    private static JSONObject jsonResult(String raw) {
        try {
            Object outer = new JSONTokener(raw == null ? "" : raw).nextValue();
            String value = outer instanceof String ? (String) outer : String.valueOf(outer);
            if (value == null || value.isEmpty()) return null;
            return new JSONObject(value);
        } catch (Throwable ignored) { return null; }
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        if (!activePurpose.isEmpty()) schedulePoll();
    }

    @Override protected void onPause() {
        resumed = false;
        handler.removeCallbacks(pollRunnable);
        super.onPause();
    }

    @Override protected void onDestroy() {
        resumed = false;
        handler.removeCallbacksAndMessages(null);
        if (webView != null) {
            CookieManager.getInstance().flush();
            webView.stopLoading();
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }
}
