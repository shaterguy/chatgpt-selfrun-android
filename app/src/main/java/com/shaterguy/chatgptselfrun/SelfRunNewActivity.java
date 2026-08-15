package com.shaterguy.chatgptselfrun;

import android.app.Activity;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.Layout;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewParent;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

public final class SelfRunNewActivity extends Activity {
    private static final String[] MODE_LABELS = {"일반 Chat · 모델 변경 없음", "Work · 모델/추론 동적 전환"};
    private static final String[] MODE_VALUES = {SelfRunStore.MODE_CHAT, SelfRunStore.MODE_WORK};
    private static final String GENERAL_CHAT_LABEL = "일반채팅";
    private static final SecureRandom RUN_RANDOM = new SecureRandom();
    private static final char[] RUN_SUFFIX_ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();
    private static final int RUN_SUFFIX_LENGTH = 6;

    private SelfRunStore store;
    private SelfRunHistoryStore history;
    private SelfRunRunLog runLog;
    private ProjectCatalogStore catalogStore;
    private EditText requirement;
    private Spinner mode;
    private Spinner project;
    private Button refreshButton;
    private Button startButton;
    private TextView projectStatus;
    private List<ProjectCatalog.Entry> projectEntries = new ArrayList<>();
    private ProjectCatalogNavigator projectLoader;
    private int refreshGeneration;
    private boolean refreshing;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        store = new SelfRunStore(this);
        history = new SelfRunHistoryStore(this);
        runLog = new SelfRunRunLog(this);
        catalogStore = new ProjectCatalogStore(this);
        createViews();
    }

    private void createViews() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFocusableInTouchMode(true);
        root.setPadding(Ui.dp(this,18),Ui.dp(this,14),Ui.dp(this,18),Ui.dp(this,24));
        scroll.addView(root);
        root.addView(Ui.title(this,"새 SelfRun Drive 작업"));
        root.addView(Ui.button(this,"작업 목록",v->finish()));
        root.addView(Ui.body(this,"새 작업은 빈 요구사항에서 시작합니다. 이전 Run의 입력 내용·신호·오류는 이 화면에 불러오지 않습니다."));

        root.addView(Ui.section(this,"프로젝트"));
        project = new Spinner(this);
        refreshButton = Ui.button(this,"새로고침",v->refreshProjects());
        LinearLayout projectRow = new LinearLayout(this);
        projectRow.setOrientation(LinearLayout.HORIZONTAL);
        projectRow.addView(project,new LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,2f));
        projectRow.addView(refreshButton,new LinearLayout.LayoutParams(0,LinearLayout.LayoutParams.WRAP_CONTENT,1f));
        root.addView(projectRow);
        projectStatus = Ui.body(this,"");
        root.addView(projectStatus);
        loadInitialProjects();

        root.addView(Ui.section(this,"실행 모드"));
        mode = new Spinner(this);
        mode.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,MODE_LABELS));
        root.addView(mode);

        root.addView(Ui.section(this,"셀프런 명령"));
        requirement = new EditText(this);
        requirement.setHint("작업 요구사항");
        requirement.setSingleLine(false);
        requirement.setMinLines(8);
        requirement.setGravity(android.view.Gravity.TOP|android.view.Gravity.START);
        requirement.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE|InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        requirement.setVerticalScrollBarEnabled(false);
        requirement.setHorizontalScrollBarEnabled(false);
        requirement.setHorizontallyScrolling(false);
        installCommandVisibilityTracking(requirement);
        root.addView(requirement);

        root.addView(Ui.section(this,"시작"));
        startButton = Ui.button(this,"SelfRun Drive 시작",v->startSelfRun());
        root.addView(startButton);
        Ui.setContent(this,scroll);
        requirement.clearFocus();
        root.requestFocus();
    }

    private void loadInitialProjects() {
        List<ProjectCatalog.Entry> cached = catalogStore.load();
        String defaultUrl = ProjectCatalog.canonicalProjectUrl(store.defaultProjectUrl());
        boolean legacyMissing = !defaultUrl.isEmpty() && ProjectCatalog.indexOfUrl(cached, defaultUrl) < 0;
        List<ProjectCatalog.Entry> choices = new ArrayList<>();
        choices.add(new ProjectCatalog.Entry(GENERAL_CHAT_LABEL, ""));
        if (legacyMissing) choices.add(new ProjectCatalog.Entry("이전 선택 프로젝트", defaultUrl));
        choices.addAll(cached);
        applyProjectChoices(choices, defaultUrl);
        if (cached.isEmpty()) projectStatus.setText("프로젝트 목록을 새로고침하면 현재 ChatGPT 프로젝트를 불러옵니다.");
        else projectStatus.setText("저장된 프로젝트 " + cached.size() + "개");
    }

    private void applyProjectChoices(List<ProjectCatalog.Entry> choices, String preferredUrl) {
        projectEntries = new ArrayList<>(choices);
        List<String> labels = ProjectCatalog.displayLabels(projectEntries);
        project.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,labels));
        int selected = 0;
        if (preferredUrl != null && !preferredUrl.isEmpty()) {
            int found = ProjectCatalog.indexOfUrl(projectEntries, preferredUrl);
            if (found >= 0) selected = found;
        }
        project.setSelection(selected);
    }

    private String selectedProjectUrl() {
        int position = project == null ? -1 : project.getSelectedItemPosition();
        if (position < 0 || position >= projectEntries.size()) return "";
        return projectEntries.get(position).url;
    }

    private void refreshProjects() {
        if (refreshing) return;
        final int generation = ++refreshGeneration;
        final String selectedBefore = selectedProjectUrl();
        refreshing = true;
        setProjectControlsEnabled(false);
        projectStatus.setText("프로젝트 목록 새로고침 중…");
        ProjectCatalogNavigator loader = new ProjectCatalogNavigator(this);
        projectLoader = loader;
        loader.start(new ProjectCatalogNavigator.Callback() {
            @Override public void onSuccess(List<ProjectCatalog.Entry> entries) {
                if (generation != refreshGeneration || isFinishing() || isDestroyed()) return;
                try {
                    catalogStore.save(entries,System.currentTimeMillis());
                } catch (Throwable error) {
                    finishRefresh(generation,"새로고침 실패 · CACHE_WRITE_FAILED · 기존 목록을 유지합니다");
                    return;
                }
                String defaultUrl = ProjectCatalog.canonicalProjectUrl(store.defaultProjectUrl());
                boolean defaultRemoved = !defaultUrl.isEmpty() && ProjectCatalog.indexOfUrl(entries,defaultUrl) < 0;
                if (defaultRemoved) store.setDefaultProjectUrl("");
                String preferred = ProjectCatalog.canonicalProjectUrl(selectedBefore);
                if (!preferred.isEmpty() && ProjectCatalog.indexOfUrl(entries,preferred) < 0) preferred = "";
                List<ProjectCatalog.Entry> choices = new ArrayList<>();
                choices.add(new ProjectCatalog.Entry(GENERAL_CHAT_LABEL,""));
                choices.addAll(entries);
                applyProjectChoices(choices,preferred);
                String message = "프로젝트 목록 " + entries.size() + "개 갱신됨";
                if (defaultRemoved) message += " · 이전 기본 프로젝트가 없어 일반채팅으로 변경됨";
                finishRefresh(generation,message);
            }
            @Override public void onFailure(String code) {
                if (generation != refreshGeneration || isFinishing() || isDestroyed()) return;
                String safeCode = code == null || code.trim().isEmpty() ? "UNKNOWN" : code.trim();
                finishRefresh(generation,"새로고침 실패 · " + safeCode + " · 기존 목록을 유지합니다");
            }
        });
    }

    private void finishRefresh(int generation, String message) {
        if (generation != refreshGeneration) return;
        projectLoader = null;
        refreshing = false;
        setProjectControlsEnabled(true);
        projectStatus.setText(message);
    }

    private void setProjectControlsEnabled(boolean enabled) {
        if (project != null) project.setEnabled(enabled);
        if (refreshButton != null) refreshButton.setEnabled(enabled);
        if (startButton != null) startButton.setEnabled(enabled);
    }

    private void installCommandVisibilityTracking(EditText editor) {
        editor.setOnFocusChangeListener((view,hasFocus)->{if(hasFocus){editor.post(this::keepCommandCursorVisible);editor.postDelayed(this::keepCommandCursorVisible,250L);}});
        editor.setOnClickListener(view->editor.post(this::keepCommandCursorVisible));
        editor.addTextChangedListener(new TextWatcher(){@Override public void beforeTextChanged(CharSequence s,int start,int count,int after){}@Override public void onTextChanged(CharSequence s,int start,int before,int count){}@Override public void afterTextChanged(Editable s){editor.post(SelfRunNewActivity.this::keepCommandCursorVisible);}});
    }

    private void keepCommandCursorVisible() {
        EditText editor=requirement; if(editor==null||!editor.hasFocus())return;
        Layout layout=editor.getLayout(); if(layout==null||editor.getWidth()<=0)return;
        ScrollView outer=findOuterScrollView(editor); if(outer==null||outer.getHeight()<=0)return;
        int selection=Math.max(0,Math.min(editor.getSelectionStart(),editor.length()));
        int line=layout.getLineForOffset(selection); int margin=Ui.dp(this,12);
        int editorTop=descendantTopWithinScrollContent(editor,outer);
        if(editorTop<0)return;
        int caretTop=editorTop+editor.getTotalPaddingTop()+layout.getLineTop(line)-margin;
        int caretBottom=editorTop+editor.getTotalPaddingTop()+layout.getLineBottom(line)+margin;
        int currentScroll=outer.getScrollY();
        int visibleTop=currentScroll+outer.getPaddingTop();
        int visibleBottom=currentScroll+outer.getHeight()-outer.getPaddingBottom();
        int targetScroll=currentScroll;
        if(caretBottom>visibleBottom)targetScroll+=caretBottom-visibleBottom;
        else if(caretTop<visibleTop)targetScroll-=visibleTop-caretTop;
        if(targetScroll!=currentScroll)outer.scrollTo(outer.getScrollX(),Math.max(0,targetScroll));
    }

    private static int descendantTopWithinScrollContent(View descendant, ScrollView outer) {
        int top=0; View current=descendant;
        while(current!=outer){
            top+=current.getTop();
            ViewParent parent=current.getParent();
            if(!(parent instanceof View))return -1;
            current=(View)parent;
        }
        return top;
    }

    private static ScrollView findOuterScrollView(View child) {
        ViewParent parent=child.getParent();
        while(parent instanceof View){if(parent instanceof ScrollView)return (ScrollView)parent; parent=((View)parent).getParent();}
        return null;
    }

    private void startSelfRun() {
        if(refreshing){Toast.makeText(this,"프로젝트 목록 새로고침이 끝난 뒤 시작하세요.",Toast.LENGTH_LONG).show();return;}
        if(store.active()&&!store.userStopped()&&!SelfRunStore.PHASE_DONE.equals(store.phase())&&!SelfRunStore.PHASE_IDLE.equals(store.phase())){Toast.makeText(this,"현재 SelfRun Drive 작업(일시정지 포함)을 먼저 중지하세요.",Toast.LENGTH_LONG).show();return;}
        String projectUrl=selectedProjectUrl();
        String projectCanonical=projectUrl.isEmpty()?"":ProjectCatalog.canonicalProjectUrl(projectUrl);
        String request=requirement.getText().toString().trim();
        if(!projectUrl.isEmpty()&&projectCanonical.isEmpty()){Toast.makeText(this,"선택한 ChatGPT 프로젝트 주소가 유효하지 않습니다. 목록을 새로고침하세요.",Toast.LENGTH_LONG).show();return;}
        if(request.isEmpty()){Toast.makeText(this,"셀프런 명령을 입력하세요.",Toast.LENGTH_LONG).show();return;}
        if(!DriveApiClient.validFileId(store.driveRunsBaseFolderId())||!DriveApiClient.validOpaqueAccountId(store.driveAccountId())){Toast.makeText(this,"먼저 ‘Drive 실행문서 저장 위치’에서 Runs 폴더를 연결하세요.",Toast.LENGTH_LONG).show();return;}
        store.setDefaultProjectUrl(projectCanonical);
        if(!store.runId().isEmpty())history.sync(store);
        stopService(new Intent(this,SelfRunService.class));
        String selectedMode=MODE_VALUES[mode.getSelectedItemPosition()];
        String runId=newRunId();
        store.start(runId,selectedMode,projectCanonical.isEmpty()?SelfRunScript.GENERAL_CHAT_URL:projectCanonical,request);
        runLog.record(store,"UI_START","mode="+selectedMode);
        startRunner();
        Toast.makeText(this,"SelfRun Drive를 시작했습니다: "+runId,Toast.LENGTH_LONG).show();
        finish();
    }

    private void startRunner() { Intent intent=new Intent(this,SelfRunService.class); intent.setAction(SelfRunService.ACTION_RUN); if(Build.VERSION.SDK_INT>=26)startForegroundService(intent);else startService(intent); }

    @Override protected void onDestroy() {
        refreshGeneration++;
        refreshing=false;
        ProjectCatalogNavigator loader=projectLoader;
        projectLoader=null;
        if(loader!=null)loader.cancel();
        super.onDestroy();
    }

    private static String newRunId() {
        SimpleDateFormat format=new SimpleDateFormat("yyyyMMdd-HHmmss",Locale.US); format.setTimeZone(TimeZone.getTimeZone("Asia/Seoul"));
        StringBuilder suffix=new StringBuilder(RUN_SUFFIX_LENGTH); for(int i=0;i<RUN_SUFFIX_LENGTH;i++)suffix.append(RUN_SUFFIX_ALPHABET[RUN_RANDOM.nextInt(RUN_SUFFIX_ALPHABET.length)]);
        return "SR-"+format.format(new Date())+"-"+suffix;
    }
}
