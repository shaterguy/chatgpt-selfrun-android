package com.shaterguy.chatgptselfrun;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class SelfRunProtocol {
    enum Type { NEXT, DONE, USER_ACTION, PAUSE, NONE }
    static final class Signal {
        final Type type; final String raw,runId,role,model,reasoning,actionId;
        Signal(Type type,String raw,String runId,String role,String model,String reasoning,String actionId){this.type=type;this.raw=raw;this.runId=runId;this.role=role;this.model=model;this.reasoning=reasoning;this.actionId=actionId;}
    }
    private static final Pattern BRACKET=Pattern.compile("\\[SELF_RUN_(NEXT|DONE|USER_ACTION_REQUIRED|PAUSE)\\s+([^\\]]+)]");
    private SelfRunProtocol(){}
    static Signal parseLatest(String text,String expectedRunId,String mode){
        if(text==null)return none();Matcher m=BRACKET.matcher(text);Signal last=none();
        while(m.find()){
            String kind=m.group(1),payload=m.group(2).trim();String[] parts=payload.split("\\s+");if(parts.length==0||!expectedRunId.equals(parts[0]))continue;String raw=m.group(0);
            if("DONE".equals(kind))last=new Signal(Type.DONE,raw,parts[0],"","","","");
            else if("USER_ACTION_REQUIRED".equals(kind)){String a=parts.length>1?parts[1]:"ACTION";if(safeCode(a))last=new Signal(Type.USER_ACTION,raw,parts[0],"","","",a);}
            else if("PAUSE".equals(kind))last=new Signal(Type.PAUSE,raw,parts[0],value(payload,"ROLE"),"","","");
            else{String role=value(payload,"ROLE").toUpperCase(Locale.ROOT),model=value(payload,"MODEL").toLowerCase(Locale.ROOT),reason=value(payload,"REASONING").toLowerCase(Locale.ROOT);if(SelfRunStore.MODE_CHAT.equals(mode)){model="";reason="";}else if(!validWorkProfile(model,reason))continue;if(role.isEmpty())role="BUILDER";if(safeCode(role))last=new Signal(Type.NEXT,raw,parts[0],role,model,reason,"");}
        }return last;
    }
    static boolean validWorkProfile(String model,String reasoning){if(!("sol".equals(model)||"terra".equals(model)||"luna".equals(model)))return false;if(!("high".equals(reasoning)||"xhigh".equals(reasoning)||"max".equals(reasoning)||"ultra".equals(reasoning)))return false;return !"luna".equals(model)||"max".equals(reasoning)||"ultra".equals(reasoning);}
    static String bootstrap(String runId,String mode,String requirement){return "[SELF_RUN_BOOTSTRAP 0.1.0 "+runId+" MODE="+mode+"]\n\n"+requirement.trim();}
    static String bootstrapDrive(String runId,String mode,String requirement,String documentId){return "["+kstTimestamp(new Date())+"] [SELF_RUN_BOOTSTRAP 0.1.0 "+runId+" MODE="+mode+"]\nSELF_RUN_CLIENT=DRIVE_V1\nDRIVE_TURN_DOCUMENT_ID="+documentId+"\n이 명령을 실제 수신하면 실질 작업 시작 전에 위 작업문서 끝에 `[현재 KST 시각] [SELF_RUN_COMMAND_RECEIVED "+runId+"]`를 기록하고 readback한다.\n최종 답변 직전에는 HANDOFF를 conversation에만 유지하고 상태에 맞는 Drive SelfRun 단일행 신호를 현재 KST 시각과 Job ID로 기록한 뒤 readback한다.\n\n"+requirement.trim();}
    static String continuation(String runId){return "[SELF_RUN_CONTINUE "+runId+"]";}
    static String driveContinuation(String runId){return "["+kstTimestamp(new Date())+"] "+continuation(runId);}
    static String signalRecovery(String runId){return "[SELF_RUN_SIGNAL_RECOVERY "+runId+"]";}
    static String kstTimestamp(Date date){SimpleDateFormat f=new SimpleDateFormat("yyyy.MM.dd | HH:mm:ss",Locale.US);f.setTimeZone(TimeZone.getTimeZone("Asia/Seoul"));return f.format(date);}
    private static String value(String payload,String key){Matcher m=Pattern.compile("(?:^|\\s)"+key+"=([^\\s]+)",Pattern.CASE_INSENSITIVE).matcher(payload);return m.find()?m.group(1).trim():"";}
    private static Signal none(){return new Signal(Type.NONE,"","","","","","");}
    private static boolean safeCode(String v){return v!=null&&v.matches("[A-Za-z0-9._:-]{1,80}");}
}
