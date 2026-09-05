package com.shaterguy.chatgptselfrun;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** Visible calibration WebView. It intentionally uses the same mobile profile as background automation. */
public final class WebUiCalibrationActivity extends Activity {
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter LOG_TIME = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm:ss").withZone(KST);
    private static final int REQUEST_EXPORT_CALIBRATION = 6101;
    private static final int REQUEST_IMPORT_CALIBRATION = 6102;
    private static final String[] PURPOSES = new String[] {
            WebUiCalibrationStore.PURPOSE_MODE_CHAT,
            WebUiCalibrationStore.PURPOSE_MODE_WORK,
            WebUiCalibrationStore.PURPOSE_GENERAL_BOOTSTRAP_WORK_MODEL,
            WebUiCalibrationStore.PURPOSE_GENERAL_BOOTSTRAP_WORK_REASONING,
            WebUiCalibrationStore.PURPOSE_GENERAL_CONTINUATION_WORK_MODEL,
            WebUiCalibrationStore.PURPOSE_GENERAL_CONTINUATION_WORK_REASONING,
            WebUiCalibrationStore.PURPOSE_PROJECT_BOOTSTRAP_WORK_MODEL,
            WebUiCalibrationStore.PURPOSE_PROJECT_BOOTSTRAP_WORK_REASONING,
            WebUiCalibrationStore.PURPOSE_PROJECT_CONTINUATION_WORK_MODEL,
            WebUiCalibrationStore.PURPOSE_PROJECT_CONTINUATION_WORK_REASONING,
            WebUiCalibrationStore.PURPOSE_PROJECT_NEW_CHAT,
            WebUiCalibrationStore.PURPOSE_GENERAL_NEW_CHAT
    };

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable pollRunnable = this::pollCapture;
    private WebUiCalibrationStore store;
    private WebView webView;
    private TextView status;
    private Button selectButton;
    private Button manageButton;
    private Button cancelButton;
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
        int side = Ui.isMedium(this) ? Ui.dp(this, 20) : Ui.dp(this, 10);
        root.setPadding(side, Ui.dp(this, 6), side, 0);

        manageButton = Ui.textButton(this, "관리", v -> showManageDialog());
        root.addView(Ui.toolbar(this, "화면 보정", manageButton));

        status = Ui.body(this, "보정 항목을 선택하세요");
        status.setTextIsSelectable(false);
        root.addView(status);

