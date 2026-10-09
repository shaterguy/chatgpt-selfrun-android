// An explicit user no-work assertion is maintenance authority, never a task Result.
export function controlQuarantined(data,control) {
  const fence=data.controlQuarantine?.[control?.task_id];
  return !!fence&&fence.schema===1&&fence.reason==='USER_CONFIRMED_NO_WORK'
    &&Number.isSafeInteger(fence.control?.control_epoch)&&Number.isSafeInteger(control?.control_epoch)
    &&control.control_epoch<=fence.control.control_epoch;
}
export function quarantineIdleSnapshot(snapshot,{userConfirmedNoWork=false,cutoffMs,expectedGeneration,expectedControlEpochs}={}) {
  const refuse=()=>{throw new Error('maintenance preflight changed or pending work; no state modified');};
  if(userConfirmedNoWork!==true||!Number.isSafeInteger(expectedGeneration)||expectedGeneration<0||!Number.isSafeInteger(cutoffMs)||cutoffMs<=0||snapshot.generation!==expectedGeneration
    ||snapshot.status!=='IDLE'||snapshot.activeCount!==0||!Array.isArray(snapshot.activeDispatches)||snapshot.activeDispatches.length)refuse();
  const life=snapshot.lifecycle;
  if(!life||!expectedControlEpochs||['requests','outbox','completionOutbox','controls'].some(k=>!life[k]||Array.isArray(life[k])||typeof life[k]!=='object')
    ||['requests','outbox','completionOutbox'].some(k=>Object.keys(life[k]).length))refuse();
  if(JSON.stringify(Object.keys(life.controls).sort())!==JSON.stringify(Object.keys(expectedControlEpochs).sort()))refuse();
  for(const [task,c] of Object.entries(life.controls))if(c.task_id!==task||!Number.isSafeInteger(c.control_epoch)||c.control_epoch<1
    ||c.control_epoch!==expectedControlEpochs[task]
    ||[c.updated_at_ms,c.updated_at].some(v=>v!==undefined&&v!==null&&(!Number.isSafeInteger(v)||v<0||v>cutoffMs)))refuse();
  if(snapshot.httpIngress&&(!snapshot.httpIngress.files||Object.keys(snapshot.httpIngress.files).length))refuse();
  if(Object.values(life.retired||{}).some(m=>m.browser_open_pending))refuse();
  const map=life.controlQuarantine;
  if(map!==undefined&&(map===null||typeof map!=='object'||Array.isArray(map)
    ||Object.entries(map).some(([task,f])=>!f||f.schema!==1||f.reason!=='USER_CONFIRMED_NO_WORK'
      ||!Number.isSafeInteger(f.cutoff_ms)||f.cutoff_ms<=0||f.control?.task_id!==task
      ||!Number.isSafeInteger(f.control?.control_epoch)||f.control.control_epoch<1)))refuse();
  const next=structuredClone(snapshot);next.lifecycle.controlQuarantine??={};
  for(const [task,c] of Object.entries(life.controls))if(c.state==='RUNNING')next.lifecycle.controlQuarantine[task]={schema:1,
    reason:'USER_CONFIRMED_NO_WORK',cutoff_ms:cutoffMs,control:structuredClone(c)};
  next.maintenanceQuarantine={schema:1,reason:'USER_CONFIRMED_NO_WORK',cutoff_ms:cutoffMs,
    previous:structuredClone(snapshot.maintenanceQuarantine||null),projection:Object.fromEntries(
      ['status','activeCount','activeSignal','activeTargetId','activeDispatches','conversationUrl','lastActivityAt','lastSignalId','lastError','acceptedAt'].map(k=>[k,structuredClone(snapshot[k]??null)]))};
  Object.assign(next,{status:'IDLE',activeCount:0,activeSignal:null,activeTargetId:null,activeDispatches:[],conversationUrl:null,
    lastActivityAt:null,lastSignalId:null,lastError:null,acceptedAt:null});
  return next;
}
// Older servers do not understand controlQuarantine. Keep its evidence intact,
// but expose an administrative pause they already enforce before changing source.
export function rollbackIdleSnapshot(snapshot,options={}) {
  quarantineIdleSnapshot(snapshot,options); // Reuse exact idle/generation/epoch/ingress guards.
  // The existing fence, rather than a newly synthesized one, proves rollback authority.
  for(const c of Object.values(snapshot.lifecycle.controls))if(c.state==='RUNNING'
    &&!controlQuarantined(snapshot.lifecycle,c))throw new Error('rollback running control lacks prior maintenance fence; no state modified');
  const unresolved=snapshot.lifecycle.browserCleanupUnresolved;
  if(unresolved!==undefined&&(unresolved===null||typeof unresolved!=='object'||Array.isArray(unresolved)))
    throw new Error('rollback cleanup archive malformed; no state modified');
  for(const archive of Object.values(unresolved||{})){
    const marker=snapshot.lifecycle.retired?.[archive?.entry?.key];
    if(archive?.schema!==1||archive.status!=='UNRESOLVED'||typeof archive.entry?.key!=='string'
      ||marker?.state==='CANCELLED'&&!['PENDING','UNCONFIRMED'].includes(marker.browser_cleanup?.status))
      throw new Error('rollback unresolved cleanup lacks legacy protection; no state modified');
  }
  const next=structuredClone(snapshot),map=next.lifecycle.controlQuarantine||{};
  for(const [task,fence]of Object.entries(map)) {
    const c=next.lifecycle.controls[task],old=fence.control;
    if(!c||c.control_epoch!==old.control_epoch||c.task_id!==old.task_id
      ||c.turn_id!==old.turn_id||c.request_id!==old.request_id
      ||!['RUNNING','PAUSED'].includes(c.state))throw new Error('rollback control changed; no state modified');
    next.lifecycle.controls[task]={...c,state:'PAUSED',maintenance_pause:{schema:1,
      reason:'USER_CONFIRMED_NO_WORK',cutoff_ms:options.cutoffMs}};
  }
  return next;
}
