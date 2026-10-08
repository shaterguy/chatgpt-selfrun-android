package com.shaterguy.chatgptselfrun;
import org.json.JSONObject;
/** Only correlated server evidence can release a cancelled send claim. */
final class SelfRunCancelledDispatchPolicy {
 enum Decision { NONE, PROVEN_UNSENT, UNPROVEN }
 static Decision evaluate(JSONObject body,String task,String turn,String request,long epoch) {
  String client=body.optString("client_status"),server=body.optString("server_status");
  if(!"CANCELLED".equals(client)&&!"SUPERSEDED".equals(client)&&!"CANCELLED".equals(server)&&!"SUPERSEDED".equals(server))return Decision.NONE;
  JSONObject proof=body.optJSONObject("server_cancel_proof");
  if(!"CANCELLED".equals(server)||proof==null||proof.optInt("schema")!=1
      ||!Boolean.TRUE.equals(proof.opt("never_submitted"))||!Boolean.TRUE.equals(proof.opt("cleanup_confirmed"))
      ||!task.equals(body.optString("task_id"))||!turn.equals(body.optString("turn_id"))||!request.equals(body.optString("request_id"))
      ||!task.equals(proof.optString("task_id"))||!turn.equals(proof.optString("turn_id"))||!request.equals(proof.optString("request_id"))
      ||body.optLong("dispatch_attempt",0)<1||body.optLong("dispatch_attempt")!=proof.optLong("dispatch_attempt",-1)
      ||epoch<1||(proof.optLong("control_epoch",-1)<1||proof.optLong("control_epoch",-1)>epoch)||body.optLong("server_control_epoch",-1)!=proof.optLong("control_epoch",-2)
      ||!"RUNNING".equals(body.optString("server_control_state"))||!body.optString("conversation_url").isEmpty())return Decision.UNPROVEN;
  return Decision.PROVEN_UNSENT;
 }
}
