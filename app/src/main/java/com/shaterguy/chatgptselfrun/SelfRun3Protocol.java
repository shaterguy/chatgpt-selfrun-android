package com.shaterguy.chatgptselfrun;

/** Minimal SelfRun 3 dispatch envelope. Canonical execution semantics live in the Drive SKILL and Result documents. */
final class SelfRun3Protocol {
    static final String CONTRACT_VERSION="3.1.0";
    static final String SKILL_DOCUMENT_ID="1gktKYJzz4zW_M2gbJ7OsodaTE1lkx-pUtuFBV5fucJo";
    static final String SELF_RUN_SKILL_DOCUMENT_ID=SKILL_DOCUMENT_ID;

    static String prompt(SelfRun3Engine.State s,String userInput) {
        return envelope(s,userInput,false);
    }

    static String repair(SelfRun3Engine.State s,String nextRequestId) {
        return envelope(s,"",true);
    }

    private static String envelope(SelfRun3Engine.State s,String userInput,boolean forceRepair) {
        boolean repair=forceRepair || "REPAIR".equals(s.text("executionKind"));
        StringBuilder out=new StringBuilder();
        field(out,"TASK_ID",s.taskId());
        field(out,"SELF_RUN_SKILL_DOCUMENT_ID",SKILL_DOCUMENT_ID);
        field(out,"RESULT_DOCUMENT_ID",s.resource("resultDocumentId"));
        field(out,"REQUIREMENT_DOCUMENT_ID",s.resource("requirementDocumentId"));
        String previousResultDocumentId=s.text("previousResultDocumentId");
        if(!previousResultDocumentId.isEmpty())
            field(out,"PREVIOUS_RESULT_DOCUMENT_ID",previousResultDocumentId);

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
