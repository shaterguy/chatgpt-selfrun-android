package com.shaterguy.chatgptselfrun;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;
public class SelfRunCancelledDispatchEngineTest {
 private SelfRun3Engine.State claimed() throws Exception {
  JSONObject config=new JSONObject().put("mode","CHAT").put("reasoning","medium").put("projectUrl","https://chatgpt.com/");
  JSONObject raw=SelfRun3Engine.create("task","task:turn:1",config).json();
  raw.put("stage","DISPATCHING").put("sendClaimed",true).put("dispatchObserved",false).put("accepted",false);
  return new SelfRun3Engine.State(raw);
 }
 @Test public void provenCancelledUnsentReopensSameTurnForHigherPreparationAttempt() throws Exception {
  SelfRun3Engine.State before=claimed();JSONObject p=new JSONObject().put("requestId",before.requestId()).put("status","SERVER_CANCELLED_UNSENT:4");
  SelfRun3Engine.State after=SelfRun3Engine.reduce(before,new SelfRun3Engine.Event("unsent",SelfRun3Engine.Kind.UNSENT,before.taskId(),before.turnId(),p));
  assertEquals(SelfRun3Engine.Stage.READY,after.stage());assertFalse(after.flag("sendClaimed"));assertEquals(before.requestId(),after.requestId());assertEquals(before.turnId(),after.turnId());
 }
 @Test public void repeatedCancelledAttemptsUseDistinctUnsentEvents()throws Exception {
  SelfRun3Engine.State before=claimed();
  for(int attempt=4;attempt<=5;attempt++) {
   JSONObject p=new JSONObject().put("requestId",before.requestId()).put("status","SERVER_CANCELLED_UNSENT:"+attempt);
   SelfRun3Engine.State reopened=SelfRun3Engine.reduce(before,new SelfRun3Engine.Event("unsent:"+attempt,SelfRun3Engine.Kind.UNSENT,before.taskId(),before.turnId(),p));
   assertEquals(SelfRun3Engine.Stage.READY,reopened.stage());assertFalse(reopened.flag("sendClaimed"));
   JSONObject claim=new JSONObject().put("at",100L+attempt);
   before=SelfRun3Engine.reduce(reopened,new SelfRun3Engine.Event("claim:"+attempt,SelfRun3Engine.Kind.CLAIM_SEND,before.taskId(),before.turnId(),claim));
  }
 }
 @Test public void confirmedSubmissionCannotBeReopenedEvenWithUnsentCallback()throws Exception {
  JSONObject raw=claimed().json().put("dispatchObserved",true);SelfRun3Engine.State before=new SelfRun3Engine.State(raw);
  JSONObject p=new JSONObject().put("requestId",before.requestId()).put("status","SERVER_CANCELLED_UNSENT:4");
  assertSame(before,SelfRun3Engine.reduce(before,new SelfRun3Engine.Event("unsent",SelfRun3Engine.Kind.UNSENT,before.taskId(),before.turnId(),p)));
 }
}
