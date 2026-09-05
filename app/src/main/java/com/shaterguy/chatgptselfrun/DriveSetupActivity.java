package com.shaterguy.chatgptselfrun;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.auth.api.identity.AuthorizationResult;
import com.google.android.gms.common.api.ApiException;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** One-time Drive binding: app-owned writes plus read-only discovery of ChatGPT-created signal docs. */
public final class DriveSetupActivity extends Activity {
    private static final int REQUEST_RUNTIME_AUTH = 5100;
    private static final int REQUEST_PICK_FOLDER = 5101;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private SelfRunStore store;
    private TextView statusHeadline;
    private TextView statusDetails;
    private TextView bindingDetails;
    private View unbindButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new SelfRunStore(this);
        render();
    }

    private void render() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout page = Ui.page(this);
        scroll.addView(page);
        page.addView(Ui.toolbar(this, "Drive 저장 위치", null));
        statusHeadline = Ui.headline(this, "");
        statusDetails = Ui.body(this, "");
        bindingDetails = Ui.muted(this, "");
        bindingDetails.setTextIsSelectable(true);
        bindingDetails.setVisibility(View.GONE);
        page.addView(Ui.card(this, statusHeadline, statusDetails));
        page.addView(Ui.button(this, store.driveRunsBaseFolderId().isEmpty() ? "폴더 선택" : "폴더 변경", v -> startPicker()));
        page.addView(Ui.textButton(this, "상세 정보", v -> bindingDetails.setVisibility(
                bindingDetails.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE)));
        page.addView(bindingDetails);
        unbindButton = Ui.textButton(this, "연결 해제", v -> unbind());
        LinearLayout.LayoutParams unbindParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        unbindParams.topMargin = Ui.dp(this, 6);
        page.addView(unbindButton, unbindParams);

        refreshBindingSummary();
        Ui.setContent(this, scroll);
    }

    private void refreshBindingSummary() {
        boolean bound = !store.driveRunsBaseFolderId().isEmpty();
        if (bound) {
            statusHeadline.setText(store.driveRunsBaseFolderName().isEmpty()
                    ? "Drive 실행문서 위치가 연결되어 있습니다"
                    : store.driveRunsBaseFolderName());
            statusDetails.setText("연결됨");
            bindingDetails.setText("폴더 ID  " + store.driveRunsBaseFolderId()
                    + "\nURL  " + store.driveRunsBaseFolderUrl()
                    + "\n바인딩 시각(ms)  " + store.driveRunsBaseFolderBoundAt());
        } else {
            statusHeadline.setText("저장 폴더를 선택하세요");
            statusDetails.setText("/GPT/Self Run/Runs/");
        }
        if (unbindButton != null) unbindButton.setVisibility(bound ? View.VISIBLE : View.GONE);
    }

    private void startPicker() {
        statusHeadline.setText("Drive 권한 확인 중…");
        statusDetails.setText("신호 문서 읽기 권한을 확인한 뒤 Google Picker를 엽니다.");
        DriveAuthorization.requestSilently(this, new DriveAuthorization.Callback() {
            @Override public void onAuthorized(AuthorizationResult result) {
                if (DriveAuthorization.accessToken(result).isEmpty()) {
                    failure("Drive 읽기 권한 토큰을 얻지 못했습니다.");
                    return;
                }
                launchFolderPicker();
            }
            @Override public void onResolutionRequired(PendingIntent pendingIntent) {
                try {
                    startIntentSenderForResult(pendingIntent.getIntentSender(), REQUEST_RUNTIME_AUTH, null, 0, 0, 0);
                } catch (Exception error) {
                    failure("Drive 읽기 권한 승인 화면을 열지 못했습니다.");
                }
            }
            @Override public void onFailure(Throwable error) { failure("Drive 읽기 권한 요청에 실패했습니다."); }
        });
    }

    private void launchFolderPicker() {
        statusHeadline.setText("Drive 폴더 선택 준비 중…");
        statusDetails.setText("Google Picker를 여는 중입니다.");
        DriveAuthorization.requestFolderPicker(this, new DriveAuthorization.Callback() {
            @Override public void onAuthorized(AuthorizationResult result) { bindSelected(result); }
            @Override public void onResolutionRequired(PendingIntent pendingIntent) {
                try {
                    startIntentSenderForResult(pendingIntent.getIntentSender(), REQUEST_PICK_FOLDER, null, 0, 0, 0);
                } catch (Exception error) {
                    failure("Google Picker를 열지 못했습니다.");
                }
            }
            @Override public void onFailure(Throwable error) { failure("Drive 폴더 선택 권한 요청에 실패했습니다."); }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_RUNTIME_AUTH) {
            if (resultCode != RESULT_OK || data == null) {
                failure("Drive 읽기 권한 승인이 취소되었습니다.");
                return;
            }
            try {
                AuthorizationResult result = DriveAuthorization.fromIntent(this, data);
                if (DriveAuthorization.accessToken(result).isEmpty()) {
                    failure("Drive 읽기 권한 토큰을 얻지 못했습니다.");
                    return;
                }
                launchFolderPicker();
            } catch (ApiException error) {
                failure("Drive 읽기 권한 승인 결과를 확인하지 못했습니다.");
            }
            return;
        }
        if (requestCode != REQUEST_PICK_FOLDER) return;
        if (resultCode != RESULT_OK || data == null) {
            failure("Drive 폴더 선택이 취소되었습니다.");
            return;
        }
        try {
            bindSelected(DriveAuthorization.fromIntent(this, data));
        } catch (ApiException error) {
            failure("Drive 폴더 선택 결과를 확인하지 못했습니다.");
        }
    }

    private void bindSelected(AuthorizationResult result) {
        String token = DriveAuthorization.accessToken(result);
        String folderId = DriveAuthorization.pickedFolderId(result);
        if (token.isEmpty() || folderId.isEmpty()) {
            failure("한 개의 Google Drive 폴더를 선택해야 합니다.");
            return;
        }
        io.execute(() -> {
            try {
                DriveApiClient api = new DriveApiClient();
                String accountId = api.getAccountPermissionId(token);
                DriveApiClient.Metadata metadata = api.getMetadata(token, folderId);
                if (metadata.trashed || !DriveApiClient.MIME_FOLDER.equals(metadata.mimeType)
                        || !metadata.isAppAuthorized || !metadata.canAddChildren || metadata.shared) {
                    throw new IllegalStateException("selected item is not a private app-authorized writable folder");
                }
                String url = new Uri.Builder().scheme("https").authority("drive.google.com")
                        .appendPath("drive").appendPath("folders").appendPath(metadata.id).build().toString();
                store.bindBaseFolder(accountId, metadata.id, metadata.name, url, System.currentTimeMillis());
                runOnUiThread(() -> {
                    refreshBindingSummary();
                    Toast.makeText(this, "Drive 실행문서 저장 위치를 연결했습니다.", Toast.LENGTH_LONG).show();
                });
            } catch (Throwable error) {
                failure("선택한 폴더의 쓰기·신호 읽기 권한을 확인하지 못했습니다.");
            }
        });
    }

    private void unbind() {
        store.clearBaseFolderBinding();
        refreshBindingSummary();
        Toast.makeText(this, "Drive 저장 위치 연결을 해제했습니다. 실행 중인 Job의 저장 ID는 유지됩니다.", Toast.LENGTH_LONG).show();
    }

    private void failure(String message) {
        runOnUiThread(() -> {
            refreshBindingSummary();
            statusDetails.setText(statusDetails.getText() + "\n마지막 결과  " + message);
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        });
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }
}
