package com.shaterguy.chatgptselfrun;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

/** Exercise restartable branch routing using serialized state, not mocked coordinator calls. */
public final class SelfRun3DisposableExecutionTest {
    @Test public void twoBranchesDispatchSequentiallyAndOnlyMergeCanContinue() {
        SelfRun3Engine.State root=wave();
        assertEquals("A",root.text("branchId"));
        root=dispatch(root);
        assertEquals("B",root.text("branchId"));
        root=dispatch(root);
        assertEquals(2,SelfRun3Engine.activeCount(root));
        root=new SelfRun3Engine.State(root.json());
        String a=branch(root,"A").turnId(), b=branch(root,"B").turnId();
        root=completeBranch(root,a,"COMPLETE");
        assertEquals(1,SelfRun3Engine.activeCount(root));
        assertFalse(hasMerge(root));
        root=completeBranch(root,b,"PARTIAL");
        assertEquals("PARALLEL_MERGE",root.text("executionKind"));
        assertEquals(0,SelfRun3Engine.activeCount(root));
        assertEquals(2,root.json().optJSONArray("mergedFrom").length());
        assertEquals(4,root.turn());
        assertEquals(4,root.history().length());
        assertEquals("doc-task:turn:1",root.text("previousResultDocumentId"));
    }
    @Test public void legacyCommittedCheckpointMigratesWithoutReexecutingOldWork() {
        SelfRun3Engine.State initial=dispatch(initial()); JSONObject raw=initial.json();
        raw.remove("executions"); raw.remove("history"); put(raw,"stage","RECONCILING"); put(raw,"ended",true);
        JSONObject config=raw.optJSONObject("config"); put(config,"reasoning",""); put(config,"chatBootstrap","medium");
        JSONObject legacyResult=result(initial), handoff=legacyResult.optJSONObject("handoff");
        for(String k:new String[]{"requirements","decisions","assumptions","materials","external_state","verification_state","do_not_repeat"}) handoff.remove(k);
        put(raw,"result",legacyResult.toString());
        SelfRun3Engine.State migrated=new SelfRun3Engine.State(raw);
        assertTrue(migrated.flag("legacyContract"));
        migrated=event(migrated,migrated.turnId(),SelfRun3Engine.Kind.COMMIT,new JSONObject());
        assertEquals(2,migrated.turn()); assertEquals(SelfRun3Engine.Stage.PREPARING,migrated.stage());
        assertFalse(migrated.flag("legacyContract")); assertEquals("medium",migrated.config().optString("reasoning"));
        assertEquals("doc-task:turn:1",migrated.text("previousResultDocumentId"));
    }
    @Test public void delayedDispatchCallbackCannotHideCommittedResult() {
        SelfRun3Engine.State s=dispatch(initial()); s=accept(s,s.turnId(),result(s));
        JSONObject p=new JSONObject(); put(p,"requestId",s.requestId());
        s=event(s,s.turnId(),SelfRun3Engine.Kind.ACCEPTED,p);
        assertEquals(SelfRun3Engine.Action.COMMIT,SelfRun3Engine.nextAction(s));
    }
    @Test public void pendingReconciliationCannotStarveUndispatchedSiblingAfterRestart() {
        SelfRun3Engine.State root=dispatch(wave());
        assertEquals("B",root.text("branchId"));
        String a=branch(root,"A").turnId();
        root=event(root,a,SelfRun3Engine.Kind.RECONCILE,new JSONObject());
        root=new SelfRun3Engine.State(root.json());
        assertEquals("B",root.text("branchId"));
        assertEquals(SelfRun3Engine.Action.PREPARE_TURN,SelfRun3Engine.nextAction(root));
        assertTrue(root.text("inputText").isEmpty()); assertTrue(root.text("branchInputText").isEmpty());
        assertEquals(1,SelfRun3Engine.waitingExecutions(root).size());
    }
    @Test public void repairReplacesOnlyFailedBranchAndStillRequiresMerge() {
        SelfRun3Engine.State root=dispatch(dispatch(wave()));
        String a=branch(root,"A").turnId(), b=branch(root,"B").turnId();
        JSONObject repair=new JSONObject(); put(repair,"safeToRepair",true);
        root=event(root,a,SelfRun3Engine.Kind.REPAIR,repair);
        assertEquals("REPAIR",root.text("executionKind")); assertTrue(SelfRun3Engine.isBranch(root));
        assertTrue(root.execution(a).flag("superseded")); assertEquals(1,SelfRun3Engine.activeCount(root));
        String replacement=root.turnId(); root=dispatch(root);
        root=completeBranch(root,replacement,"COMPLETE"); assertFalse(hasMerge(root));
        root=completeBranch(root,b,"COMPLETE"); assertTrue(hasMerge(root));
        JSONArray ids=root.json().optJSONArray("mergedFrom");
        for(int i=0;i<ids.length();i++) assertNotEquals("doc-"+a,ids.optString(i));
        assertTrue(ids.toString().contains("doc-"+replacement));
    }
    @Test public void pausedParallelPlanCannotDispatchUntilResume() {
        SelfRun3Engine.State s=dispatch(initial()); JSONObject r=result(s);
        put(r,"status","PAUSED"); put(r,"next_execution",plan());
        s=accept(s,s.turnId(),r); s=event(s,s.turnId(),SelfRun3Engine.Kind.COMMIT,new JSONObject());
        assertEquals(SelfRun3Engine.Stage.PAUSED,s.stage()); assertEquals(0,SelfRun3Engine.activeCount(s));
        s=event(s,s.turnId(),SelfRun3Engine.Kind.RESUME,new JSONObject());
        assertEquals(SelfRun3Engine.Stage.PREPARING,s.stage()); assertEquals("A",s.text("branchId"));
    }
    @Test public void userInterventionCannotBypassMerge() {
        SelfRun3Engine.State root=dispatch(dispatch(wave()));
        String a=branch(root,"A").turnId(), b=branch(root,"B").turnId();
        root=completeBranch(root,a,"USER_ACTION_REQUIRED");
        root=completeBranch(root,b,"COMPLETE");
        assertFalse(hasMerge(root));
        assertEquals(SelfRun3Engine.Stage.WAITING_USER_INTERVENTION,root.stage());
        SelfRun3Engine.State intervention=root.execution(a);
        JSONObject r=branchResult(intervention,"USER_ACTION_RESOLVED");
        JSONObject action=new JSONObject(); put(action,"user_reported_complete",true); put(action,"resume_action","verify actual permission");
        put(r,"intervention",action);
        root=accept(root,a,r); root=event(root,a,SelfRun3Engine.Kind.COMMIT,new JSONObject());
        assertEquals("PARALLEL_MERGE",root.text("executionKind"));
    }
    @Test public void hybridRoutesBothDirectionsAndFixedModeRejectsSwitch() {
        JSONObject config=new JSONObject(); put(config,"mode","CHAT"); put(config,"reasoning","medium");
        SelfRun3Engine.applyProfile(config,profile("WORK","sol","high"),"HYBRID");
        assertEquals("WORK",config.optString("mode"));
        SelfRun3Engine.applyProfile(config,profile("CHAT","","high"),"HYBRID");
        assertEquals("CHAT",config.optString("mode"));
        assertThrows(IllegalStateException.class,()->SelfRun3Engine.applyProfile(config,profile("WORK","sol","high"),"CHAT"));
        assertThrows(IllegalStateException.class,()->SelfRun3Engine.applyProfile(config,profile("WORK","invented","high"),"HYBRID"));
    }
    @Test public void appRejectsWrongResultIdentityButNotAiCheckpointShapeDrift() {
        SelfRun3Engine.State root=dispatch(dispatch(wave())), a=branch(root,"A");
        JSONObject r=branchResult(a,"COMPLETE"); put(r,"document_id","other");
        final JSONObject wrongIdentity=r;
        assertThrows(IllegalStateException.class,()->SelfRun3Engine.parseResult(wrongIdentity.toString(),a));

        r=branchResult(a,"COMPLETE"); put(r.optJSONObject("branch_result"),"status","USER_ACTION_REQUIRED");
        final JSONObject semanticMismatch=r;
        assertNotNull(SelfRun3Engine.parseResult(semanticMismatch.toString(),a));

        r=branchResult(a,"COMPLETE"); put(r,"next_execution",plan());
        final JSONObject branchRoutingNoise=r;
        assertNotNull(SelfRun3Engine.parseResult(branchRoutingNoise.toString(),a));
    }
    @Test public void overlappingParallelHintFallsBackToSafeSerialExecution() {
        SelfRun3Engine.State s=dispatch(initial());
        JSONObject r=result(s); JSONObject p=plan();
        put(p.optJSONArray("branches").optJSONObject(0),"mutation_boundary",new JSONArray().put("github:o/r/main/src"));
        put(p.optJSONArray("branches").optJSONObject(1),"mutation_boundary",new JSONArray().put("github:o/r/main/src/A.java"));
        put(r,"next_execution",p);
        s=accept(s,s.turnId(),r);
        s=event(s,s.turnId(),SelfRun3Engine.Kind.COMMIT,new JSONObject());
        assertEquals(SelfRun3Engine.Stage.PREPARING,s.stage());
        assertEquals("WORK",s.text("phase"));
        assertFalse(SelfRun3Engine.isBranch(s));
        assertEquals(2,s.turn());
        assertThrows(IllegalStateException.class,()->SelfRun3Engine.canonicalBoundary("github:o/r/."));
    }
    @Test public void transportEndIsIgnoredAndLateHistoryIsAddressable() {
        SelfRun3Engine.State s=dispatch(initial()); String id=s.turnId();
        JSONObject p=new JSONObject(); put(p,"requestId",s.requestId()); put(p,"source","message_stream_complete");
        assertSame(s,event(s,id,SelfRun3Engine.Kind.ENDED,p));
        s=accept(s,id,result(s)); assertEquals(SelfRun3Engine.Action.COMMIT,SelfRun3Engine.nextAction(s));
        s=event(s,id,SelfRun3Engine.Kind.COMMIT,new JSONObject());
        JSONObject url=new JSONObject(); put(url,"key","conversationUrl"); put(url,"value","https://chatgpt.com/c/abc-123");
        s=event(s,id,SelfRun3Engine.Kind.RESOURCE,url);
        assertEquals("https://chatgpt.com/c/abc-123",s.execution(id).resource("conversationUrl"));
        assertEquals(2,s.turn()); assertTrue(s.resource("conversationUrl").isEmpty());
    }
    private static SelfRun3Engine.State wave() {
        SelfRun3Engine.State s=dispatch(initial()); JSONObject r=result(s); put(r,"next_execution",plan());
        s=accept(s,s.turnId(),r); return event(s,s.turnId(),SelfRun3Engine.Kind.COMMIT,new JSONObject());
    }
    private static SelfRun3Engine.State initial() {
        JSONObject c=new JSONObject(); put(c,"mode","CHAT"); put(c,"reasoning","medium");
        SelfRun3Engine.State s=SelfRun3Engine.create("task","task:turn:1",c);
        s=resource(s,"folderId","folder"); s=resource(s,"requirementDocumentId","requirements");
        return event(s,s.turnId(),SelfRun3Engine.Kind.SETUP_DONE,new JSONObject());
    }
    private static SelfRun3Engine.State dispatch(SelfRun3Engine.State s) {
        String id=s.turnId(); s=resource(s,"resultDocumentId","doc-"+id);
        JSONObject p=new JSONObject(); put(p,"prompt","bootstrap"); put(p,"inputRevision",0);
        s=event(s,id,SelfRun3Engine.Kind.TURN_READY,p);
        s=event(s,id,SelfRun3Engine.Kind.CLAIM_SEND,new JSONObject());
        put(p,"requestId",s.execution(id).requestId()); return event(s,id,SelfRun3Engine.Kind.STARTED,p);
    }
    private static SelfRun3Engine.State completeBranch(SelfRun3Engine.State root,String id,String status) {
        root=accept(root,id,branchResult(root.execution(id),status));
        return event(root,id,SelfRun3Engine.Kind.COMMIT,new JSONObject());
    }
    private static JSONObject branchResult(SelfRun3Engine.State s,String status) {
        JSONObject r=SelfRun3Engine.emptyResult(s); put(r,"committed",true); put(r,"status",status); put(r,"handoff",handoff());
        JSONObject b=new JSONObject(); put(b,"status",status); put(r,"branch_result",b);
        if("USER_ACTION_REQUIRED".equals(status)) put(r,"reason","user login needed");
        return r;
    }
    private static JSONObject result(SelfRun3Engine.State s) {
        JSONObject r=SelfRun3Engine.emptyResult(s); put(r,"committed",true); put(r,"status","CONTINUE");
        put(r,"phase_completed","PLAN"); put(r,"next_phase","WORK"); put(r,"handoff",handoff()); return r;
    }
    private static JSONObject handoff() {
        JSONObject h=new JSONObject(); put(h,"objective","test"); put(h,"next_action","continue");
        for(String k:new String[]{"completed","remaining","evidence","constraints","requirements","decisions","assumptions","materials","external_state","verification_state","do_not_repeat"}) put(h,k,new JSONArray());
        return h;
    }
    private static JSONObject plan() {
        JSONObject p=new JSONObject(); put(p,"type","PARALLEL"); put(p,"parallel_group_id","wave1");
        JSONArray b=new JSONArray(); for(String id:new String[]{"A","B"}) {
            JSONObject x=new JSONObject(); put(x,"branch_id",id); put(x,"objective","independent read "+id);
            put(x,"profile",profile("CHAT","","medium")); put(x,"mutation_boundary",new JSONArray()); b.put(x);
        } put(p,"branches",b); return p;
    }
    private static JSONObject profile(String mode,String model,String reason) {
        JSONObject p=new JSONObject(); put(p,"mode",mode); put(p,"model",model); put(p,"reasoning",reason); return p;
    }
    private static SelfRun3Engine.State branch(SelfRun3Engine.State s,String id) {
        return s.executions().stream().filter(x->id.equals(x.text("branchId"))).findFirst().orElseThrow();
    }
    private static boolean hasMerge(SelfRun3Engine.State s) { return s.executions().stream().anyMatch(x->"PARALLEL_MERGE".equals(x.text("executionKind"))); }
    private static SelfRun3Engine.State accept(SelfRun3Engine.State s,String id,JSONObject r) {
        JSONObject p=new JSONObject(); put(p,"text",r.toString()); return event(s,id,SelfRun3Engine.Kind.RESULT,p);
    }
    private static SelfRun3Engine.State resource(SelfRun3Engine.State s,String k,String value) {
        JSONObject p=new JSONObject(); put(p,"key",k); put(p,"value",value); return event(s,s.turnId(),SelfRun3Engine.Kind.RESOURCE,p);
    }
    private static SelfRun3Engine.State event(SelfRun3Engine.State s,String id,SelfRun3Engine.Kind kind,JSONObject p) {
        return SelfRun3Engine.reduce(s,new SelfRun3Engine.Event(id+":"+kind,kind,s.taskId(),id,p));
    }
    private static void put(JSONObject o,String k,Object v) { SelfRun3Engine.put(o,k,v); }
}
