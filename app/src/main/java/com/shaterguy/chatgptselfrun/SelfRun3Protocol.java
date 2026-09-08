package com.shaterguy.chatgptselfrun;

import org.json.JSONObject;

/** Every app dispatch is the first message of a fresh conversation. */
final class SelfRun3Protocol {
    static final String CONTRACT_VERSION="3.0.0";
    static final String SKILL_DOCUMENT_ID="1gktKYJzz4zW_M2gbJ7OsodaTE1lkx-pUtuFBV5fucJo";
    static final String CONTRACT="""
            SelfRun 3 실행 계약 (contract-version: 3.1.0)
            실행 전에 SELF_RUN_SKILL_DOCUMENT_ID의 최신 메타데이터와 전체 내용을 읽고 적용한다.
            모든 앱 실행은 새 conversation이다. 이전 ChatGPT context를 가정하지 않는다.
            실제 외부 권위 상태, canonical committed Result, 최초 Requirement 순으로 상태를 복원한다.
            PREVIOUS_RESULT_DOCUMENT_ID와 REQUIREMENT_DOCUMENT_ID를 읽고 FOLDER_ID 첨부를 필요한 범위에서 확인한다.
            이미 완료된 작업과 외부효과, 유효한 검증을 반복하지 않는다.
            PHASE=PLAN은 실행 가능한 설계와 자료를 확보한다. WORK는 알려진 수정과 자체점검을 완료한다.
            최종 수정 뒤 독립 VERIFY와 실제 산출물 전달까지 완료해야 DONE이다.
            사용자의 최신 지시와 Project 지침, 권한 범위를 준수한다.

            RESULT_DOCUMENT_ID의 기존 Google Doc을 읽고 본문을 단일 JSON으로 교체한다.
            제목을 변경하거나 별도 결과 문서를 만들지 않는다. revisionId를 사용한 원자적 batchUpdate를 우선한다.
            schema/task_id/turn_id/turn/document_id/event_id는 RESULT_IDENTITY_TEMPLATE과 정확히 일치해야 한다.
            모든 작업 및 결과 필드를 완성한 마지막 단계에서 committed를 boolean true로 설정하고 readback한다.
            화면 답변 출력이나 RECEIPT는 완료조건이 아니다. 앱은 Drive committed 결과만 관찰한다.
            status는 CONTINUE, DONE, PAUSED, USER_ACTION_REQUIRED, USER_ACTION_RESOLVED다.
            일반 실행 phase_completed는 PLAN, PLAN_PARTIAL, WORK, WORK_PARTIAL,
            VERIFY_PARTIAL, VERIFY_WORK, VERIFY_WORK_PARTIAL, VERIFY_DONE이다.
            next_phase는 PLAN, WORK, VERIFY, DONE이다.
            PLAN→WORK, PLAN_PARTIAL→PLAN, WORK→VERIFY, WORK_PARTIAL→WORK,
            VERIFY_PARTIAL/VERIFY_WORK_PARTIAL→WORK 또는 VERIFY, VERIFY_WORK→VERIFY를 사용한다.
            DONE은 현재 VERIFY, phase_completed=VERIFY_DONE, next_phase=DONE일 때만 가능하다.

            handoff는 delta가 아닌 full checkpoint이다. objective, completed, remaining, evidence,
            constraints, next_action, requirements, decisions, assumptions, materials, external_state,
            verification_state, do_not_repeat를 모두 포함한다. 해당 정보가 없으면 빈 배열/객체로 명시한다.
            최초 및 최신 요구, non_goals, 결정 근거, 문서/파일/폴더 ID, repository/branch/commit_sha/exact_diff/version,
            tests/ci_runs/artifacts/checksums, known_failures/failed_attempts/invalid_assumptions,
            remaining_verification, execution_plan/next_profile/next_input 등 실제 상태를 복원 가능하게 보존한다.
            next_input은 다음 실행에 필요한 확정 지시만 기록하고 임의 승인이나 형식적 계속 신호를 만들지 않는다.

            TASK_MODE=CHAT/WORK는 실제 모드를 고정한다. HYBRID 첫 실행은 사용자 선택을 보존한다.
            HYBRID 다음 실행부터 CHAT/WORK와 model/reasoning을 작업 성격과 비용·지연·실패비용에 맞춰 선택한다.
            CHAT↔WORK 왕복 제한은 없다. 제공 PROFILE_REGISTRY에 실제 등록된 조합만 사용한다.
            next_profile:{mode,model,reasoning}을 지정한다. CHAT model은 빈 문자열이다.
            직렬이면 next_execution:{type:"SERIAL",objective:"...",profile:{mode,model,reasoning}}이다.
            병렬이면 next_execution:{type:"PARALLEL",parallel_group_id:"새 고유 ID",branches:[...]}이다.
            branches는 정확히 2개이며 각 항목에 branch_id,objective,profile,mutation_boundary:string[]를 넣는다.
            mutation_boundary는 scheme:resource/path 형식의 정규화된 식별자 문자열 배열이며 읽기 전용이면 빈 배열이다.
            예: github:owner/repo/branch/path, drive:document-id. 공백·와일드카드·상대경로·URL 인코딩은 금지한다.
            부모 리소스와 하위 리소스는 중첩으로 본다. 모든 동일 변경 대상은 동일한 정규 식별자를 사용한다.
            동일 Git branch/source file/Google Doc/deployment/release/transaction 쓰기가 겹치면 반드시 SERIAL이다.
            의존관계·중복 외부효과 위험이 없고 실제 시간 단축이 기대될 때만 PARALLEL을 선언한다.
            불명확한 독립성은 SERIAL_REQUIRED로 판단한다. 병렬 깊이는 1, 자동 실행 동시 상한은 2이다.
            병렬 후 Merge 프로필은 next_profile로 지정한다.

            PARALLEL_BRANCH는 자신의 objective와 mutation_boundary 안에서만 작업한다.
            다른 branch 역할을 확인하고 중복 작업이나 경합 쓰기를 하지 않는다.
            branch는 next_execution/next_profile을 선언하거나 독자적 후속 실행을 시작하지 않는다.
            execution_context:{type:"PARALLEL_BRANCH",parallel_group_id,branch_id,branch_depth:1}을 그대로 유지한다.
            branch_result:{status:"COMPLETE" 또는 "PARTIAL",completed:[],remaining:[],evidence:[],merge_notes:[]}를 작성한다.
            branch의 최상위 status도 COMPLETE/PARTIAL 또는 사용자개입 상태다. 모든 branch는 Merge로만 합류한다.

            PARALLEL_MERGE는 MERGED_FROM의 두 결과, 공통 predecessor, 실제 권위 상태, 새 사용자 입력을 읽는다.
            상충·중복·실패·제약을 해결하고 completed/remaining/verification을 통합한 새 full checkpoint를 작성한다.
            execution_context:{type:"PARALLEL_MERGE",parallel_group_id}와 merged_from의 정확한 문서 ID 배열을 유지한다.
            Merge 결과가 다음 canonical predecessor다. Merge 이후에만 새 병렬 wave를 만들 수 있다.

            USER_ACTION_REQUIRED는 사용자만 해결할 실제 차단에 한한다. reason과 requested_action을 구체화한다.
            사용자는 해당 conversation에서 필요한 만큼 직접 수동 대화할 수 있다.
            사용자가 조치 완료를 알리면 본 자동작업을 계속하지 말고 동일 결과 문서에 USER_ACTION_RESOLVED를 기록한다.
            intervention:{requested_action,user_reported_complete:true,user_input_summary,user_decisions:[],
            additional_constraints:[],new_information:[],resume_action}과 기존 full handoff/identity를 보존한다.
            branch이면 branch_result.status도 USER_ACTION_RESOLVED로 바꾸고 새 branch 실행을 만들지 않는다.
            새 자동 conversation은 가능한 경우 실제 외부 상태를 검증한 뒤 재개한다.
            사용자 진술만으로 성공을 단정하지 않으며 검증 불가 시 사용자 증거로 명시한다.

            REPAIR는 새 conversation의 새 identity/result 문서를 사용한다.
            BRANCH_ID가 있는 REPAIR는 해당 branch의 대체 결과만 기록하며 PARALLEL_BRANCH 결과 계약을 따른다.
            원본 branch 결과를 덮어쓰거나 독립 후속 작업을 만들지 않고 다른 branch와 Merge로 합류한다.
            REPAIR_TARGET_DOCUMENT_ID와 실제 권위 상태를 읽어 결과 기록만 복구한다.
            코드 수정·배포·외부효과·완료된 검증을 다시 실행하지 않는다. 증거가 없으면 PARTIAL로 남긴다.
            앱 PAUSE는 로컬 제어이고 기존 대화로 자동 continuation을 보내지 않는다.
            마지막 가시 줄에 RECEIPT를 그대로 출력한다.
            """;
    static String receipt(SelfRun3Engine.State s) { return "[SELF_RUN_3_RESULT "+s.turnId()+"]"; }
    static String prompt(SelfRun3Engine.State s,String userInput) {
        StringBuilder out=new StringBuilder("[SELF_RUN_V3 "+CONTRACT_VERSION+"]\n");
        field(out,"SELF_RUN_SKILL_DOCUMENT_ID",SKILL_DOCUMENT_ID);
        field(out,"TASK_ID",s.taskId()); field(out,"TURN_ID",s.turnId()); field(out,"REQUEST_ID",s.requestId());
        field(out,"TURN",String.valueOf(s.turn())); field(out,"PHASE",s.text("phase"));
        field(out,"TASK_MODE",s.taskMode()); field(out,"MODE",s.config().optString("mode"));
        field(out,"EXECUTION_KIND",s.text("executionKind")); field(out,"SIGNAL_TYPE",s.text("signalType"));
        field(out,"RESULT_DOCUMENT_ID",s.resource("resultDocumentId")); field(out,"REQUIREMENT_DOCUMENT_ID",s.resource("requirementDocumentId"));
        field(out,"FOLDER_ID",s.resource("folderId")); field(out,"PREVIOUS_RESULT_DOCUMENT_ID",s.text("previousResultDocumentId"));
        field(out,"RECEIPT",receipt(s)); field(out,"EXECUTION_PROFILE",SelfRun3Engine.executionProfile(s).toString());
        if(!s.text("parallelGroupId").isEmpty()) field(out,"PARALLEL_GROUP_ID",s.text("parallelGroupId"));
        if(SelfRun3Engine.isBranch(s)) {
            field(out,"BRANCH_ID",s.text("branchId")); field(out,"BRANCH_OBJECTIVE",s.text("branchObjective"));
            field(out,"MUTATION_BOUNDARY",String.valueOf(s.json().optJSONArray("mutationBoundary")));
            field(out,"BRANCH_PLAN",String.valueOf(s.json().optJSONArray("branchPlan")));
        }
        if("PARALLEL_MERGE".equals(s.text("executionKind"))) field(out,"MERGED_FROM",String.valueOf(s.json().optJSONArray("mergedFrom")));
        if("REPAIR".equals(s.text("executionKind"))) field(out,"REPAIR_TARGET_DOCUMENT_ID",s.text("repairTargetDocumentId"));
        out.append('\n').append(CONTRACT).append("\n[RESULT_IDENTITY_TEMPLATE]\n").append(SelfRun3Engine.emptyResult(s)).append('\n');
        out.append("\n[PROFILE_REGISTRY_CHAT]\n").append(ProfileRegistry.exportChatJson(CONTRACT_VERSION));
        out.append("\n[PROFILE_REGISTRY_WORK]\n").append(ProfileRegistry.exportWorkJson(CONTRACT_VERSION)).append('\n');
        out.append("\n[최초 요구사항 원문]\n").append(s.config().optString("requirement")).append('\n');
        if(!s.text("nextInput").isEmpty()) out.append("\n[이전 실행의 확정 입력]\n").append(s.text("nextInput")).append('\n');
        if(s.json().has("intervention")) out.append("\n[사용자 개입 결과·실제 상태 검증 필요]\n").append(s.json().optJSONObject("intervention")).append('\n');
        if(userInput!=null && !userInput.isEmpty()) out.append("\n[사용자 추가 지시 원문]\n").append(userInput).append('\n');
        return out.toString();
    }
    static String repair(SelfRun3Engine.State s,String nextRequestId) { return prompt(s,"결과 기록만 복구하고 기존 외부효과를 재실행하지 않습니다."); }
    private static void field(StringBuilder b,String key,String value) { b.append(key).append('=').append(value).append('\n'); }
    private SelfRun3Protocol() {}
}
