package com.shaterguy.chatgptselfrun;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Read-only view of the current Drive canonical CHAT and WORK profile registries. */
public final class ProfileRegistryActivity extends Activity {
    private LinearLayout chatList;
    private LinearLayout workList;
    private TextView status;
    private ProfileRegistry.Mode selectedMode = ProfileRegistry.Mode.CHAT;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ProfileRegistry.initialize(this);
        createViews();
        refreshFromDrive();
    }

    @Override protected void onResume() {
        super.onResume();
        refreshFromDrive();
    }

    private void createViews() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        LinearLayout page = Ui.page(this);
        page.addView(Ui.toolbar(this, "모델 조합",
                Ui.iconButton(this, R.drawable.ic_refresh, "Drive 최신 조합 확인", v -> refreshFromDrive())));
        status = Ui.body(this, "");
        page.addView(status);

        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content);

        com.google.android.material.tabs.TabLayout tabs =
                new com.google.android.material.tabs.TabLayout(this);
        tabs.addTab(tabs.newTab().setText("일반 채팅"));
        tabs.addTab(tabs.newTab().setText("워크"));
        content.addView(tabs);

        chatList = new LinearLayout(this);
        chatList.setOrientation(LinearLayout.VERTICAL);
        workList = new LinearLayout(this);
        workList.setOrientation(LinearLayout.VERTICAL);
        content.addView(chatList);
        content.addView(workList);
        workList.setVisibility(View.GONE);

        tabs.addOnTabSelectedListener(new com.google.android.material.tabs.TabLayout.OnTabSelectedListener() {
            @Override public void onTabSelected(com.google.android.material.tabs.TabLayout.Tab tab) {
                selectedMode = tab.getPosition() == 0
                        ? ProfileRegistry.Mode.CHAT : ProfileRegistry.Mode.WORK;
                chatList.setVisibility(selectedMode == ProfileRegistry.Mode.CHAT
                        ? View.VISIBLE : View.GONE);
                workList.setVisibility(selectedMode == ProfileRegistry.Mode.WORK
                        ? View.VISIBLE : View.GONE);
            }
            @Override public void onTabUnselected(com.google.android.material.tabs.TabLayout.Tab tab) {}
            @Override public void onTabReselected(com.google.android.material.tabs.TabLayout.Tab tab) {}
        });

        page.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(page, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        Ui.setContent(this, root);
        renderRegistry();
    }

    private void refreshFromDrive() {
        if (status != null) {
            status.setText("Drive 최신 조합을 확인하고 있습니다.");
            status.setVisibility(View.VISIBLE);
        }
        ProfileRegistrySync.refresh(this, result -> {
            if (isFinishing()) return;
            renderRegistry();
            if (!result.error.isEmpty() && !result.anyUsable()) {
                status.setText("Drive 조합을 아직 불러오지 못했습니다. " + result.error);
                status.setVisibility(View.VISIBLE);
            }
        });
    }

    private void renderRegistry() {
        if (chatList == null || workList == null) return;
        chatList.removeAllViews();
        workList.removeAllViews();
        for (ProfileRegistry.Profile profile : ProfileRegistry.listChat()) addProfile(chatList, profile);
        for (ProfileRegistry.Profile profile : ProfileRegistry.listWork()) addProfile(workList, profile);
        if (ProfileRegistry.listChat().isEmpty()) {
            chatList.addView(Ui.muted(this, "Drive에 등록된 일반 채팅 조합이 없습니다."));
        }
        if (ProfileRegistry.listWork().isEmpty()) {
            workList.addView(Ui.muted(this, "Drive에 등록된 워크 조합이 없습니다."));
        }
        if (!ProfileRegistry.storageHealthy()) {
            status.setText("저장된 최근 정상 조합을 읽을 수 없습니다.");
            status.setVisibility(View.VISIBLE);
        } else if (!ProfileRegistry.listChat().isEmpty() || !ProfileRegistry.listWork().isEmpty()) {
            status.setText("");
            status.setVisibility(View.GONE);
        }
    }

    private void addProfile(LinearLayout list, ProfileRegistry.Profile profile) {
        list.addView(Ui.setting(this, R.drawable.ic_settings, profile.displayLabel(),
                "Drive canonical registry", v -> showProfile(profile)));
        list.addView(Ui.divider(this));
    }

    private void showProfile(ProfileRegistry.Profile profile) {
        String signal = profile.mode == ProfileRegistry.Mode.WORK
                ? "MODEL=" + profile.signalModel + " REASONING=" + profile.signalReasoning
                : "REASONING=" + profile.signalReasoning;
        new AlertDialog.Builder(this)
                .setTitle(profile.displayLabel())
                .setMessage(signal + "\n\n실제 요청 조합: " + profile.actualCombination()
                        + "\n\n이 목록은 Drive canonical registry에서 자동 갱신됩니다.")
                .setPositiveButton("닫기", null)
                .show();
    }
}