        selectButton = Ui.button(this, "보정 항목 선택", v -> showPurposePicker());
        cancelButton = Ui.textButton(this, "취소", v -> cancelPurpose());
        confirmButton = Ui.button(this, "이 위치 저장", v -> confirmSimple());
        LinearLayout controls = Ui.actionStrip(this, selectButton, cancelButton, confirmButton);
        LinearLayout.LayoutParams controlsParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        controlsParams.setMargins(0, Ui.dp(this, 2), 0, Ui.dp(this, 8));
        root.addView(controls, controlsParams);

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
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        Ui.setContent(this, root);
        updateControls("");
        webView.loadUrl(SelfRunScript.GENERAL_CHAT_URL);
    }

    private void showPurposePicker() {
        String[] items = new String[PURPOSES.length];
        for (int i = 0; i < PURPOSES.length; i++) {
            String purpose = PURPOSES[i];
            items[i] = label(purpose) + " · " + store.purposeStatus(purpose);
        }
        new AlertDialog.Builder(this)
                .setTitle("보정 항목 선택")
                .setItems(items, (dialog, which) -> startPurpose(PURPOSES[which]))
                .setNegativeButton("닫기", null)
                .show();
    }

    private void showManageDialog() {
        String[] items = new String[] {
                "보정 로그", "보정값 내보내기", "보정값 가져오기", "ChatGPT 홈", "새로고침", "보정값 전체 초기화", "화면 닫기"};
        new AlertDialog.Builder(this)
                .setTitle("웹 UI 보정 관리")
                .setItems(items, (dialog, which) -> {
                    switch (which) {
                        case 0 -> showLogs();
                        case 1 -> startExportCalibration();
                        case 2 -> startImportCalibration();
                        case 3 -> webView.loadUrl(SelfRunScript.GENERAL_CHAT_URL);
                        case 4 -> webView.reload();
                        case 5 -> confirmClearAll();
                        case 6 -> finish();
                        default -> { }
                    }
                })
                .setNegativeButton("닫기", null)
                .show();
    }

    private void startExportCalibration() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_TITLE, WebUiCalibrationBackupCodec.DEFAULT_FILE_NAME);
        startActivityForResult(intent, REQUEST_EXPORT_CALIBRATION);
    }

    private void startImportCalibration() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        startActivityForResult(intent, REQUEST_IMPORT_CALIBRATION);
    }

    private void confirmClearAll() {
        new AlertDialog.Builder(this)
                .setTitle("보정값 전체 초기화")
                .setMessage("저장된 웹 UI 보정값과 런타임 매칭 로그를 모두 초기화합니다.")
                .setNegativeButton("취소", null)
                .setPositiveButton("초기화", (dialog, which) -> clearAll())
                .show();
    }

    private void startPurpose(String purpose) {
        activePurpose = purpose;
        lastCandidate = "";
        store.record(purpose, "CAPTURE_ARMED", "mobile_profile");
        updateControls("");
        if (webView != null) {
            webView.evaluateJavascript(WebUiCalibrationDom.clearCapture(), ignored -> armCurrentPurpose(50L));
        }
    }

    private void cancelPurpose() {
        if (activePurpose.isEmpty()) return;
        String purpose = activePurpose;
        activePurpose = "";
        lastCandidate = "";
        handler.removeCallbacks(pollRunnable);
        store.record(purpose, "CAPTURE_CANCELLED", "user_requested");
        if (webView != null) {
            webView.evaluateJavascript(WebUiCalibrationDom.clearCapture(), ignored -> {});
        }
        updateControls("보정 취소됨");
    }

    private void updateControls(String note) {
        boolean active = !activePurpose.isEmpty();
        selectButton.setVisibility(active ? View.GONE : View.VISIBLE);
        manageButton.setVisibility(active ? View.GONE : View.VISIBLE);
        cancelButton.setVisibility(active ? View.VISIBLE : View.GONE);
        boolean canConfirm = active && !isSubmitPurpose(activePurpose) && !lastCandidate.isEmpty();
        confirmButton.setVisibility(canConfirm ? View.VISIBLE : View.GONE);
        confirmButton.setEnabled(canConfirm);

        if (!note.isEmpty()) {
            status.setText(active ? label(activePurpose) + " · " + note : "웹 UI 보정 · " + note);
        } else if (active) {
            status.setText(label(activePurpose) + " · " + capturePrompt(activePurpose));
        } else {
            status.setText("보정 항목을 선택하세요");
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
                    seedProfile();
                    updateControls("확보 완료: " + label(purpose));
                    Toast.makeText(this, label(purpose) + " 보정값 저장 완료", Toast.LENGTH_SHORT).show();
                } else {
                    store.record(purpose, "CAPTURE_REJECTED", "invalid_capture");
                    updateControls("저장 실패 · 다시 터치하세요");
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
                updateControls("위치 감지됨");
            }
            schedulePoll();
        });
    }

    private void confirmSimple() {
        if (activePurpose.isEmpty() || isSubmitPurpose(activePurpose) || webView == null || lastCandidate.isEmpty()) return;
        String purpose = activePurpose;
        store.record(purpose, "CANDIDATE_CONFIRM_REQUESTED", "native_button");
        confirmButton.setEnabled(false);
        webView.evaluateJavascript(WebUiCalibrationDom.finalizeSimple(purpose), ignored -> {
            if (purpose.equals(activePurpose)) handler.postDelayed(pollRunnable, 50L);
        });
    }

    private void seedProfile() {
        if (webView == null) return;
        webView.evaluateJavascript(store.seedScript(), ignored -> {});
    }

    private void showLogs() {
        if (webView == null) {
            showLogDialog(store.logText(80), "런타임 매칭 로그를 읽을 WebView가 없습니다.");
            return;
        }
        webView.evaluateJavascript(WebUiCalibrationDom.readRuntimeLog(), raw ->
                showLogDialog(store.logText(80), runtimeLogText(jsString(raw))));
    }

    private void showLogDialog(String captureLog, String runtimeLog) {
        new AlertDialog.Builder(this)
                .setTitle("웹 UI 보정 로그")
                .setMessage("[보정 이력]\n" + captureLog + "\n\n[런타임 MATCH/MISS]\n" + runtimeLog)
                .setPositiveButton("닫기", null)
                .show();
    }

    private static String runtimeLogText(String raw) {
        try {
            JSONArray items = new JSONArray(raw == null || raw.isEmpty() ? "[]" : raw);
            StringBuilder out = new StringBuilder();
            int start = Math.max(0, items.length() - 80);
            for (int i = start; i < items.length(); i++) {
                JSONObject item = items.optJSONObject(i);
                if (item == null) continue;
                if (out.length() > 0) out.append('\n');
                long at = item.optLong("at", 0L);
                out.append(at > 0 ? LOG_TIME.format(Instant.ofEpochMilli(at)) : "-")
                        .append(" · ").append(item.optString("purpose"))
                        .append(" · ").append(item.optString("event"));
                String detail = item.optString("detail");
                if (!detail.isEmpty()) out.append(" · ").append(detail);
            }
            return out.length() == 0 ? "런타임 매칭 로그가 없습니다." : out.toString();
        } catch (Throwable ignored) { return "런타임 매칭 로그를 읽지 못했습니다."; }
    }

    private static String jsString(String raw) {
        try {
            Object value = new JSONTokener(raw == null ? "" : raw).nextValue();
            return value instanceof String ? (String) value : String.valueOf(value);
        } catch (Throwable ignored) { return "[]"; }
    }

    private void clearAll() {
        activePurpose = "";
        lastCandidate = "";
        handler.removeCallbacks(pollRunnable);
        store.clearAll();
        store.record("SYSTEM", "PROFILE_RESET", "user_requested");
        if (webView != null) {
            webView.evaluateJavascript("(()=>{try{localStorage.removeItem('" + WebUiCalibrationStore.STORAGE_KEY
                    + "');localStorage.removeItem('selfrun-drive:ui-runtime-log:v1');sessionStorage.removeItem('selfrun-drive:ui-runtime-dedupe:v1');sessionStorage.removeItem('selfrun-drive:ui-calibration:capture');return 'OK';}catch(e){return 'ERROR';}})()", ignored -> {});
        }
        updateControls("모든 보정값 초기화됨");
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == REQUEST_EXPORT_CALIBRATION) {
            exportCalibration(uri);
        } else if (requestCode == REQUEST_IMPORT_CALIBRATION) {
            importCalibration(uri);
        }
    }

    private void exportCalibration(Uri uri) {
        try {
            String payload = WebUiCalibrationBackupCodec.exportEnvelope(store.profile());
            byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
            if (bytes.length > WebUiCalibrationBackupCodec.MAX_BACKUP_BYTES) throw new IllegalStateException("backup_too_large");
            try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
                if (out == null) throw new IllegalStateException("output_unavailable");
                out.write(bytes);
                out.flush();
            }
            store.record("SYSTEM", "PROFILE_EXPORTED", "formatVersion=" + WebUiCalibrationBackupCodec.FORMAT_VERSION);
            updateControls("보정값 내보내기 완료");
            Toast.makeText(this, "웹 UI 보정값을 저장했습니다.", Toast.LENGTH_SHORT).show();
        } catch (Throwable error) {
            store.record("SYSTEM", "PROFILE_EXPORT_FAILED", error.getClass().getSimpleName());
            updateControls("보정값 내보내기 실패");
            Toast.makeText(this, "보정값 파일을 저장하지 못했습니다.", Toast.LENGTH_LONG).show();
        }
    }

    private void importCalibration(Uri uri) {
        try {
            String raw = readBackup(uri);
            if (!WebUiCalibrationBackupCodec.importInto(this, raw)) throw new IllegalArgumentException("invalid_backup");
            store.record("SYSTEM", "PROFILE_IMPORTED", "formatVersion=" + WebUiCalibrationBackupCodec.FORMAT_VERSION);
            seedProfile();
            updateControls("보정값 가져오기 완료");
            Toast.makeText(this, "웹 UI 보정값을 적용했습니다.", Toast.LENGTH_SHORT).show();
        } catch (Throwable error) {
            store.record("SYSTEM", "PROFILE_IMPORT_REJECTED", error.getClass().getSimpleName());
            updateControls("보정값 가져오기 실패");
            Toast.makeText(this, "올바른 웹 UI 보정값 파일이 아닙니다.", Toast.LENGTH_LONG).show();
        }
    }

    private String readBackup(Uri uri) throws Exception {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IllegalStateException("input_unavailable");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int total = 0;
            while (true) {
                int read = in.read(buffer);
                if (read < 0) break;
                total += read;
                if (total > WebUiCalibrationBackupCodec.MAX_BACKUP_BYTES) {
                    throw new IllegalArgumentException("backup_too_large");
                }
                out.write(buffer, 0, read);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static String capturePrompt(String purpose) {
        return switch (purpose) {
            case WebUiCalibrationStore.PURPOSE_MODE_CHAT -> "일반채팅 메뉴 항목을 터치하세요";
            case WebUiCalibrationStore.PURPOSE_MODE_WORK -> "Work 메뉴 항목을 터치하세요";
            case WebUiCalibrationStore.PURPOSE_GENERAL_BOOTSTRAP_WORK_MODEL -> "메인 새 대화 → Work → 모델 선택부를 터치하세요";
            case WebUiCalibrationStore.PURPOSE_GENERAL_BOOTSTRAP_WORK_REASONING -> "메인 새 대화 → Work → 추론 선택부를 터치하세요";
            case WebUiCalibrationStore.PURPOSE_GENERAL_CONTINUATION_WORK_MODEL -> "일반 Work 기존 방 → 모델 선택부를 터치하세요";
            case WebUiCalibrationStore.PURPOSE_GENERAL_CONTINUATION_WORK_REASONING -> "일반 Work 기존 방 → 추론 선택부를 터치하세요";
            case WebUiCalibrationStore.PURPOSE_PROJECT_BOOTSTRAP_WORK_MODEL -> "프로젝트 새 대화 → Work → 모델 선택부를 터치하세요";
            case WebUiCalibrationStore.PURPOSE_PROJECT_BOOTSTRAP_WORK_REASONING -> "프로젝트 새 대화 → Work → 추론 선택부를 터치하세요";
            case WebUiCalibrationStore.PURPOSE_PROJECT_CONTINUATION_WORK_MODEL -> "프로젝트 Work 기존 방 → 모델 선택부를 터치하세요";
            case WebUiCalibrationStore.PURPOSE_PROJECT_CONTINUATION_WORK_REASONING -> "프로젝트 Work 기존 방 → 추론 선택부를 터치하세요";
            case WebUiCalibrationStore.PURPOSE_PROJECT_NEW_CHAT -> "프로젝트 새 대화에서 임의 문구를 입력하고 제출하세요";
            case WebUiCalibrationStore.PURPOSE_GENERAL_NEW_CHAT -> "메인 새 대화에서 임의 문구를 입력하고 제출하세요";
            default -> "목표를 터치하세요";
        };
    }

    private static String label(String purpose) {
        return switch (purpose) {
            case WebUiCalibrationStore.PURPOSE_MODE_CHAT -> "일반채팅 메뉴";
            case WebUiCalibrationStore.PURPOSE_MODE_WORK -> "Work 메뉴";
            case WebUiCalibrationStore.PURPOSE_GENERAL_BOOTSTRAP_WORK_MODEL -> "일반 첫턴 모델";
            case WebUiCalibrationStore.PURPOSE_GENERAL_BOOTSTRAP_WORK_REASONING -> "일반 첫턴 추론";
            case WebUiCalibrationStore.PURPOSE_GENERAL_CONTINUATION_WORK_MODEL -> "일반 후속 모델";
            case WebUiCalibrationStore.PURPOSE_GENERAL_CONTINUATION_WORK_REASONING -> "일반 후속 추론";
            case WebUiCalibrationStore.PURPOSE_PROJECT_BOOTSTRAP_WORK_MODEL -> "프로젝트 첫턴 모델";
            case WebUiCalibrationStore.PURPOSE_PROJECT_BOOTSTRAP_WORK_REASONING -> "프로젝트 첫턴 추론";
            case WebUiCalibrationStore.PURPOSE_PROJECT_CONTINUATION_WORK_MODEL -> "프로젝트 후속 모델";
            case WebUiCalibrationStore.PURPOSE_PROJECT_CONTINUATION_WORK_REASONING -> "프로젝트 후속 추론";
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

    @Override public void onBackPressed() {
        if (!activePurpose.isEmpty()) {
            cancelPurpose();
            return;
        }
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }
        super.onBackPressed();
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
