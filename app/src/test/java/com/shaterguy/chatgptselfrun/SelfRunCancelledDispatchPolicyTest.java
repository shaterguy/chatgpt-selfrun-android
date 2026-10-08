package com.shaterguy.chatgptselfrun;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;
public class SelfRunCancelledDispatchPolicyTest {
 private JSONObject body()throws Exception {
  JSONObject proof=new JSONObject().put("schema",1).put("task_id","task").put("turn_id","turn").put("request_id","request").put("dispatch_attempt",4).put("control_epoch",5).put("never_submitted",true).put("cleanup_confirmed",true);
  return new JSONObject().put("task_id","task").put("turn_id","turn").put("request_id","request").put("dispatch_attempt",4).put("client_status","CANCELLED").put("server_status","CANCELLED").put("server_control_state","RUNNING").put("server_control_epoch",5).put("server_cancel_proof",proof);
 }
 private SelfRunCancelledDispatchPolicy.Decision decision(JSONObject body){return SelfRunCancelledDispatchPolicy.evaluate(body,"task","turn","request",6);}
 @Test public void correlatedUnsentProofAllowsHigherAttemptAfterSameRequestControlRefresh()throws Exception {assertEquals(SelfRunCancelledDispatchPolicy.Decision.PROVEN_UNSENT,decision(body()));}
 @Test public void staleBrowserAttachedCancellationNeverPollsOrAuthorizesRetry()throws Exception {JSONObject b=body().put("server_status","BROWSER_ATTACHED");assertEquals(SelfRunCancelledDispatchPolicy.Decision.UNPROVEN,decision(b));}
 @Test public void missingCleanupOrNoSendProofCannotRetry()throws Exception {for(String k:new String[]{"never_submitted","cleanup_confirmed"}){JSONObject b=body();b.getJSONObject("server_cancel_proof").put(k,false);assertEquals(SelfRunCancelledDispatchPolicy.Decision.UNPROVEN,decision(b));}}
 @Test public void mismatchedIdentityAttemptOrFutureControlCannotRetry()throws Exception {
  for(String k:new String[]{"task_id","turn_id","request_id"}){JSONObject b=body();b.getJSONObject("server_cancel_proof").put(k,"other");assertEquals(SelfRunCancelledDispatchPolicy.Decision.UNPROVEN,decision(b));}
  JSONObject b=body();b.getJSONObject("server_cancel_proof").put("dispatch_attempt",3);assertEquals(SelfRunCancelledDispatchPolicy.Decision.UNPROVEN,decision(b));
  b=body();b.getJSONObject("server_cancel_proof").put("control_epoch",7);assertEquals(SelfRunCancelledDispatchPolicy.Decision.UNPROVEN,decision(b));
 }
 @Test public void conversationOrSupersededOrUnprovenCancellationCannotRetry()throws Exception {
  assertEquals(SelfRunCancelledDispatchPolicy.Decision.UNPROVEN,decision(body().put("conversation_url","https://chatgpt.com/c/existing")));
  assertEquals(SelfRunCancelledDispatchPolicy.Decision.UNPROVEN,decision(body().put("server_status","SUPERSEDED")));
  JSONObject b=body();b.remove("server_cancel_proof");assertEquals(SelfRunCancelledDispatchPolicy.Decision.UNPROVEN,decision(b));
 }
 @Test public void ordinaryRunningDispatchIsUnchanged()throws Exception {assertEquals(SelfRunCancelledDispatchPolicy.Decision.NONE,decision(body().put("client_status","SEND_REQUESTED").put("server_status","STARTED")));}
}
