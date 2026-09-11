package com.shaterguy.chatgptselfrun;

import org.json.JSONArray;
import org.json.JSONObject;

/** Dynamic SelfRun 3 envelope. Canonical execution semantics live in the Drive SKILL. */
final class SelfRun3Protocol {
    static final String CONTRACT_VERSION="3.1.0";
    static final String SKILL_DOCUMENT_ID="1gktKYJzz4zW_M2gbJ7OsodaTE1lkx-pUtuFBV5fucJo";
    static final String SELF_RUN_SKILL_DOCUMENT_ID=SKILL_DOCUMENT_ID;
    private static final String RESULT_DOCUMENT_RULES =
            "작업문서는 반드시 SELF_RUN_SKILL_DOCUMENT_ID의 최신 전체 원문에 정의된 Result 작성양식과 의미 규칙을 그대로 따라 작성하십시오. " +
            "정해진 필드명, 구조, 값의 타입과 상태전이를 임의로 변경하거나 필요한 내용을 생략하지 마십시오. " +
            "앱이 허용하거나 다음 AI가 복구할 수 있다는 이유로 양식을 바꾸지 마십시오. " +
            "지정된 기존 RESULT_DOCUMENT_ID의 초기 identity를 보존하고 본문에는 설명이나 코드블록 없이 단일 JSON 객체만 기록하십시오. " +
            "작성 완료 후에만 committed를 boolean true로 확정하고 같은 문서를 다시 읽어 저장된 내용을 검증하십시오.";

    static String receipt(SelfRun3Engine.State s) {
        return "[SELF_RUN_3_RESULT "+s.turnId()+"]";
    }

    static String prompt(SelfRun3Engine.State s,String userInput) {
        return envelope(s,userInput,false);
    }

    static String repair(SelfRun3Engine.State s,String nextRequestId) {
        return envelope(s,"",true);
    }

    private static String envelope(SelfRun3Engine.State s,String userInput,boolean forceRepair) {
        boolean repair=forceRepair || "REPAIR".equals(s.text("executionKind"));
        StringBuilder out=new StringBuilder("[SELF_RUN_V3 "+CONTRACT_VERSION);
        if(repair) out.append(" RESULT_REPAIR=1");
        out.append("]\n");
        field(out,"SELF_RUN_SKILL_DOCUMENT_ID",SKILL_DOCUMENT_ID);
        field(out,"TASK_ID",s.taskId());
        field(out,"TURN_ID",s.turnId());
        field(out,"REQUEST_ID",s.requestId());
        field(out,"TURN",String.valueOf(s.turn()));
        field(out,"PHASE",s.text("phase"));
        field(out,"TASK_MODE",s.taskMode());
        field(out,"MODE",s.config().optString("mode"));
        field(out,"EXECUTION_KIND",s.text("executionKind"));
        field(out,"SIGNAL_TYPE",s.text("signalType"));
        field(out,"RESULT_DOCUMENT_ID",s.resource("resultDocumentId"));
        field(out,"REQUIREMENT_DOCUMENT_ID",s.resource("requirementDocumentId"));
        field(out,"PREVIOUS_RESULT_DOCUMENT_ID",s.text("previousResultDocumentId"));
        field(out,"FOLDER_ID",s.resource("folderId"));
        field(out,"RECEIPT",receipt(s));
        field(out,"EXECUTION_PROFILE",SelfRun3Engine.executionProfile(s).toString());

        if(!s.text("parallelGroupId").isEmpty()) field(out,"PARALLEL_GROUP_ID",s.text("parallelGroupId"));
        if(SelfRun3Engine.isBranch(s)) {
            field(out,"BRANCH_ID",s.text("branchId"));
            field(out,"BRANCH_OBJECTIVE",s.text("branchObjective"));
            field(out,"MUTATION_BOUNDARY",String.valueOf(s.json().optJSONArray("mutationBoundary")));
            field(out,"BRANCH_PLAN",String.valueOf(s.json().optJSONArray("branchPlan")));
        }
        if("PARALLEL_MERGE".equals(s.text("executionKind")))
            field(out,"MERGED_FROM",String.valueOf(s.json().optJSONArray("mergedFrom")));
        if(repair)
            field(out,"REPAIR_TARGET_DOCUMENT_ID",s.text("repairTargetDocumentId"));

        out.append("\n[RESULT_DOCUMENT_CONTRACT]\n").append(RESULT_DOCUMENT_RULES).append('\n');
        if(!SelfRun3Engine.isBranch(s)) appendProfileChoices(out,s.taskMode());
        if(!s.text("nextInput").isEmpty())
            out.append("\n[이전 턴에서 확정된 다음 입력]\n").append(s.text("nextInput")).append('\n');
        if(s.json().has("intervention"))
            out.append("\n[사용자 개입 결과·실제 상태 검증 필요]\n").append(s.json().optJSONObject("intervention")).append('\n');
        if(userInput!=null && !userInput.isEmpty())
            out.append("\n[사용자 추가 지시 원문]\n").append(userInput).append('\n');
        return out.toString();
    }

    private static void appendProfileChoices(StringBuilder out,String taskMode) {
        if("CHAT".equals(taskMode) || "HYBRID".equals(taskMode))
            out.append("\n[PROFILE_REGISTRY_CHAT]\n").append(compactProfileChoices(ProfileRegistry.Mode.CHAT)).append('\n');
        if("WORK".equals(taskMode) || "HYBRID".equals(taskMode))
            out.append("\n[PROFILE_REGISTRY_WORK]\n").append(compactProfileChoices(ProfileRegistry.Mode.WORK)).append('\n');
    }

    private static String compactProfileChoices(ProfileRegistry.Mode mode) {
        JSONArray choices=new JSONArray();
        for(ProfileRegistry.Profile profile : mode==ProfileRegistry.Mode.CHAT
                ? ProfileRegistry.listChat() : ProfileRegistry.listWork()) {
            if(mode==ProfileRegistry.Mode.CHAT && !"xhigh".equals(profile.signalReasoning)) continue;
            JSONObject signal=new JSONObject();
            if(mode==ProfileRegistry.Mode.WORK)
                SelfRun3Engine.put(signal,"model",profile.signalModel);
            SelfRun3Engine.put(signal,"reasoning",profile.signalReasoning);
            choices.put(signal);
        }
        return choices.toString();
    }

    private static void field(StringBuilder b,String key,String value) {
        b.append(key).append('=').append(value).append('\n');
    }

    private SelfRun3Protocol() {}
}
