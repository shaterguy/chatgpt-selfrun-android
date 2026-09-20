package com.shaterguy.chatgptselfrun;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.io.IOException;
import java.util.UUID;
import static org.junit.Assert.*;

/** Behavioral recovery tests with controllable Drive reads and serialized process restarts. */
public final class SelfRun3RecoveryBehaviorTest {
    @Test public void waitingStopCommittedContinuesWithoutSendingOldTurn() throws Exception {
        SelfRun3Engine.State old=waiting(); String id=old.turnId();
        SelfRun3Engine.State resumed=resume(stop(old),x -> result(x,"CONTINUE").toString());
        assertEquals(SelfRun3Engine.Action.COMMIT,SelfRun3Engine.nextAction(resumed));
        SelfRun3Engine.State next=event(resumed,SelfRun3Engine.Kind.COMMIT,new JSONObject());
        assertEquals(2,next.turn()); assertTrue(next.execution(id).flag("committed"));
        assertEquals(1,next.executions().stream().filter(x->x.flag("sendClaimed")).count());
    }
    @Test public void commitFailureAndReconciliationAreReplacedByFreshDriveState() throws Exception {
        SelfRun3Engine.State state=accept(waiting(),result(waiting(),"CONTINUE"));
        state=event(state,SelfRun3Engine.Kind.ERROR,obj("code","V3_COMMIT_FAILED"));
        state=event(state,SelfRun3Engine.Kind.PAUSE,obj("reason","V3_COMMIT_FAILED"));
        SelfRun3Engine.State resumed=resume(stop(state),x->result(x,"DONE").toString());
        assertTrue(resumed.text("error").isEmpty()); assertFalse(resumed.flag("taskPaused"));
        assertEquals(SelfRun3Engine.Stage.DONE,event(resumed,SelfRun3Engine.Kind.COMMIT,new JSONObject()).stage());
    }
    @Test public void pendingRestartsSameIdentityButFencesOldRequestAndKeepsInput() throws Exception {
        SelfRun3Engine.State old=waiting();
        SelfRun3Engine.State resumed=resume(stop(old),x->SelfRun3Engine.emptyResult(x).toString());
        assertEquals(old.turnId(),resumed.turnId()); assertEquals(old.turn(),resumed.turn());
        assertEquals(old.resource("resultDocumentId"),resumed.resource("resultDocumentId"));
        assertEquals(old.resource("requirementDocumentId"),resumed.resource("requirementDocumentId"));
        assertEquals(old.text("inputText"),resumed.text("inputText"));
        assertNotEquals(old.requestId(),resumed.requestId()); assertTrue(resumed.resource("conversationUrl").isEmpty());
        assertFalse(resumed.flag("sendClaimed")); assertEquals(SelfRun3Engine.Stage.PREPARING,resumed.stage());
        assertEquals(old.requestId(),resumed.json().optJSONArray("stoppedAttempts").optJSONObject(0).optString("requestId"));
        assertSame(resumed,event(resumed,SelfRun3Engine.Kind.STARTED,obj("requestId",old.requestId())));
        assertTrue(SelfRun3Protocol.prompt(resumed,"").contains("처음부터 다시 시작"));
    }
    @Test public void lateCommitAtPreparationIsConsumedWithoutNewClaim() throws Exception {
        SelfRun3Engine.State resumed=resume(stop(waiting()),x->SelfRun3Engine.emptyResult(x).toString());
        resumed=accept(resumed,result(resumed,"DONE"));
        assertFalse(resumed.flag("sendClaimed"));
        assertEquals(SelfRun3Engine.Stage.DONE,event(resumed,SelfRun3Engine.Kind.COMMIT,new JSONObject()).stage());
    }
    @Test public void lateCommitAfterWebPreparationPreventsClaim() throws Exception {
        SelfRun3Engine.State resumed=resume(stop(waiting()),x->SelfRun3Engine.emptyResult(x).toString());
        resumed=event(resumed,SelfRun3Engine.Kind.TURN_READY,obj("prompt","retry"));
        resumed=accept(resumed,result(resumed,"CONTINUE"));
        assertSame(resumed,event(resumed,SelfRun3Engine.Kind.CLAIM_SEND,obj("at",12L)));
        assertFalse(resumed.flag("sendClaimed"));
    }
    @Test public void doneAndInterventionRestoreWithoutReexecution() throws Exception {
        for(String status:new String[]{"DONE","USER_ACTION_REQUIRED"}) {
            SelfRun3Engine.State resumed=resume(stop(waiting()),x->result(x,status).toString());
            resumed=event(resumed,SelfRun3Engine.Kind.COMMIT,new JSONObject());
            assertEquals("DONE".equals(status)?SelfRun3Engine.Stage.DONE:SelfRun3Engine.Stage.WAITING_USER_INTERVENTION,resumed.stage());
            assertEquals(1,resumed.turn());
        }
    }
    @Test public void alreadyCommittedDoneAndInterventionResolutionAreRestored() throws Exception {
        SelfRun3Engine.State done=event(accept(waiting(),result(waiting(),"DONE")),SelfRun3Engine.Kind.COMMIT,new JSONObject());
        assertEquals(SelfRun3Engine.Stage.DONE,resume(stop(done),x->result(x,"DONE").toString()).stage());
        SelfRun3Engine.State intervention=event(accept(waiting(),result(waiting(),"USER_ACTION_REQUIRED")),SelfRun3Engine.Kind.COMMIT,new JSONObject());
        SelfRun3Engine.State resumed=resume(stop(intervention),x->result(x,"USER_ACTION_RESOLVED").toString());
        assertEquals(2,event(resumed,SelfRun3Engine.Kind.COMMIT,new JSONObject()).turn());
    }
    @Test public void readFailureAndForeignIdentityLeaveOriginalStopped() {
        SelfRun3Engine.State stopped=stop(waiting()); String before=stopped.json().toString();
        assertThrows(IOException.class,()->resume(stopped,x->{throw new IOException("offline");}));
        assertThrows(IllegalStateException.class,()->resume(stopped,x->{JSONObject r=SelfRun3Engine.emptyResult(x);put(r,"task_id","other");return r.toString();}));
        assertEquals(before,stopped.json().toString()); assertEquals(SelfRun3Engine.Stage.STOPPED,stopped.stage());
    }
    @Test public void processRestartRepeatedStopAndStaleRecoveryAreSafe() throws Exception {
        SelfRun3Engine.State stopped=stop(waiting());
        JSONObject plan=SelfRun3StoppedRecovery.plan(stopped,x->SelfRun3Engine.emptyResult(x).toString());
        SelfRun3Engine.State stoppedAgain=stop(stopped);
        assertThrows(IllegalStateException.class,()->event(stoppedAgain,SelfRun3Engine.Kind.RESUME_STOPPED,plan));
        SelfRun3Engine.State resumed=resume(new SelfRun3Engine.State(stoppedAgain.json()),x->SelfRun3Engine.emptyResult(x).toString());
        String firstRequest=resumed.requestId();
        resumed=resume(stop(resumed),x->SelfRun3Engine.emptyResult(x).toString());
        assertNotEquals(firstRequest,resumed.requestId()); assertEquals(1,resumed.turn());
        assertEquals(2,resumed.json().optJSONArray("stoppedAttempts").length());
    }
    @Test public void pauseResumeStillPreservesWaitingAndRequest() {
        SelfRun3Engine.State old=waiting();
        SelfRun3Engine.State resumed=event(event(old,SelfRun3Engine.Kind.PAUSE,new JSONObject()),SelfRun3Engine.Kind.RESUME,new JSONObject());
        assertEquals(SelfRun3Engine.Stage.WAITING,resumed.stage()); assertEquals(old.requestId(),resumed.requestId()); assertTrue(resumed.flag("sendClaimed"));
    }
    @Test public void missingProfileModelReasoningAndInvalidCombinationCreateRepair() {
        for(String bad:new String[]{"profile","model","reasoning","combination","mode"}) {
            SelfRun3Engine.State old=waiting(); JSONObject r=result(old,"CONTINUE");
            JSONObject p=r.optJSONObject("next_profile");
            if("profile".equals(bad)) r.remove("next_profile");
            else if("combination".equals(bad)) put(p,"model","invented");
            else if("mode".equals(bad)) put(p,"mode","WORK");
            else p.remove(bad);
            SelfRun3Engine.State repair=event(accept(old,r),SelfRun3Engine.Kind.COMMIT,new JSONObject());
            assertEquals(bad,"REPAIR",repair.text("executionKind"));
            assertEquals(old.config().toString(),repair.config().toString());
            assertEquals(old.resource("resultDocumentId"),repair.text("repairTargetDocumentId"));
            assertEquals(r.toString(),repair.execution(old.turnId()).text("result"));
            assertTrue(repair.resource("resultDocumentId").isEmpty()); assertEquals(2,repair.turn());
            assertTrue(SelfRun3Protocol.prompt(repair,"").contains("REPAIR_TARGET_DOCUMENT_ID="+old.resource("resultDocumentId")));
        }
    }
    @Test public void multipleProblemsAreSpecificAndRepairNeverUsesBrokenNextProfile() {
        SelfRun3Engine.State old=waiting(); JSONObject r=result(old,"CONTINUE");
        put(r,"next_profile",obj("mode","WORK"));
        SelfRun3Engine.State repair=event(accept(old,r),SelfRun3Engine.Kind.COMMIT,new JSONObject());
        String prompt=SelfRun3Protocol.prompt(repair,"");
        for(String text:new String[]{"next_profile.mode","next_profile.model","next_profile.reasoning","MISSING","CONFLICT","직접 읽고","기존 committed Result는 수정하거나 덮어쓰지"}) assertTrue(text,prompt.contains(text));
        assertEquals("CHAT",repair.config().optString("mode"));
        assertEquals("gpt-5-6-thinking",repair.config().optString("model"));
        assertEquals("medium",repair.config().optString("reasoning"));
        assertEquals(SelfRun3Engine.Action.PREPARE_TURN,SelfRun3Engine.nextAction(repair));
    }
    @Test public void unusedSemanticFieldsAndRedundantMissingProfileDoNotCauseRepair() {
        SelfRun3Engine.State old=waiting(); JSONObject r=result(old,"CONTINUE");
        JSONObject p=r.optJSONObject("next_profile"); r.remove("next_profile");
        put(r,"next_execution",obj("type","SERIAL","profile",p));
        SelfRun3Engine.State next=event(accept(old,r),SelfRun3Engine.Kind.COMMIT,new JSONObject());
        assertEquals("NORMAL",next.text("executionKind")); assertEquals(2,next.turn());
        r=result(old,"CONTINUE"); r.optJSONObject("next_profile").remove("mode");
        assertEquals("NORMAL",event(accept(old,r),SelfRun3Engine.Kind.COMMIT,new JSONObject()).text("executionKind"));
    }
    @Test public void validAlternateProfileCanRecoverUnusablePrimaryWithoutRepair() {
        SelfRun3Engine.State old=waiting(); JSONObject r=result(old,"CONTINUE");
        put(r,"next_execution",obj("type","SERIAL","profile",obj("mode","WORK")));
        assertEquals("NORMAL",event(accept(old,r),SelfRun3Engine.Kind.COMMIT,new JSONObject()).text("executionKind"));
    }
    @Test public void contradictoryUsableProfilesRepairAndDoNotGuess() {
        SelfRun3Engine.State old=waiting(); JSONObject r=result(old,"CONTINUE");
        JSONObject p=SelfRun3Engine.copy(r.optJSONObject("next_profile"));put(p,"reasoning","high");
        put(r,"next_execution",obj("type","SERIAL","profile",p));
        SelfRun3Engine.State repair=event(accept(old,r),SelfRun3Engine.Kind.COMMIT,new JSONObject());
        assertEquals("REPAIR",repair.text("executionKind")); assertTrue(repair.json().optJSONArray("repairProblems").toString().contains("CONFLICT"));
    }
    @Test public void localStateErrorsCannotBeClassifiedAsResultRepair() {
        SelfRun3Engine.State old=waiting();
        assertThrows(IllegalStateException.class,()->event(old,SelfRun3Engine.Kind.COMMIT,new JSONObject()));
        SelfRun3Engine.State paused=event(accept(old,result(old,"CONTINUE")),SelfRun3Engine.Kind.PAUSE,new JSONObject());
        assertThrows(IllegalStateException.class,()->event(paused,SelfRun3Engine.Kind.COMMIT,new JSONObject()));
    }
    @Test public void corruptLocalExecutionProfileIsNotAResultDefect() {
        SelfRun3Engine.State old=waiting(); JSONObject raw=old.json();
        put(raw.optJSONObject("config"),"reasoning","corrupt-local-value");
        SelfRun3Engine.State corrupt=new SelfRun3Engine.State(raw);
        JSONObject broken=result(corrupt,"CONTINUE"); broken.remove("next_profile");
        SelfRun3Engine.State accepted=accept(corrupt,broken);
        IllegalStateException failure=assertThrows(IllegalStateException.class,
                ()->event(accepted,SelfRun3Engine.Kind.COMMIT,new JSONObject()));
        assertFalse(failure instanceof SelfRun3RoutingException);
        assertEquals("NORMAL",accepted.text("executionKind"));
    }
    @Test public void terminalResultAndLateInputDoNotNeedNextProfile() {
        SelfRun3Engine.State old=waiting(); JSONObject done=result(old,"DONE");done.remove("next_profile");
        SelfRun3Engine.State accepted=accept(old,done);
        assertEquals(SelfRun3Engine.Stage.DONE,event(accepted,SelfRun3Engine.Kind.COMMIT,new JSONObject()).stage());
        SelfRun3Engine.State next=event(accepted,SelfRun3Engine.Kind.COMMIT,obj("lateInput",true));
        assertEquals("NORMAL",next.text("executionKind"));assertEquals("PLAN",next.text("phase"));
        assertEquals(old.config().optString("model"),next.config().optString("model"));
    }
    @Test public void initialChatKeepPolicyCanContinueAndRepairWithoutInventingModel() {
        SelfRun3Engine.State old=waiting(); JSONObject raw=old.json();
        put(raw.optJSONObject("config"),"model","");put(raw.optJSONObject("config"),"reasoning","keep");
        old=new SelfRun3Engine.State(raw);
        JSONObject result=result(old,"CONTINUE");
        assertEquals("NORMAL",event(accept(old,result),SelfRun3Engine.Kind.COMMIT,new JSONObject()).text("executionKind"));
        result.remove("next_profile");
        SelfRun3Engine.State repair=event(accept(old,result),SelfRun3Engine.Kind.COMMIT,new JSONObject());
        assertEquals("REPAIR",repair.text("executionKind"));assertEquals(old.config().toString(),repair.config().toString());
    }
    @Test public void malformedRoutingTypesReportInvalidFieldsTogether() {
        SelfRun3Engine.State old=waiting(); JSONObject bad=result(old,"CONTINUE");
        put(bad,"next_profile","wrong");put(bad,"next_execution",new JSONArray());
        SelfRun3Engine.State repair=event(accept(old,bad),SelfRun3Engine.Kind.COMMIT,new JSONObject());
        String problems=repair.json().optJSONArray("repairProblems").toString();
        assertTrue(problems.contains("next_profile"));assertTrue(problems.contains("next_execution"));
        assertTrue(problems.contains("INVALID"));assertEquals("REPAIR",repair.text("executionKind"));
    }
    @Test public void repairCommitContinuesFromNewDocumentAndPreservesOriginal() {
        SelfRun3Engine.State old=waiting(); JSONObject broken=result(old,"CONTINUE"); broken.remove("next_profile");
        SelfRun3Engine.State repair=event(accept(old,broken),SelfRun3Engine.Kind.COMMIT,new JSONObject());
        repair=resource(repair,"resultDocumentId","repair-document");
        repair=accept(repair,result(repair,"CONTINUE"));
        SelfRun3Engine.State next=event(repair,SelfRun3Engine.Kind.COMMIT,new JSONObject());
        assertEquals(3,next.turn());assertEquals("repair-document",next.text("previousResultDocumentId"));
        assertEquals(broken.toString(),next.execution(old.turnId()).text("result"));
        assertTrue(next.text("repairReason").isEmpty());
    }

