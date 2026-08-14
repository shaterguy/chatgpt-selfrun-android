package com.shaterguy.chatgptselfrun;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.Layout;
import android.text.TextWatcher;
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
    private EditText projectUrl;
    private EditText requirement;
    private Spinner mode;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        store = new SelfRunStore(this); history = new SelfRunHistoryStore(this); runLog = new SelfRunRunLog(this); createViews();
    }

    private void createViews() {
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true); LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL); root.setFocusableInTouchMode(true); root.setPadding(Ui.dp(this,18),Ui.dp(this,14),Ui.dp(this,18),Ui.dp(this,24)); scroll.addView(root);
        root.addView(Ui.title(this,"새 SelfRun Drive 작업")); root.addView(Ui.button(this,"작업 목록",v->finish())); root.addView(Ui.body(this,"새 작업은 빈 요구사항에서 시작합니다. 이전 Run의 입력 내용·신호·오류는 이 화면에 불러오지 않습니다."));
        root.addView(Ui.section(this,"프로젝트 주소")); projectUrl=new EditText(this); projectUrl.setSingleLine(true); projectUrl.setHint("https://chatgpt.com/g/<project-id>"); projectUrl.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_URI); projectUrl.setText(store.defaultProjectUrl()); root.addView(projectUrl);
        root.addView(Ui.section(this,"실행 모드")); mode=new Spinner(this); mode.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,MODE_LABELS)); root.addView(mode);
        root.addView(Ui.section(this,"셀프런 명령")); requirement=new EditText(this); requirement.setHint("작업 요구사항"); requirement.setSingleLine(false); requirement.setMinLines(8); requirement.setGravity(android.view.Gravity.TOP|android.view.Gravity.START); requirement.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_FLAG_MULTI_LINE|InputType.TYPE_TEXT_FLAG_CAP_SENTENCES); requirement.setVerticalScrollBarEnabled(false); requirement.setHorizontalScrollBarEnabled(false); requirement.setHorizontallyScrolling(false); installCommandVisibilityTracking(requirement); root.addView(requirement);
        root.addView(Ui.section(this,"시작")); root.addView(Ui.button(this,"SelfRun Drive 시작",v->startSelfRun())); Ui.setContent(this,scroll); projectUrl.clearFocus(); requirement.clearFocus(); root.requestFocus();
    }

    private void installCommandVisibilityTracking(EditText editor) {
        editor.setOnFocusChangeListener((view,hasFocus)->{if(hasFocus){editor.post(this::keepCommandCursorVisible);editor.postDelayed(this::keepCommandCursorVisible,250L);}});
        editor.setOnClickListener(view->editor.post(this::keepCommandCursorVisible));
        editor.addTextChangedListener(new TextWatcher(){@Override public void beforeTextChanged(CharSequence s,int start,int count,int after){}@Override public void onTextChanged(CharSequence s,int start,int before,int count){}@Override public void afterTextChanged(Editable s){editor.post(SelfRunNewActivity.this::keepCommandCursorVisible);}});
    }

    private void keepCommandCursorVisible() {
        EditText editor=requirement; if(editor==null||!editor.hasFocus())return;
        Layout layout=editor.getLayout(); if(layout==null||editor.getWidth()<=0)return;
        int selection=Math.max(0,Math.min(editor.getSelectionStart(),editor.length()));
        int line=layout.getLineForOffset(selection); int margin=Ui.dp(this,12);
        int left=Math.max(0,editor.getTotalPaddingLeft());
        int top=Math.max(0,editor.getTotalPaddingTop()+layout.getLineTop(line)-margin);
        int right=Math.max(left+1,editor.getWidth()-editor.getTotalPaddingRight());
        int bottom=editor.getTotalPaddingTop()+layout.getLineBottom(line)+margin;
        editor.requestRectangleOnScreen(new Rect(left,top,right,bottom),true);
    }

    private void startSelfRun() {
        if(store.active()&&!store.userStopped()&&!SelfRunStore.PHASE_DONE.equals(store.phase())&&!SelfRunStore.PHASE_IDLE.equals(store.phase())){Toast.makeText(this,"현재 SelfRun Drive 작업(일시정지 포함)을 먼저 중지하세요.",Toast.LENGTH_LONG).show();return;}
        String project=projectUrl.getText().toString().trim(),request=requirement.getText().toString().trim();
        if(SelfRunScript.projectId(project).isEmpty()){Toast.makeText(this,"ChatGPT 프로젝트 주소를 확인하세요.",Toast.LENGTH_LONG).show();return;}
        if(request.isEmpty()){Toast.makeText(this,"셀프런 명령을 입력하세요.",Toast.LENGTH_LONG).show();return;}
        if(!DriveApiClient.validFileId(store.driveRunsBaseFolderId())||!DriveApiClient.validOpaqueAccountId(store.driveAccountId())){Toast.makeText(this,"먼저 ‘Drive 실행문서 저장 위치’에서 Runs 폴더를 연결하세요.",Toast.LENGTH_LONG).show();return;}
        store.setDefaultProjectUrl(project); if(!store.runId().isEmpty())history.sync(store); stopService(new Intent(this,SelfRunService.class)); String selectedMode=MODE_VALUES[mode.getSelectedItemPosition()]; String runId=newRunId(); store.start(runId,selectedMode,project,request); runLog.record(store,"UI_START","mode="+selectedMode); startRunner(); Toast.makeText(this,"SelfRun Drive를 시작했습니다: "+runId,Toast.LENGTH_LONG).show(); finish();
    }

    private void startRunner() { Intent intent=new Intent(this,SelfRunService.class); intent.setAction(SelfRunService.ACTION_RUN); if(Build.VERSION.SDK_INT>=26)startForegroundService(intent);else startService(intent); }

    private static String newRunId() {
        SimpleDateFormat format=new SimpleDateFormat("yyyyMMdd-HHmmss",Locale.US); format.setTimeZone(TimeZone.getTimeZone("Asia/Seoul"));
        StringBuilder suffix=new StringBuilder(RUN_SUFFIX_LENGTH); for(int i=0;i<RUN_SUFFIX_LENGTH;i++)suffix.append(RUN_SUFFIX_ALPHABET[RUN_RANDOM.nextInt(RUN_SUFFIX_ALPHABET.length)]);
        return "SR-"+format.format(new Date())+"-"+suffix;
    }
}
