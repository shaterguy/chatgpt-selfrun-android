package com.shaterguy.chatgptselfrun;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.auth.api.identity.AuthorizationResult;
import com.google.android.gms.common.api.ApiException;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** One-time drive.file + Google Picker binding for the canonical global Runs folder. */
public final class DriveSetupActivity extends Activity {
    private static final int REQUEST_PICK_FOLDER = 5101;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private SelfRunStore store;
    private TextView status;

    @Override protected void onCreate(Bundle savedInstanceState) { super.onCreate(savedInstanceState); store = new SelfRunStore(this); render(); }

    private void render() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(Ui.dp(this, 18), Ui.dp(this, 14), Ui.dp(this, 18), Ui.dp(this, 24));
        root.addView(Ui.title(this, "SelfRun Drive 설정"));
        root.addView(Ui.body(this, "Google Picker에서 /GPT/Self Run/Runs/ 폴더를 선택합니다. 앱은 drive.file 범위만 요청하며 선택된 폴더와 앱이 그 아래에 만드는 항목만 사용합니다. 기존에 저장된 Runs 폴더 ID가 접근 가능하면 폴더 이동만을 이유로 다시 연결할 필요가 없습니다."));
        status = Ui.body(this, bindingSummary()); root.addView(status);
        root.addView(Ui.row(this, Ui.button(this, "Drive 실행문서 저장 위치 연결", v -> startPicker()), Ui.button(this, "연결 해제", v -> unbind())));
        root.addView(Ui.button(this, "닫기", v -> finish())); scroll.addView(root); Ui.setContent(this, scroll);
    }

    private String bindingSummary() { if (store.driveRunsBaseFolderId().isEmpty()) return "현재 바인딩: 없음"; return "현재 바인딩: " + store.driveRunsBaseFolderName() + "\n폴더 ID: " + store.driveRunsBaseFolderId() + "\nURL: " + store.driveRunsBaseFolderUrl() + "\n바인딩 시각(ms): " + store.driveRunsBaseFolderBoundAt(); }

    private void startPicker() {
        status.setText("Google Drive 폴더 선택 준비 중…");
        DriveAuthorization.requestFolderPicker(this, new DriveAuthorization.Callback() {
            @Override public void onAuthorized(AuthorizationResult result) { bindSelected(result); }
            @Override public void onResolutionRequired(PendingIntent pendingIntent) { try { startIntentSenderForResult(pendingIntent.getIntentSender(), REQUEST_PICK_FOLDER, null, 0, 0, 0); } catch (Exception error) { failure("Google Picker를 열지 못했습니다."); } }
            @Override public void onFailure(Throwable error) { failure("Drive 권한 요청에 실패했습니다."); }
        });
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data); if (requestCode != REQUEST_PICK_FOLDER) return;
        if (resultCode != RESULT_OK || data == null) { failure("Drive 폴더 선택이 취소되었습니다."); return; }
        try { bindSelected(DriveAuthorization.fromIntent(this, data)); } catch (ApiException error) { failure("Drive 폴더 선택 결과를 확인하지 못했습니다."); }
    }

    private void bindSelected(AuthorizationResult result) {
        String token = DriveAuthorization.accessToken(result), folderId = DriveAuthorization.pickedFolderId(result);
        if (token.isEmpty() || folderId.isEmpty()) { failure("한 개의 Google Drive 폴더를 선택해야 합니다."); return; }
        io.execute(() -> { try {
            DriveApiClient api = new DriveApiClient(); String accountId = api.getAccountPermissionId(token); DriveApiClient.Metadata metadata = api.getMetadata(token, folderId);
            if (metadata.trashed || !DriveApiClient.MIME_FOLDER.equals(metadata.mimeType) || !metadata.isAppAuthorized || !metadata.canAddChildren || metadata.shared) throw new IllegalStateException("selected item is not a private app-authorized writable folder");
            String url = new Uri.Builder().scheme("https").authority("drive.google.com").appendPath("drive").appendPath("folders").appendPath(metadata.id).build().toString();
            store.bindBaseFolder(accountId, metadata.id, metadata.name, url, System.currentTimeMillis());
            runOnUiThread(() -> { status.setText(bindingSummary()); Toast.makeText(this, "Drive 실행문서 저장 위치를 연결했습니다.", Toast.LENGTH_LONG).show(); });
        } catch (Throwable error) { failure("선택한 폴더를 drive.file 권한으로 확인하지 못했습니다."); } });
    }

    private void unbind() { store.clearBaseFolderBinding(); status.setText(bindingSummary()); Toast.makeText(this, "Drive 저장 위치 연결을 해제했습니다. 실행 중인 Job의 저장 ID는 유지됩니다.", Toast.LENGTH_LONG).show(); }
    private void failure(String message) { runOnUiThread(() -> { status.setText(bindingSummary() + "\n마지막 결과: " + message); Toast.makeText(this, message, Toast.LENGTH_LONG).show(); }); }
    @Override protected void onDestroy() { io.shutdownNow(); super.onDestroy(); }
}
