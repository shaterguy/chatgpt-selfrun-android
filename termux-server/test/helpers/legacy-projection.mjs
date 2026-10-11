// Historical Drive payload fixture only; production now publishes conversation_url alone.
import { canonicalUrl } from '../../src/lifecycle.mjs';
export function projection(r) {
  const statuses={PREPARED:'READY_TO_SUBMIT',COMMITTED:'COMPLETED',COMPLETED:'COMPLETED',CANCELLED:'CANCELLED',
    STOPPED:'CANCELLED',PAUSED:'CANCELLED',BLOCKED:'ERROR'};
  // STARTED releases the owned URL to the app; post_outcome retains receipt truth.
  const status=statuses[r.state]||(canonicalUrl(r.conversation_url)?'STARTED':'PENDING');
  const {body,...durable}=r;
  return {...r.body,lifecycle_record:{schema:1,...durable},server_status:status,lifecycle_state:r.state,
    lifecycle_revision:r.revision,server_generation:r.server_generation||0,request_generation:r.run_generation,browser_generation:r.browser_generation,
    conversation_url:r.conversation_url,recovery_count:r.recovery_count,recovery_attempt_id:r.intent?.kind==='recovery'?r.intent.id:null,
    post_correlation:r.intent?.id||null,post_outcome:r.intent?.outcome||null,server_error:r.error?.message||'',
    server_error_class:r.error?.code||null,server_control_state:r.control_state,server_control_epoch:r.control_epoch,
    resume_retry_count:r.attach_attempts,resume_retry_at_ms:r.retry_at||0,result_state:r.result_state,
    response_cursor:r.cursor,response_status:r.response_status||'UNKNOWN',stop_status:r.stop_status||null,updated_at_ms:r.updated_at};
}
