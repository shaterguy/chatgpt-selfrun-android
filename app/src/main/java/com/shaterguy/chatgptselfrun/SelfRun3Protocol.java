package com.shaterguy.chatgptselfrun;

import org.json.JSONObject;

/** Pinned, self-contained V3 contract. No global V2 title-signal document is modified or required. */
final class SelfRun3Protocol {
    static final String CONTRACT_VERSION = "3.0.0";
    static final String CONTRACT = """
            SelfRun 3 실행 계약 (contract-version: 3.0.0)
            이 실행은 구형 DRIVE_V1/0.2.0 실행이 아니다. 아래 V3 계약이 앱 통신 형식의 유일한 기준이다.
            구형 SELF_RUN_TURN_COMPLETED/DONE 제목 문서를 새로 만들거나 문서 제목으로 신호를 보내지 않는다.
            현재 사용자 요구와 해당 ChatGPT Project의 최신 업무·개발·검증·권한 지침은 함께 준수한다.
            허가 범위를 넓히거나 새 비용·외부 계정·권한·사용자 승인을 만들어내지 않는다.

            1. 작업과 턴
            TASK_ID는 같은 사용자 작업의 영구 식별자다. TURN_ID는 논리 턴, REQUEST_ID는 물리 전송 식별자다.
            앱이 지정한 PHASE를 수행한다. 최초 PLAN은 실행 가능한 설계·근거·다음 작업 재료를 확보한다.
            WORK는 현재 실행 가능한 알려진 수정과 자체점검을 마친 뒤 새 턴의 VERIFY에 넘긴다.
            VERIFY는 실제 원본·기존 유효 테스트·readback을 독립적으로 판정한다. 실패하면 재작업한다.
            최종 수정 이후 필요한 VERIFY가 통과하고 요청한 실제 산출물 전달까지 끝난 경우에만 DONE이다.
            이미 끝난 작업·외부 효과·유효한 검증은 반복하지 않는다. 시간 경과나 앱 재시도는 완료 증거가 아니다.
            같은 대화에서도 PREVIOUS_RESULT_DOCUMENT_ID가 있으면 해당 체크포인트와 현재 실제 상태를 확인한다.
            REQUIREMENT_DOCUMENT_ID는 최초 요구 원문이다. 이후 사용자 추가·정정·철회는 최신 유효 지시로 반영한다.
            FOLDER_ID의 첨부파일은 현재 작업의 참고 자료다. 결과 문서와 최초 요구 문서는 첨부 자료로 중복 해석하지 않는다.

            2. 턴 결과 저장
            턴 종료 전에 RESULT_DOCUMENT_ID가 가리키는 정확한 기존 Google Doc 본문을 읽는다.
            문서 제목은 변경하지 않는다. 새 결과 문서를 만들지 않는다. 본문을 하나의 JSON 객체로 교체한다.
            가능하면 현재 revisionId를 사용하여 삭제·삽입을 같은 batchUpdate에 적용한다.
            단일 JSON 외 설명·마크다운·코드펜스는 넣지 않는다. 앱이 제공한 schema/task_id/turn_id/turn/document_id/event_id를 그대로 사용한다.
            미완성 상태의 committed:false를 완료로 바꾸기 전에 아래 필드를 모두 작성한다.
            status: CONTINUE, DONE, PAUSED, USER_ACTION_REQUIRED 중 하나다.
            phase_completed: PLAN, PLAN_PARTIAL, WORK, WORK_PARTIAL, VERIFY_PARTIAL, VERIFY_WORK, VERIFY_WORK_PARTIAL, VERIFY_DONE 중 하나다.
            next_phase: PLAN, WORK, VERIFY, DONE 중 하나다.
            정상 전이: PLAN→WORK, WORK→VERIFY, VERIFY_DONE→DONE이다.
            WORK_PARTIAL→WORK, VERIFY_PARTIAL→VERIFY 또는 WORK, VERIFY_WORK→VERIFY, VERIFY_WORK_PARTIAL→WORK를 사용한다.
            PLAN_PARTIAL은 최초 계획이 실제로 미완성일 때만 PLAN으로 이어간다.
            DONE은 현재 PHASE=VERIFY, phase_completed=VERIFY_DONE, next_phase=DONE일 때만 가능하다.
            handoff는 전체 체크포인트 객체다. objective, completed, remaining, evidence, constraints, next_action 필드를 모두 포함한다.
            handoff에는 원본 요구와 최신 추가 지시·결정·완료 증거·정확한 파일/커밋/문서 식별자·미완료 항목을 복원 가능하게 보존한다.
            필요하면 handoff에 plan, materials, assumptions, requirements, verification 등의 필드를 추가한다.
            next_input은 실제로 다음 user-role 입력에 전달해야 하는 확정된 내용만 문자열로 기록하며, 없으면 빈 문자열이다.
            임의 승인, 반복 계속 지시, 형식적인 턴 수 채우기를 next_input에 넣지 않는다.
            MODE=WORK의 CONTINUE에는 PROFILE_REGISTRY의 동일 항목에 있는 signal.model과 signal.reasoning으로 profile 객체를 기록한다.
            최초 PLAN에서 이후 실행·검증을 감당하는 효율적인 유효 조합을 선택하고, 구체적인 변경 사유가 없으면 유지한다.
            MODE=CHAT의 profile은 생략할 수 있으며 앱에 선택된 Chat 프로필을 임의로 바꾸지 않는다.
            USER_ACTION_REQUIRED는 실제 로그인·미제공 정보·물리 조치 등 사용자만 해결할 구체적인 차단 입력이 있을 때만 사용한다.
            단순 안내·열람·확인·기기 사후 수락시험은 중단 사유가 아니다. 정지는 reason을 함께 기록한다.
            모든 필드가 완성되면 committed를 boolean true로 설정한다. 쓰기 결과가 불명확하면 같은 문서를 먼저 readback한다.
            정확한 문서 ID·JSON 본문·모든 식별자·committed:true를 readback한 뒤에만 사용자 가시 응답을 마감한다.
            마지막 가시 줄에는 앱이 제공한 RECEIPT를 문자 그대로 출력한다. 이 줄 자체가 논리적 완료 증거를 대신하지 않는다.

            3. 정지·복구
            사용자의 중지·일시정지는 우선하며 새로운 본 작업을 진행하지 않는다. 이미 확정된 상태는 정확히 보존한다.
            RESULT_REPAIR=1 요청은 직전 논리 턴의 결과 기록만 복구한다. 본 작업·코드 수정·배포·검증을 다시 실행하지 않는다.
            직전 단계 완료를 실제 증거로 확인할 수 없으면 해당 단계 PARTIAL과 같은 next_phase를 사용한다.
            복구 문서를 썼다는 이유로 phase를 전진시키거나 DONE으로 처리하지 않는다.
            이 제품에는 AI 유지보수·자체 코드 수정·자동 패치 에이전트가 없다. 이 계약은 작업 수행과 결과 전달만을 규정한다.
            """;

