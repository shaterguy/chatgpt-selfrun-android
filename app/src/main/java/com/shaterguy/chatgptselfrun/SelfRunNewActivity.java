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
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Toast;

import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

public final class SelfRunNewActivity extends Activity {
    private static final String[] MODE_LABELS = {"일반 Chat · 모델 변경 없음", "Work · 모델/추론 동적 전환"};
    private static final String[] MODE_VALUES = {SelfRunStore.MODE_CHAT, SelfRunStore.MODE_WORK};
    private static final SecureRandom RUN_RANDOM = new SecureRandom();
    private static final char[] RUN_SUFFIX_ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();
    private static final int RUN_SUFFIX_LENGTH = 6;

    private SelfRunStore store;
    private SelfRunHistoryStore history;
    private SelfRunRunLog runLog;
    private Spinner project;
    private ProjectCatalog catalog;
    private java.util.List<ProjectUrlPolicy.ProjectRef> projectEntries;
    private EditText requirement;
    private Spinner mode;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        store = new SelfRunStore(this); history = new SelfRunHistoryStore(this); runLog = new SelfRunRunLog(this); catalog = new ProjectCatalog(this); createViews();
    }

    private void createViews() {
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL); root.setFocusableInTouchMode(true); root.setPadding(Ui.dp(this,18),Ui.dp(this,14),Ui.dp(this,18),Ui.dp(this,24)); scroll.addView(root);
        root.addView(Ui.title(this,"새 SelfRun Drive 작업")); root.addView(Ui.button(this,"작업 목록",v->finish())); root.addView(Ui.body(this,"새 작업은 빈 요구사항에서 시작합니다. 이전 Run의 입력 내용·신호·오류는 이 화면에 불러오지 않습니다."));
        root.addView(Ui.section(this,"프로젝트 선택")); project=new Spinner(this); root.addView(project); root.addView(Ui.body(this,"등록할 프로젝트를 직접 열면 목록에 추가됩니다. 전체 목록을 자동 탐색하거나 메뉴를 자동 클릭하지 않습니다.")); root.addView(Ui.row(this,Ui.button(this,"프로젝트 등록/업데이트",v->startActivity(new Intent(this,LoginActivity.class))),Ui.button(this,"등록 목록 지우기",v->{catalog.clear();store.setDefaultProjectUrl("");reloadProjects();Toast.makeText(this,"등록 프로젝트 목록을 지웠습니다.",Toast.LENGTH_SHORT).show();}))); reloadProjects();
        root.addView(Ui.section(this,"실행 모드")); mode=new Spinner(this); mode.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,MODE_LABELS)); root.addView(mode);
        root.addView(Ui.section(this,"셀프런 명령")); requirement=new EditText(this); requirement.setHint("작업 요구사항"); requirement.setSingleLine(false); requirement.setMinLines(8); requirement.setGravity(android.view.Gravity.TOP|android.view.Gravity.START); requirement.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE|InputType.TYPE_TEXT_FLAG_CAP_SENTENCES); requirement.setVerticalScrollBarEnabled(false); requirement.setHorizontalScrollBarEnabled(false); requirement.setHorizontallyScrolling(false); installCommandVisibilityTracking(requirement); root.addView(requirement);
        root.addView(Ui.section(this,"시작")); root.addView(Ui.button(this,"SelfRun Drive 시작",v->startSelfRun())); Ui.setContent(this,scroll); project.clearFocus(); requirement.clearFocus(); root.requestFocus();
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
        if(store.active()&&!store.userStopped()&&!SelfRunStore.PHASE_DONE.equals(store.phase())&&!SelfRunStore.PHASE_IDLE.equals(store.phase())){Toast.makeText(this,"현재 SelfRun Drive 작업(일시정지 포함)을 먼저 중지하세요.",Toast.LENGTH_LONG).show();return;}
        String project=selectedProjectUrl(),request=requirement.getText().toString().trim();
        if(request.isEmpty()){Toast.makeText(this,"셀프런 명령을 입력하세요.",Toast.LENGTH_LONG).show();return;}
        if(!DriveApiClient.validFileId(store.driveRunsBaseFolderId())||!DriveApiClient.validOpaqueAccountId(store.driveAccountId())){Toast.makeText(this,"먼저 ‘Drive 실행문서 저장 위치’에서 Runs 폴더를 연결하세요.",Toast.LENGTH_LONG).show();return;}
        store.setDefaultProjectUrl(project); if(!store.runId().isEmpty())history.sync(store); stopService(new Intent(this,SelfRunService.class)); String selectedMode=MODE_VALUES[mode.getSelectedItemPosition()]; String runId=newRunId(); store.start(runId,selectedMode,project,request); runLog.record(store,"UI_START","mode="+selectedMode); startRunner(); Toast.makeText(this,"SelfRun Drive를 시작했습니다: "+runId,Toast.LENGTH_LONG).show(); finish();
    }

    private void startRunner() { Intent intent=new Intent(this,SelfRunService.class); intent.setAction(SelfRunService.ACTION_RUN); if(Build.VERSION.SDK_INT>=26)startForegroundService(intent);else startService(intent); }

    @Override protected void onResume() { super.onResume(); if(project!=null) reloadProjects(); }

    private void reloadProjects() {
        String previous=store.defaultProjectUrl(); projectEntries=catalog.entries(); java.util.ArrayList<String> labels=new java.util.ArrayList<>(); labels.add("일반채팅"); int selected=0;
        for(int i=0;i<projectEntries.size();i++){ProjectUrlPolicy.ProjectRef entry=projectEntries.get(i);labels.add(ProjectCatalog.displayName(entry));if(entry.canonicalUrl.equals(previous))selected=i+1;}
        project.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,labels)); project.setSelection(selected);
    }

    private String selectedProjectUrl() { int position=project.getSelectedItemPosition(); return position<=0?SelfRunScript.GENERAL_CHAT_URL:projectEntries.get(position-1).canonicalUrl; }

    private static String newRunId() {
        SimpleDateFormat format=new SimpleDateFormat("yyyyMMdd-HHmmss",Locale.US); format.setTimeZone(TimeZone.getTimeZone("Asia/Seoul"));
        StringBuilder suffix=new StringBuilder(RUN_SUFFIX_LENGTH); for(int i=0;i<RUN_SUFFIX_LENGTH;i++)suffix.append(RUN_SUFFIX_ALPHABET[RUN_RANDOM.nextInt(RUN_SUFFIX_ALPHABET.length)]);
        return "SR-"+format.format(new Date())+"-"+suffix;
    }
}
