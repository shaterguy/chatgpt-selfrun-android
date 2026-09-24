package com.shaterguy.chatgptselfrun;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import static org.junit.Assert.*;

public final class SelfRun3PromptContractTest {
    @Test public void promptKeepsOnlyRequiredPointersAndConditionalUserInput() throws Exception {
        SelfRun3Engine.State state=state("WORK","WORK","NORMAL");
        String prompt=SelfRun3Protocol.prompt(state,"current user constraint");

        assertTrue(prompt.startsWith("TASK_ID=task\nSELF_RUN_SKILL_DOCUMENT_ID="+SelfRun3Protocol.SKILL_DOCUMENT_ID+"\n"));
        for(String value : new String[]{
                "RESULT_DOCUMENT_ID=result","REQUIREMENT_DOCUMENT_ID=requirement",
                "PREVIOUS_RESULT_DOCUMENT_ID=previous","current user constraint"}) assertTrue(value,prompt.contains(value));

        for(String removed : new String[]{
                "[SELF_RUN_V3","TURN_ID=","REQUEST_ID=","TURN=","PHASE=","TASK_MODE=","MODE=",
                "EXECUTION_KIND=","SIGNAL_TYPE=","FOLDER_ID=","RECEIPT=","EXECUTION_PROFILE=",
                "[RESULT_DOCUMENT_CONTRACT]","정해진 필드명, 구조, 값의 타입과 상태전이"})
            assertFalse(removed,prompt.contains(removed));

        assertFalse(prompt.contains("[PROFILE_REGISTRY_"));
        assertFalse(prompt.contains("requirement body must not be copied"));
        assertFalse(prompt.contains("RESULT_IDENTITY_TEMPLATE"));
        assertFalse(prompt.contains("\"request\":"));
        assertFalse(prompt.contains("\"operations\":"));
        assertFalse(prompt.contains("\"fingerprint\":"));
        assertFalse(prompt.contains("phase_completed"));
        assertFalse(prompt.contains("full checkpoint"));
    }

    @Test public void initialResultCarriesRemovedRuntimePolicyContext() {
        SelfRun3Engine.State state=state("HYBRID","CHAT","NORMAL");
        JSONObject context=SelfRun3Engine.emptyResult(state).optJSONObject("dispatch_context");
        assertNotNull(context);
        assertEquals("WORK",context.optString("phase"));
        assertEquals("HYBRID",context.optString("task_mode"));
        assertEquals("folder",context.optString("task_folder_id"));
        assertEquals(3,context.length());
    }

    @Test public void hybridPromptOmitsEveryProfileRegistryChoice() throws Exception {
        String prompt=SelfRun3Protocol.prompt(state("HYBRID","CHAT","NORMAL"),"");
        assertFalse(prompt.contains("[PROFILE_REGISTRY_"));
    }

    @Test public void branchMergeRepairAndInterventionContextStayConditional() throws Exception {
        JSONObject branchRaw=state("HYBRID","WORK","PARALLEL_BRANCH").json();
        SelfRun3Engine.put(branchRaw,"parallelGroupId","group-1");
        SelfRun3Engine.put(branchRaw,"branchId","A");
        SelfRun3Engine.put(branchRaw,"branchObjective","isolated work");
        SelfRun3Engine.put(branchRaw,"mutationBoundary",new JSONArray().put("github:owner/repo/branch/path"));
        SelfRun3Engine.put(branchRaw,"branchPlan",new JSONArray().put(new JSONObject().put("branch_id","A")));
        String branch=SelfRun3Protocol.prompt(new SelfRun3Engine.State(branchRaw),"");
        assertTrue(branch.contains("PARALLEL_GROUP_ID=group-1"));
        assertTrue(branch.contains("BRANCH_ID=A"));
        assertTrue(branch.contains("BRANCH_OBJECTIVE=isolated work"));
        assertTrue(branch.contains("MUTATION_BOUNDARY="));
        assertTrue(branch.contains("BRANCH_PLAN="));
        assertFalse(branch.contains("PROFILE_REGISTRY_"));

        JSONObject mergeRaw=state("HYBRID","WORK","PARALLEL_MERGE").json();
        SelfRun3Engine.put(mergeRaw,"parallelGroupId","group-1");
        SelfRun3Engine.put(mergeRaw,"mergedFrom",new JSONArray().put("result-a").put("result-b"));
        String merge=SelfRun3Protocol.prompt(new SelfRun3Engine.State(mergeRaw),"");
        assertTrue(merge.contains("MERGED_FROM=[\"result-a\",\"result-b\"]"));
        assertFalse(merge.contains("BRANCH_OBJECTIVE="));

        JSONObject repairRaw=state("WORK","WORK","REPAIR").json();
        SelfRun3Engine.put(repairRaw,"repairTargetDocumentId","bad-result");
        SelfRun3Engine.put(repairRaw,"nextInput","confirmed correction");
        SelfRun3Engine.put(repairRaw,"intervention",new JSONObject().put("user_reported_complete",true));
        String repair=SelfRun3Protocol.prompt(new SelfRun3Engine.State(repairRaw),"");
        assertTrue(repair.startsWith("TASK_ID=task\nSELF_RUN_SKILL_DOCUMENT_ID="+SelfRun3Protocol.SKILL_DOCUMENT_ID+"\n"));
        assertTrue(repair.contains("REPAIR_TARGET_DOCUMENT_ID=bad-result"));
        assertTrue(repair.contains("[이전 턴에서 확정된 다음 입력]\nconfirmed correction"));
        assertTrue(repair.contains("[사용자 개입 결과·실제 상태 검증 필요]"));
    }