    static String receipt(SelfRun3Engine.State s) { return "[SELF_RUN_3_RESULT " + s.turnId() + "]"; }
    static String prompt(SelfRun3Engine.State s, String userInput) {
        JSONObject config = s.config();
        StringBuilder out = new StringBuilder();
        out.append("[SELF_RUN_V3 ").append(CONTRACT_VERSION).append("]\n")
                .append("TASK_ID=").append(s.taskId()).append('\n')
                .append("TURN_ID=").append(s.turnId()).append('\n')
                .append("REQUEST_ID=").append(s.requestId()).append('\n')
                .append("TURN=").append(s.turn()).append('\n')
                .append("PHASE=").append(s.text("phase")).append('\n')
                .append("MODE=").append(config.optString("mode")).append('\n')
                .append("RESULT_DOCUMENT_ID=").append(s.resource("resultDocumentId")).append('\n')
                .append("REQUIREMENT_DOCUMENT_ID=").append(s.resource("requirementDocumentId")).append('\n')
                .append("FOLDER_ID=").append(s.resource("folderId")).append('\n')
                .append("PREVIOUS_RESULT_DOCUMENT_ID=").append(s.text("previousResultDocumentId")).append('\n')
                .append("RECEIPT=").append(receipt(s)).append("\n\n")
                .append(CONTRACT).append("\n[RESULT_IDENTITY_TEMPLATE]\n")
                .append(SelfRun3Engine.emptyResult(s).toString()).append('\n');
        if ("WORK".equals(config.optString("mode"))) {
            out.append("\nPROFILE_REGISTRY=\n").append(ProfileRegistry.exportWorkJson(CONTRACT_VERSION)).append('\n');
        }
        if (s.turn() == 1) out.append("\n[최초 요구사항 원문]\n").append(config.optString("requirement")).append('\n');
        if (!s.text("nextInput").isEmpty()) out.append("\n[이전 턴에서 확정된 다음 입력]\n").append(s.text("nextInput")).append('\n');
        if (userInput != null && !userInput.isEmpty()) out.append("\n[사용자 추가 지시 원문]\n").append(userInput).append('\n');
        return out.toString();
    }
    static String repair(SelfRun3Engine.State s, String nextRequestId) {
        return "[SELF_RUN_V3 RESULT_REPAIR=1]\nTASK_ID=" + s.taskId()
                + "\nTURN_ID=" + s.turnId() + "\nREQUEST_ID=" + nextRequestId
                + "\nPHASE=" + s.text("phase") + "\nRESULT_DOCUMENT_ID=" + s.resource("resultDocumentId")
                + "\nRECEIPT=" + receipt(s) + "\n\n" + CONTRACT
                + "\n직전 논리 턴의 결과 문서가 없거나 유효한 형식이 아닙니다. 본 작업을 반복하지 말고 지정 문서의 결과만 복원하십시오."
                + " 완료 증거가 없으면 PARTIAL로 보존하십시오.\n[RESULT_IDENTITY_TEMPLATE]\n" + SelfRun3Engine.emptyResult(s).toString();
    }
    private SelfRun3Protocol() {}
}
