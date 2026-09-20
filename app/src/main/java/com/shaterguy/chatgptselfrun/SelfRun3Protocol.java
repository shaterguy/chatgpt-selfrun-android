package com.shaterguy.chatgptselfrun;

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
        if(repair && s.json().has("repairProblems")) {
            field(out,"REPAIR_REASON",s.text("repairReason"));
            field(out,"REPAIR_PROBLEMS",s.json().optJSONArray("repairProblems").toString());
        }

        out.append("\n[RESULT_DOCUMENT_CONTRACT]\n").append(RESULT_DOCUMENT_RULES).append('\n');
        if(repair && "RESULT_ROUTING_INVALID".equals(s.text("repairReason")))
            out.append("\n[RESULT_REPAIR]\n")
                    .append("REPAIR_TARGET_DOCUMENT_ID의 기존 Result 작업문서 전체를 직접 읽고 REPAIR_PROBLEMS에 기록된 실제 누락·오류·모순을 복구하십시오. ")
                    .append("기존 committed Result는 수정하거나 덮어쓰지 마십시오. 정상 내용과 완료된 외부 작업을 보존하고, ")
                    .append("현재 envelope의 새 identity를 사용해 복구된 전체 Result를 현재 RESULT_DOCUMENT_ID에 작성하십시오. ")
                    .append("현재 REPAIR 대화방의 EXECUTION_PROFILE은 문제 발생 턴의 실제 실행정보이며 복구할 다음 실행 profile을 뜻하지 않습니다.\n");
        if(s.flag("stoppedRestart"))
            out.append("\n[완전 중지 후 동일 턴 재시작]\n")
                    .append("Drive의 현재 작업문서와 Requirement, 이전 Result 및 실제 외부 작업상태를 먼저 확인하고, ")
                    .append("미완료인 현재 턴을 처음부터 다시 시작하십시오. 이미 완료된 외부 작업은 확인 후 재사용하며 중복 수행하지 마십시오. ")
                    .append("현재 Result가 이미 committed:true이면 같은 턴의 본 작업을 다시 수행하거나 Result를 덮어쓰지 마십시오.\n");
        if(!s.text("nextInput").isEmpty())
            out.append("\n[이전 턴에서 확정된 다음 입력]\n").append(s.text("nextInput")).append('\n');
        if(s.json().has("intervention"))
            out.append("\n[사용자 개입 결과·실제 상태 검증 필요]\n").append(s.json().optJSONObject("intervention")).append('\n');
        if(userInput!=null && !userInput.isEmpty())
            out.append("\n[사용자 추가 지시 원문]\n").append(userInput).append('\n');
        return out.toString();
    }

    private static void field(StringBuilder b,String key,String value) {
        b.append(key).append('=').append(value).append('\n');
    }

    private SelfRun3Protocol() {}
}
