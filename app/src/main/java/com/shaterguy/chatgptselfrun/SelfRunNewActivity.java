package com.shaterguy.chatgptselfrun;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.InputType;
import android.text.Layout;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class SelfRunNewActivity extends Activity {
    private static final String[] MODE_LABELS = {"일반 Chat · 추론 정도 선택", "Work · 모델/추론 동적 전환"};
    private static final String[] MODE_VALUES = {SelfRunStore.MODE_CHAT, SelfRunStore.MODE_WORK};
    private static final String BOOTSTRAP_SAME_AS_TASK = "same";
    private static final int REQUEST_ATTACHMENTS = 3017;
    private static final String STATE_REQUIREMENT = "requirement";
    private static final String STATE_MODE = "mode";
    private static final String STATE_PROJECT = "project";
    private static final String STATE_ATTACHMENTS = "attachments";
    private static final String STATE_CHAT_REASONING = "chatReasoningToken";
    private static final String STATE_CHAT_BOOTSTRAP_REASONING = "chatBootstrapReasoningToken";
    private static final String STATE_CHAT_ADVANCED_EXPANDED = "chatAdvancedExpanded";

    private SelfRunStore store;
    private SelfRunHistoryStore history;
    private SelfRunRunLog runLog;
    private Ui.SelectionField project;
    private ProjectCatalog catalog;
    private List<ProjectUrlPolicy.ProjectRef> projectEntries;
    private EditText requirement;
    private Ui.SelectionField mode;
    private Ui.SelectionField chatReasoning;
    private TextView chatReasoningHelp;
    private Button chatAdvancedToggle;
    private LinearLayout chatAdvancedContainer;
    private Ui.SelectionField chatBootstrapReasoning;
    private TextView chatBootstrapReasoningHelp;
    private LinearLayout attachmentListView;
    private TextView attachmentSummary;
    private final ArrayList<SelfRunStore.Attachment> selectedAttachments = new ArrayList<>();
    private final ArrayList<String> chatReasoningValues = new ArrayList<>();
    private final ArrayList<String> chatBootstrapReasoningValues = new ArrayList<>();
    private boolean chatAdvancedExpanded;
    private boolean attachmentsHandedOff;
    private boolean firstResume = true;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        ProfileRegistry.initialize(this);
        store = new SelfRunStore(this);
        history = new SelfRunHistoryStore(this);
        runLog = new SelfRunRunLog(this);
        catalog = new ProjectCatalog(this);
        createViews();
        restoreDraftState(savedInstanceState);
    }

    private void createViews() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout page = Ui.page(this);
        page.setFocusableInTouchMode(true);
        scroll.addView(page);

        page.addView(Ui.topBar(this, "새 SelfRun", "Launch Workspace",
                Ui.textButton(this, "취소", v -> finish())));

        LinearLayout destinationRuntime = new LinearLayout(this);
        destinationRuntime.setOrientation(LinearLayout.VERTICAL);
        destinationRuntime.addView(Ui.section(this, "DESTINATION"));
        project = Ui.selection(this, "프로젝트");
        destinationRuntime.addView(project);
        destinationRuntime.addView(Ui.muted(this,
                "등록한 프로젝트 또는 일반채팅을 선택합니다. 프로젝트 등록은 실제 ChatGPT 화면을 직접 열어 수행합니다."));
        destinationRuntime.addView(Ui.actionStrip(this,
                Ui.outlinedButton(this, "프로젝트 등록 · 업데이트",
                        v -> startActivity(new Intent(this, LoginActivity.class))),
                Ui.textButton(this, "등록 목록 지우기", v -> {
                    catalog.clear();
                    store.setDefaultProjectUrl("");
                    reloadProjects();
                    Toast.makeText(this, "등록 프로젝트 목록을 지웠습니다.", Toast.LENGTH_SHORT).show();
                })));
        reloadProjects();

        destinationRuntime.addView(Ui.section(this, "RUNTIME"));
        mode = Ui.selection(this, "실행 모드");
        mode.setItems(MODE_LABELS);
        destinationRuntime.addView(mode);
        chatReasoning = Ui.selection(this, "작업 추론 정도 · 두 번째 턴부터");
        refreshChatReasoningOptions(ChatReasoningPreferenceStore.EXTRA_HIGH);
        LinearLayout.LayoutParams reasoningParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        reasoningParams.topMargin = Ui.dp(this, 10);
        destinationRuntime.addView(chatReasoning, reasoningParams);
        chatReasoningHelp = Ui.muted(this, "");
        destinationRuntime.addView(chatReasoningHelp);

        chatAdvancedToggle = Ui.textButton(this, "고급 옵션 펼치기", v -> {
            chatAdvancedExpanded = !chatAdvancedExpanded;
            updateChatReasoningAvailability();
        });
        destinationRuntime.addView(chatAdvancedToggle);
        chatAdvancedContainer = new LinearLayout(this);
        chatAdvancedContainer.setOrientation(LinearLayout.VERTICAL);
        chatBootstrapReasoning = Ui.selection(this, "부트스트랩 전용 추론 정도");
        refreshChatBootstrapReasoningOptions(BOOTSTRAP_SAME_AS_TASK);
        chatAdvancedContainer.addView(chatBootstrapReasoning);
        chatBootstrapReasoningHelp = Ui.muted(this,
                "첫 부트스트랩 PLAN 메시지에만 적용합니다. 두 번째 턴부터는 작업 추론 정도를 다시 적용합니다.");
        chatAdvancedContainer.addView(chatBootstrapReasoningHelp);
        destinationRuntime.addView(chatAdvancedContainer);

        mode.setOnSelectionChangedListener(position -> updateChatReasoningAvailability());
        updateChatReasoningAvailability();

        LinearLayout missionReferences = new LinearLayout(this);
        missionReferences.setOrientation(LinearLayout.VERTICAL);
        missionReferences.addView(Ui.section(this, "MISSION"));
        missionReferences.addView(Ui.headline(this, "SelfRun이 끝까지 수행할 작업"));
        requirement = new EditText(this);
        requirement.setHint("작업 요구사항을 입력하세요");
        requirement.setSingleLine(false);
        requirement.setMinLines(Ui.isExpanded(this) ? 13 : 9);
        requirement.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        requirement.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        requirement.setVerticalScrollBarEnabled(false);
        requirement.setHorizontalScrollBarEnabled(false);
        requirement.setHorizontallyScrolling(false);
        installCommandVisibilityTracking(requirement);
        missionReferences.addView(requirement);

        missionReferences.addView(Ui.section(this, "REFERENCES"));
        missionReferences.addView(Ui.muted(this,
                "첨부파일은 SelfRun 시작 후 해당 Run의 Drive Job 폴더로 전달됩니다."));
        missionReferences.addView(Ui.outlinedButton(this, "파일 첨부", v -> openAttachmentPicker()),
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        attachmentSummary = Ui.muted(this, "선택된 파일이 없습니다.");
        missionReferences.addView(attachmentSummary);
        attachmentListView = new LinearLayout(this);
        attachmentListView.setOrientation(LinearLayout.VERTICAL);
        missionReferences.addView(attachmentListView);

        LinearLayout workspace = new LinearLayout(this);
        if (Ui.isExpanded(this)) {
            workspace.setOrientation(LinearLayout.HORIZONTAL);
            workspace.addView(destinationRuntime,
                    new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.82f));
            LinearLayout.LayoutParams missionParams = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.18f);
            missionParams.setMarginStart(Ui.dp(this, 28));
            workspace.addView(missionReferences, missionParams);
        } else {
            workspace.setOrientation(LinearLayout.VERTICAL);
            workspace.addView(destinationRuntime,
                    new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            LinearLayout.LayoutParams missionParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            missionParams.topMargin = Ui.dp(this, 8);
            workspace.addView(missionReferences, missionParams);
        }
        page.addView(workspace, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams launchParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        launchParams.topMargin = Ui.dp(this, 22);
        page.addView(Ui.button(this, "SelfRun Drive 시작", v -> startSelfRun()), launchParams);
        page.addView(Ui.muted(this, "새 작업은 항상 빈 요구사항에서 시작하며 이전 Run의 입력·신호·오류를 재사용하지 않습니다."));

        Ui.setContent(this, scroll);
        project.clearFocus();
        requirement.clearFocus();
        page.requestFocus();
    }

    private void refreshChatReasoningOptions(String preferred) {
        if (chatReasoning == null) return;
        String wanted = preferred == null || preferred.isEmpty() ? ChatReasoningPreferenceStore.KEEP : preferred;
        ArrayList<String> labels = new ArrayList<>();
        chatReasoningValues.clear();
        labels.add("현재 Chat 설정 유지");
        chatReasoningValues.add(ChatReasoningPreferenceStore.KEEP);
        int selected = 0;
        for (ProfileRegistry.Profile profile : ProfileRegistry.listChat()) {
            labels.add(profile.displayLabel() + " · " + profile.actualCombination());
            chatReasoningValues.add(profile.signalReasoning);
            if (profile.signalReasoning.equals(wanted)) selected = chatReasoningValues.size() - 1;
        }
        if (!ChatReasoningPreferenceStore.KEEP.equals(wanted)
                && ProfileRegistry.resolveChat(wanted) == null) {
            labels.add("지원하지 않는 이전 선택 · " + wanted);
            chatReasoningValues.add(wanted);
            selected = chatReasoningValues.size() - 1;
        }
        chatReasoning.setItems(labels.toArray(new String[0]));
        chatReasoning.setSelection(selected);
    }

    private void refreshChatBootstrapReasoningOptions(String preferred) {
        if (chatBootstrapReasoning == null) return;
        String wanted = preferred == null || preferred.isEmpty() ? BOOTSTRAP_SAME_AS_TASK : preferred;
        ArrayList<String> labels = new ArrayList<>();
        chatBootstrapReasoningValues.clear();
        labels.add("작업 추론 정도와 동일");
        chatBootstrapReasoningValues.add(BOOTSTRAP_SAME_AS_TASK);
        int selected = 0;
        for (ProfileRegistry.Profile profile : ProfileRegistry.listChat()) {
            labels.add(profile.displayLabel() + " · " + profile.actualCombination());
            chatBootstrapReasoningValues.add(profile.signalReasoning);
            if (profile.signalReasoning.equals(wanted)) selected = chatBootstrapReasoningValues.size() - 1;
        }
        if (!BOOTSTRAP_SAME_AS_TASK.equals(wanted) && ProfileRegistry.resolveChat(wanted) == null) {
            labels.add("지원하지 않는 이전 선택 · " + wanted);
            chatBootstrapReasoningValues.add(wanted);
            selected = chatBootstrapReasoningValues.size() - 1;
        }
        chatBootstrapReasoning.setItems(labels.toArray(new String[0]));
        chatBootstrapReasoning.setSelection(selected);
    }

    private void updateChatReasoningAvailability() {
        if (mode == null || chatReasoning == null || chatReasoningHelp == null
                || chatAdvancedToggle == null || chatAdvancedContainer == null
                || chatBootstrapReasoning == null) return;
        boolean chat = mode.getSelectedItemPosition() <= 0;
        chatReasoning.setEnabled(chat);
        chatReasoning.setVisibility(chat ? View.VISIBLE : View.GONE);
        chatAdvancedToggle.setEnabled(chat);
        chatAdvancedToggle.setVisibility(chat ? View.VISIBLE : View.GONE);
        chatAdvancedToggle.setText(chatAdvancedExpanded ? "고급 옵션 접기" : "고급 옵션 펼치기");
        chatAdvancedContainer.setVisibility(chat && chatAdvancedExpanded ? View.VISIBLE : View.GONE);
        chatBootstrapReasoning.setEnabled(chat && chatAdvancedExpanded);
        chatReasoningHelp.setText(chat
                ? "두 번째 턴부터 SelfRun 종료까지 사용할 일반 Chat 추론 정도입니다. 목록은 Profile Registry를 실시간 원본으로 사용합니다."
                : "Work 모드는 새 작업에서 수동 선택하지 않고 TURN_COMPLETED MODEL/REASONING 신호를 Registry에서 해석합니다.");
    }

    private String selectedChatReasoning() {
        if (chatReasoning == null || chatReasoningValues.isEmpty()) return ChatReasoningPreferenceStore.KEEP;
        int position = Math.max(0, Math.min(chatReasoningValues.size() - 1,
                chatReasoning.getSelectedItemPosition()));
        return chatReasoningValues.get(position);
    }

    private String selectedChatBootstrapReasoning() {
        if (chatBootstrapReasoning == null || chatBootstrapReasoningValues.isEmpty()) {
            return BOOTSTRAP_SAME_AS_TASK;
        }
        int position = Math.max(0, Math.min(chatBootstrapReasoningValues.size() - 1,
                chatBootstrapReasoning.getSelectedItemPosition()));
        return chatBootstrapReasoningValues.get(position);
    }

    private int chatReasoningPosition(String value) {
        for (int i = 0; i < chatReasoningValues.size(); i++) {
            if (chatReasoningValues.get(i).equals(value)) return i;
        }
        return 0;
    }

    private void openAttachmentPicker() {
        Intent picker = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        picker.addCategory(Intent.CATEGORY_OPENABLE);
        picker.setType("*/*");
        picker.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        picker.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(picker, REQUEST_ATTACHMENTS);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_ATTACHMENTS || resultCode != RESULT_OK || data == null) return;
        ArrayList<Uri> uris = new ArrayList<>();
        if (data.getData() != null) uris.add(data.getData());
        ClipData clips = data.getClipData();
        if (clips != null) for (int i = 0; i < clips.getItemCount(); i++) uris.add(clips.getItemAt(i).getUri());
        int accepted = 0, rejected = 0;
        Set<String> existing = new HashSet<>();
        for (SelfRunStore.Attachment item : selectedAttachments) existing.add(item.uri);
        for (Uri uri : uris) {
            if (uri == null || !"content".equals(uri.getScheme()) || !existing.add(uri.toString())) continue;
            if (selectedAttachments.size() >= SelfRunStore.MAX_ATTACHMENTS_PER_RUN) { rejected++; continue; }
            try {
                int flags = data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION;
                if (flags == 0) throw new SecurityException("read grant missing");
                selectedAttachments.add(readAttachmentDraft(uri, nextAttachmentIndex()));
                accepted++;
            } catch (Throwable error) { rejected++; }
        }
        renderAttachments();
        if (rejected > 0) {
            Toast.makeText(this, "일부 파일은 읽기 권한·형식·크기 제한 때문에 제외했습니다. 첨부는 최대 "
                    + SelfRunStore.MAX_ATTACHMENTS_PER_RUN + "개, 파일당 최대 100 MB입니다.", Toast.LENGTH_LONG).show();
        } else if (accepted > 0) {
            Toast.makeText(this, "첨부파일 " + accepted + "개를 추가했습니다.", Toast.LENGTH_SHORT).show();
        }
    }

    private SelfRunStore.Attachment readAttachmentDraft(Uri uri, int index) {
        String name = "";
        long size = -1L;
        try (Cursor cursor = getContentResolver().query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (nameColumn >= 0 && !cursor.isNull(nameColumn)) name = cursor.getString(nameColumn);
                if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) size = Math.max(-1L, cursor.getLong(sizeColumn));
            }
        }
        name = sanitizeDisplayName(name, index);
        if (size > SelfRunStore.MAX_ATTACHMENT_BYTES) throw new IllegalArgumentException("attachment too large");
        String mimeType = DriveApiClient.normalizeAttachmentMimeType(getContentResolver().getType(uri));
        return SelfRunStore.Attachment.draft(index, uri.toString(), name, mimeType, size);
    }

    static String sanitizeDisplayName(String value, int index) {
        String source = value == null ? "" : value;
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < source.length() && out.length() < 180; i++) {
            char c = source.charAt(i);
            if (Character.isISOControl(c) || c == '/' || c == '\\') out.append('_'); else out.append(c);
        }
        String result = out.toString().trim();
        if (result.isEmpty()) result = "attachment-" + Math.max(0, index);
        return result.length() <= 180 ? result : result.substring(0, 180);
    }

    private int nextAttachmentIndex() {
        int max = -1;
        for (SelfRunStore.Attachment item : selectedAttachments) max = Math.max(max, item.index);
        return max + 1;
    }

    private void renderAttachments() {
        if (attachmentListView == null || attachmentSummary == null) return;
        attachmentListView.removeAllViews();
        if (selectedAttachments.isEmpty()) { attachmentSummary.setText("선택된 파일이 없습니다."); return; }
        attachmentSummary.setText("첨부파일 " + selectedAttachments.size() + "개");
        for (SelfRunStore.Attachment item : new ArrayList<>(selectedAttachments)) {
            LinearLayout row = Ui.settingsRow(this, item.name,
                    item.size < 0 ? "크기 알 수 없음" : formatBytes(item.size),
                    Ui.textButton(this, "제거", v -> removeAttachment(item.index)));
            attachmentListView.addView(row);
            attachmentListView.addView(Ui.divider(this));
        }
    }

    private boolean persistSelectedAttachmentGrants(Set<String> persistedBefore) {
        ArrayList<Uri> acquired = new ArrayList<>();
        try {
            for (SelfRunStore.Attachment item : selectedAttachments) {
                Uri uri = Uri.parse(item.uri);
                if (!"content".equals(uri.getScheme())) throw new SecurityException("content URI required");
                getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                if (!persistedBefore.contains(item.uri)) acquired.add(uri);
            }
            return true;
        } catch (Throwable error) {
            for (Uri uri : acquired) releaseReadGrant(uri);
            return false;
        }
    }

    private Set<String> persistedReadGrantUris() {
        HashSet<String> result = new HashSet<>();
        try {
            for (android.content.UriPermission permission : getContentResolver().getPersistedUriPermissions()) {
                if (permission != null && permission.isReadPermission() && permission.getUri() != null) result.add(permission.getUri().toString());
            }
            return result;
        } catch (Throwable error) {
            throw new IllegalStateException("persisted URI permission snapshot failed", error);
        }
    }

    private List<SelfRunStore.Attachment> attachmentsNeedingPersistableGrant(Set<String> persistedBefore) {
        ArrayList<SelfRunStore.Attachment> result = new ArrayList<>();
        for (SelfRunStore.Attachment item : selectedAttachments) if (!persistedBefore.contains(item.uri)) result.add(item);
        return result;
    }

    private void removeAttachment(int index) {
        for (int i = 0; i < selectedAttachments.size(); i++) {
            if (selectedAttachments.get(i).index == index) { selectedAttachments.remove(i); break; }
        }
        renderAttachments();
    }

    private void releaseReadGrant(Uri uri) {
        if (uri == null || !"content".equals(uri.getScheme())) return;
        try { getContentResolver().releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); }
        catch (Throwable ignored) { }
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024L) return bytes + " B";
        double value = bytes / 1024.0;
        String unit = "KB";
        if (value >= 1024.0) { value /= 1024.0; unit = "MB"; }
        if (value >= 1024.0) { value /= 1024.0; unit = "GB"; }
        return String.format(Locale.US, "%.1f %s", value, unit);
    }

    private void installCommandVisibilityTracking(EditText editor) {
        editor.setOnFocusChangeListener((view, hasFocus) -> {
            if (hasFocus) { editor.post(this::keepCommandCursorVisible); editor.postDelayed(this::keepCommandCursorVisible, 250L); }
        });
        editor.setOnClickListener(view -> editor.post(this::keepCommandCursorVisible));
        editor.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { }
            @Override public void afterTextChanged(Editable s) { editor.post(SelfRunNewActivity.this::keepCommandCursorVisible); }
        });
    }

    private void keepCommandCursorVisible() {
        EditText editor = requirement;
        if (editor == null || !editor.hasFocus()) return;
        Layout layout = editor.getLayout();
        if (layout == null || editor.getWidth() <= 0) return;
        ScrollView outer = findOuterScrollView(editor);
        if (outer == null || outer.getHeight() <= 0) return;
        int selection = Math.max(0, Math.min(editor.getSelectionStart(), editor.length()));
        int line = layout.getLineForOffset(selection), margin = Ui.dp(this, 12);
        int editorTop = descendantTopWithinScrollContent(editor, outer);
        if (editorTop < 0) return;
        int caretTop = editorTop + editor.getTotalPaddingTop() + layout.getLineTop(line) - margin;
        int caretBottom = editorTop + editor.getTotalPaddingTop() + layout.getLineBottom(line) + margin;
        int currentScroll = outer.getScrollY();
        int visibleTop = currentScroll + outer.getPaddingTop();
        int visibleBottom = currentScroll + outer.getHeight() - outer.getPaddingBottom();
        int targetScroll = currentScroll;
        if (caretBottom > visibleBottom) targetScroll += caretBottom - visibleBottom;
        else if (caretTop < visibleTop) targetScroll -= visibleTop - caretTop;
        if (targetScroll != currentScroll) outer.scrollTo(outer.getScrollX(), Math.max(0, targetScroll));
    }

    private static int descendantTopWithinScrollContent(View descendant, ScrollView outer) {
        int top = 0;
        View current = descendant;
        while (current != outer) {
            top += current.getTop();
            ViewParent parent = current.getParent();
            if (!(parent instanceof View)) return -1;
            current = (View) parent;
        }
        return top;
    }

    private static ScrollView findOuterScrollView(View child) {
        ViewParent parent = child.getParent();
        while (parent instanceof View) {
            if (parent instanceof ScrollView) return (ScrollView) parent;
            parent = ((View) parent).getParent();
        }
        return null;
    }

    private void startSelfRun() {
        if (store.active() && !store.userStopped()
                && !SelfRunStore.PHASE_DONE.equals(store.phase()) && !SelfRunStore.PHASE_IDLE.equals(store.phase())) {
            Toast.makeText(this, "현재 SelfRun Drive 작업(일시정지 포함)을 먼저 중지하세요.", Toast.LENGTH_LONG).show();
            return;
        }
        String project = selectedProjectUrl();
        String request = requirement.getText().toString();
        String requirementError = SelfRunOriginalRequirement.validationError(request);
        if (!requirementError.isEmpty()) { Toast.makeText(this, requirementError, Toast.LENGTH_LONG).show(); return; }
        if (!DriveApiClient.validFileId(store.driveRunsBaseFolderId())
                || !DriveApiClient.validOpaqueAccountId(store.driveAccountId())) {
            Toast.makeText(this, "먼저 ‘Drive 실행문서 저장 위치’에서 Runs 폴더를 연결하세요.", Toast.LENGTH_LONG).show(); return;
        }
        String selectedMode = MODE_VALUES[mode.getSelectedItemPosition()];
        String continuationReasoning = SelfRunStore.MODE_CHAT.equals(selectedMode)
                ? selectedChatReasoning() : ChatReasoningPreferenceStore.KEEP;
        String bootstrapChoice = SelfRunStore.MODE_CHAT.equals(selectedMode)
                ? selectedChatBootstrapReasoning() : BOOTSTRAP_SAME_AS_TASK;
        String bootstrapReasoning = BOOTSTRAP_SAME_AS_TASK.equals(bootstrapChoice)
                ? continuationReasoning : bootstrapChoice;
        if (SelfRunStore.MODE_CHAT.equals(selectedMode)
                && !ChatReasoningPreferenceStore.KEEP.equals(continuationReasoning)
                && ProfileRegistry.resolveChat(continuationReasoning) == null) {
            Toast.makeText(this, "선택한 작업 Chat profile이 삭제되었거나 지원되지 않습니다. Profile Registry에서 다시 선택하세요.", Toast.LENGTH_LONG).show();
            return;
        }
        if (SelfRunStore.MODE_CHAT.equals(selectedMode)
                && !ChatReasoningPreferenceStore.KEEP.equals(bootstrapReasoning)
                && ProfileRegistry.resolveChat(bootstrapReasoning) == null) {
            Toast.makeText(this, "선택한 부트스트랩 Chat profile이 삭제되었거나 지원되지 않습니다. Profile Registry에서 다시 선택하세요.", Toast.LENGTH_LONG).show();
            return;
        }
        if (SelfRunStore.MODE_CHAT.equals(selectedMode)
                && ChatReasoningPreferenceStore.KEEP.equals(continuationReasoning)
                && !ChatReasoningPreferenceStore.KEEP.equals(bootstrapReasoning)) {
            Toast.makeText(this, "부트스트랩 후 복원할 작업 profile을 알 수 없습니다. 작업 추론 정도를 등록된 조합으로 선택하세요.", Toast.LENGTH_LONG).show();
            return;
        }
        String runId = SelfRunRunId.create();
        if (!ChatReasoningPreferenceStore.save(this, runId, bootstrapReasoning, continuationReasoning)) {
            Toast.makeText(this, "Chat 추론 profile 설정을 저장하지 못했습니다.", Toast.LENGTH_LONG).show(); return;
        }
        Set<String> persistedBefore;
        try {
            persistedBefore = persistedReadGrantUris();
            store.prepareAttachmentGrantHandoff(attachmentsNeedingPersistableGrant(persistedBefore));
        } catch (RuntimeException invalid) {
            Toast.makeText(this, "첨부파일 권한 상태를 확인할 수 없거나 첨부 제한을 초과했습니다.", Toast.LENGTH_LONG).show(); return;
        }
        if (!persistSelectedAttachmentGrants(persistedBefore)) {
            store.cancelAttachmentGrantHandoff();
            Toast.makeText(this, "첨부파일의 지속 읽기 권한을 확보할 수 없습니다. 해당 파일을 다시 선택하세요.", Toast.LENGTH_LONG).show(); return;
        }
        store.setDefaultProjectUrl(project);
        if (!store.runId().isEmpty()) history.sync(store);
        stopService(new Intent(this, SelfRunService.class));
        if (!SelfRunSignalTransport.mark(this, runId)) {
            store.cancelAttachmentGrantHandoff();
            Toast.makeText(this, "SelfRun signal transport 상태를 저장하지 못했습니다.", Toast.LENGTH_LONG).show(); return;
        }
        try {
            store.start(runId, selectedMode, project, request, new ArrayList<>(selectedAttachments));
            attachmentsHandedOff = true;
        } catch (RuntimeException error) {
            store.cancelAttachmentGrantHandoff();
            throw error;
        }
        runLog.record(store, "UI_START", "mode=" + selectedMode
                + ";chatBootstrapReasoning=" + bootstrapReasoning
                + ";chatContinuationReasoning=" + continuationReasoning
                + ";attachments=" + selectedAttachments.size());
        startRunner();
        Toast.makeText(this, "SelfRun Drive를 시작했습니다: " + runId, Toast.LENGTH_LONG).show();
        finish();
    }

    private void startRunner() {
        Intent intent = new Intent(this, SelfRunService.class);
        intent.setAction(SelfRunService.ACTION_RUN);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent); else startService(intent);
    }

    @Override protected void onResume() {
        super.onResume();
        if (chatReasoning != null) refreshChatReasoningOptions(selectedChatReasoning());
        if (chatBootstrapReasoning != null) {
            refreshChatBootstrapReasoningOptions(selectedChatBootstrapReasoning());
        }
        if (firstResume) { firstResume = false; return; }
        if (project != null) reloadProjects(selectedProjectUrl());
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(STATE_REQUIREMENT, requirement == null ? "" : requirement.getText().toString());
        outState.putInt(STATE_MODE, mode == null ? 0 : mode.getSelectedItemPosition());
        outState.putString(STATE_CHAT_REASONING, selectedChatReasoning());
        outState.putString(STATE_CHAT_BOOTSTRAP_REASONING, selectedChatBootstrapReasoning());
        outState.putBoolean(STATE_CHAT_ADVANCED_EXPANDED, chatAdvancedExpanded);
        outState.putString(STATE_PROJECT, project == null ? SelfRunScript.GENERAL_CHAT_URL : selectedProjectUrl());
        outState.putString(STATE_ATTACHMENTS, SelfRunStore.encodeAttachmentDrafts(selectedAttachments));
    }

    private void restoreDraftState(Bundle state) {
        if (state == null) {
            String preferred = ProfileRegistry.resolveChat(ChatReasoningPreferenceStore.EXTRA_HIGH) != null
                    ? ChatReasoningPreferenceStore.EXTRA_HIGH
                    : (ProfileRegistry.listChat().isEmpty() ? ChatReasoningPreferenceStore.KEEP
                    : ProfileRegistry.listChat().get(0).signalReasoning);
            refreshChatReasoningOptions(preferred);
            refreshChatBootstrapReasoningOptions(BOOTSTRAP_SAME_AS_TASK);
            chatAdvancedExpanded = false;
            renderAttachments();
            updateChatReasoningAvailability();
            return;
        }
        requirement.setText(state.getString(STATE_REQUIREMENT, ""));
        int modePosition = Math.max(0, Math.min(MODE_VALUES.length - 1, state.getInt(STATE_MODE, 0)));
        mode.setSelection(modePosition);
        String reasoning = state.getString(STATE_CHAT_REASONING, ChatReasoningPreferenceStore.KEEP);
        refreshChatReasoningOptions(reasoning);
        String bootstrap = state.getString(STATE_CHAT_BOOTSTRAP_REASONING, BOOTSTRAP_SAME_AS_TASK);
        refreshChatBootstrapReasoningOptions(bootstrap);
        chatAdvancedExpanded = state.getBoolean(STATE_CHAT_ADVANCED_EXPANDED, false);
        selectedAttachments.clear();
        selectedAttachments.addAll(SelfRunStore.decodeAttachmentDrafts(state.getString(STATE_ATTACHMENTS, "")));
        selectProjectUrl(state.getString(STATE_PROJECT, SelfRunScript.GENERAL_CHAT_URL));
        renderAttachments();
        updateChatReasoningAvailability();
    }

    private void selectProjectUrl(String url) {
        if (SelfRunScript.isGeneralChatUrl(url)) { project.setSelection(0); return; }
        for (int i = 0; i < projectEntries.size(); i++) {
            if (projectEntries.get(i).canonicalUrl.equals(url)) { project.setSelection(i + 1); return; }
        }
    }

    private void reloadProjects() { reloadProjects(store.defaultProjectUrl()); }

    private void reloadProjects(String preferredUrl) {
        String previous = preferredUrl == null ? "" : preferredUrl;
        projectEntries = catalog.entries();
        ArrayList<String> labels = new ArrayList<>();
        labels.add("일반채팅");
        int selected = 0;
        for (int i = 0; i < projectEntries.size(); i++) {
            ProjectUrlPolicy.ProjectRef entry = projectEntries.get(i);
            labels.add(catalog.displayName(entry));
            if (entry.canonicalUrl.equals(previous)) selected = i + 1;
        }
        project.setItems(labels.toArray(new String[0]));
        project.setSelection(selected);
    }

    private String selectedProjectUrl() {
        int position = project.getSelectedItemPosition();
        return position <= 0 ? SelfRunScript.GENERAL_CHAT_URL : projectEntries.get(position - 1).canonicalUrl;
    }
}