    @Test public void sourceCannotReintroduceStaticContractOrDriveOwnedPayloads() throws Exception {
        String source=src("SelfRun3Protocol.java");
        assertFalse(source.contains("REQUEST_ID"));
        assertFalse(source.contains("TURN_ID"));
        assertFalse(source.contains("TASK_MODE"));
        assertFalse(source.contains("EXECUTION_PROFILE"));
        assertFalse(source.contains("FOLDER_ID"));
        assertFalse(source.contains("RESULT_DOCUMENT_RULES"));
        assertFalse(source.contains("[RESULT_DOCUMENT_CONTRACT]"));
        assertFalse(source.contains("compactWorkProfileChoices"));
        assertFalse(source.contains("PROFILE_REGISTRY_"));
        assertFalse(source.matches("(?s).*static\\s+final\\s+String\\s+CONTRACT\\s*=.*"));
        assertFalse(source.contains("RESULT_IDENTITY_TEMPLATE"));
        assertFalse(source.contains("SelfRun3Engine.emptyResult"));
        assertFalse(source.contains("ProfileRegistry.exportChatJson"));
        assertFalse(source.contains("ProfileRegistry.exportWorkJson"));
        assertFalse(source.contains("[최초 요구사항 원문]"));
        assertFalse(source.contains("optString(\"requirement\")"));
        assertFalse(source.contains("phase_completed"));
        assertFalse(source.contains("full checkpoint"));
        assertFalse(source.contains("USER_ACTION_RESOLVED"));
    }

    @Test public void serviceAndLaunchMarkerStayV3Only() throws Exception {
        String service=src("SelfRunService.java");
        String marker=src("SelfRunSignalTransport.java");
        assertTrue(service.contains("V3_STALE_RUN_RETIRED"));
        assertFalse(service.contains("SelfRunRolloverCoordinator"));
        assertTrue(marker.contains("SelfRun3RunMarker.mark"));
        assertTrue(marker.contains("SelfRun3RunMarker.current"));
    }

    private static SelfRun3Engine.State state(String taskMode,String mode,String executionKind) {
        JSONObject config=new JSONObject();
        SelfRun3Engine.put(config,"taskMode",taskMode);
        SelfRun3Engine.put(config,"mode",mode);
        SelfRun3Engine.put(config,"model","WORK".equals(mode)?"sol":"");
        SelfRun3Engine.put(config,"reasoning","WORK".equals(mode)?"high":"xhigh");
        SelfRun3Engine.put(config,"requirement","requirement body must not be copied");
        JSONObject resources=new JSONObject();
        SelfRun3Engine.put(resources,"resultDocumentId","result");
        SelfRun3Engine.put(resources,"requirementDocumentId","requirement");
        SelfRun3Engine.put(resources,"folderId","folder");
        JSONObject raw=new JSONObject();
        SelfRun3Engine.put(raw,"schema",SelfRun3Engine.STATE_SCHEMA);
        SelfRun3Engine.put(raw,"stage",SelfRun3Engine.Stage.SETUP.name());
        SelfRun3Engine.put(raw,"taskId","task");
        SelfRun3Engine.put(raw,"turnId","task:turn:2");
        SelfRun3Engine.put(raw,"requestId","task:turn:2-request");
        SelfRun3Engine.put(raw,"turn",2);
        SelfRun3Engine.put(raw,"phase","WORK");
        SelfRun3Engine.put(raw,"taskMode",taskMode);
        SelfRun3Engine.put(raw,"executionKind",executionKind);
        SelfRun3Engine.put(raw,"signalType","AUTO_NEXT_TURN");
        SelfRun3Engine.put(raw,"previousResultDocumentId","previous");
        SelfRun3Engine.put(raw,"config",config);
        SelfRun3Engine.put(raw,"resources",resources);
        return new SelfRun3Engine.State(raw);
    }

    private static String between(String source,String start,String end) {
        int a=source.indexOf(start), b=source.indexOf(end,a+start.length());
        return source.substring(a+start.length(),b);
    }

    private static String after(String source,String marker) {
        return source.substring(source.indexOf(marker)+marker.length());
    }

    private static int count(String source,String marker) {
        int n=0, from=0;
        while((from=source.indexOf(marker,from))>=0) { n++; from+=marker.length(); }
        return n;
    }

    private static String src(String name) throws Exception {
        Path p=Paths.get("src/main/java/com/shaterguy/chatgptselfrun",name);
        if(!Files.exists(p)) p=Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun",name);
        return new String(Files.readAllBytes(p),StandardCharsets.UTF_8);
    }
}