    static SelfRun3Engine.State waiting() {
        SelfRun3Engine.State s=SelfRun3Engine.create("recovery-test","recovery-test:turn:1",obj("mode","CHAT","model","gpt-5-6-thinking","reasoning","medium","taskMode","CHAT","requirement","original"));
        s=resource(s,"folderId","folder");s=resource(s,"requirementDocumentId","requirements");
        s=event(s,SelfRun3Engine.Kind.SETUP_DONE,new JSONObject()); s=resource(s,"resultDocumentId","result-one");
        s=event(s,SelfRun3Engine.Kind.TURN_READY,obj("prompt","original","inputText","keep input","inputRevision",4L));
        s=event(s,SelfRun3Engine.Kind.CLAIM_SEND,obj("at",10L));
        return event(s,SelfRun3Engine.Kind.STARTED,obj("requestId",s.requestId()));
    }
    static SelfRun3Engine.State stop(SelfRun3Engine.State s) {return event(s,SelfRun3Engine.Kind.STOP,new JSONObject());}
    static SelfRun3Engine.State resume(SelfRun3Engine.State s,SelfRun3StoppedRecovery.Reader r) throws Exception {return event(s,SelfRun3Engine.Kind.RESUME_STOPPED,SelfRun3StoppedRecovery.plan(s,r));}
    static SelfRun3Engine.State accept(SelfRun3Engine.State s,JSONObject r) {return event(s,SelfRun3Engine.Kind.RESULT,obj("text",r.toString()));}
    static JSONObject result(SelfRun3Engine.State s,String status) {JSONObject r=SelfRun3Engine.emptyResult(s);put(r,"committed",true);put(r,"status",status);put(r,"next_phase","WORK");put(r,"next_profile",obj("mode","CHAT","model","gpt-5-6-thinking","reasoning","medium"));return r;}
    static SelfRun3Engine.State resource(SelfRun3Engine.State s,String k,String v) {return event(s,SelfRun3Engine.Kind.RESOURCE,obj("key",k,"value",v));}
    static SelfRun3Engine.State event(SelfRun3Engine.State s,SelfRun3Engine.Kind k,JSONObject p) {return SelfRun3Engine.reduce(s,new SelfRun3Engine.Event(UUID.randomUUID().toString(),k,s.taskId(),s.turnId(),p));}
    static JSONObject obj(Object... args) {JSONObject r=new JSONObject();for(int i=0;i<args.length;i+=2)put(r,(String)args[i],args[i+1]);return r;}
    static void put(JSONObject o,String k,Object v) {SelfRun3Engine.put(o,k,v);}
}
