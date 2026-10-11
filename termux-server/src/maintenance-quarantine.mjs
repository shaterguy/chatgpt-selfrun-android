import {isDeepStrictEqual} from 'node:util';

// Legacy maintenance metadata is removed during initialization, never used as
// permission to reject a future app command. Old helper names do not archive it.
export function controlQuarantined() {return false;}
export function quarantineIdleSnapshot(snapshot,options={}) {return removeTerminalHistorySnapshot(snapshot,options);}
export function rollbackIdleSnapshot() {throw new Error('terminal removal requires reviewed forward recovery; no state modified');}

// Explicit no-work maintenance removes history; current commands retain their own authority.
const identityKey=b=>JSON.stringify([b?.task_id,b?.turn_id,b?.request_id]);
const terminalState=state=>['STOPPED','CANCELLED','COMMITTED','COMPLETED'].includes(state);
// A cancelled attempt inside the same live app execution is duplicate-input
// evidence, not ended-task history. Explicit STOP/DONE releases it completely.
export const currentCancellationProof=(data,r)=>r?.state==='CANCELLED'
  &&['RUNNING','PAUSED','WAITING_USER_INTERVENTION','RESUME_REQUESTED','RESUME_STOPPED_REQUESTED'].includes(data.controls?.[r.task_id]?.state)
  &&identityKey(data.controls[r.task_id])===identityKey(r)
  &&Number(data.controls[r.task_id].control_epoch)>=Number(r.control_epoch||0);
export function removeTerminalHistorySnapshot(snapshot,{userConfirmedNoWork=false,cutoffMs,expectedGeneration,expectedControlEpochs}={}) {
  const refuse=()=>{throw new Error('terminal cleanup authority changed or current work exists; no state modified');};
  const life=snapshot.lifecycle;
  if(userConfirmedNoWork!==true||!Number.isSafeInteger(cutoffMs)||cutoffMs<=0||snapshot.generation!==expectedGeneration
    ||!Number.isSafeInteger(expectedGeneration)||snapshot.status!=='IDLE'||snapshot.activeCount!==0||!Array.isArray(snapshot.activeDispatches)||snapshot.activeDispatches.length
    ||!life||!expectedControlEpochs)refuse();
  for(const k of ['requests','controls','retired','outbox','completionOutbox','browserCleanup'])if(!life[k]||Array.isArray(life[k])||typeof life[k]!=='object')refuse();
  if(JSON.stringify(Object.keys(life.controls).sort())!==JSON.stringify(Object.keys(expectedControlEpochs).sort()))refuse();
  const quiet=v=>[v?.updated_at,v?.updated_at_ms,v?.created_at_ms,v?.send_requested_at_ms,v?.cancelled_at_ms].every(t=>t===undefined||t===null||Number.isSafeInteger(t)&&t>=0&&t<=cutoffMs);
  for(const [task,c]of Object.entries(life.controls))if(c.task_id!==task||!Number.isSafeInteger(c.control_epoch)||c.control_epoch<1||c.control_epoch!==expectedControlEpochs[task]||!quiet(c))refuse();
  if(snapshot.httpIngress&&(!snapshot.httpIngress.files||Object.keys(snapshot.httpIngress.files).length))refuse();
  if(Object.values(life.retired).some(m=>m.browser_open_pending)||Object.values(life.requests).some(r=>!terminalState(r.state)||r.browser_open_pending))refuse();
  const removed=new Map(),ids=new Set();
  for(const [key,r]of [...Object.entries(life.retired),...Object.entries(life.requests)]) {
    if(!terminalState(r.state)||!r.task_id||!r.turn_id||!r.request_id||!quiet(r))refuse();
    const c=life.controls[r.task_id],epoch=Number(r.control_epoch||0),attempt=Number(r.dispatch_attempt||1);
    if(!Number.isSafeInteger(epoch)||epoch<0||!Number.isSafeInteger(attempt)||attempt<1)refuse();
    if(c&&identityKey(c)===identityKey(r)&&c.state==='RUNNING'&&c.control_epoch>epoch)refuse();
    removed.set(key,r);removed.set(identityKey(r),r);ids.add(r.request_id);
  }
  const owner=(key,item)=>removed.get(key)||removed.get(item?.key)||removed.get(identityKey(item));
  for(const kind of ['outbox','completionOutbox'])for(const [key,item]of Object.entries(life[kind])) {
    const body=item.body||item,m=owner(key,body);
    if(!m||!quiet(item)||!quiet(body)||Number(body.control_epoch||body.server_control_epoch||m.control_epoch||0)>Number(m.control_epoch||0)
      ||Number(body.dispatch_attempt||m.dispatch_attempt||1)>Number(m.dispatch_attempt||1))refuse();
    for(const field of ['task_id','turn_id','request_id'])if(body[field]!==undefined&&body[field]!==m[field])refuse();
    if(kind==='completionOutbox'&&item.completion_reason==='CLIENT_CANCELLED'&&m.state!=='CANCELLED')refuse();
  }
  for(const entry of Object.values(life.browserCleanup))if(!owner(entry.key,entry))refuse();
  for(const archive of Object.values(life.browserCleanupUnresolved||{}))if(!owner(archive?.entry?.key,archive?.entry))refuse();
  const next=structuredClone(snapshot),data=next.lifecycle;
  // Exact idle/generation/control guards above protect newly arrived work.
  // The user confirmed that these remaining legacy controls represent no work.
  data.requests={};data.retired={};data.controls={};data.controlQuarantine={};
  delete data.terminalReplayFences;
  data.outbox={};data.completionOutbox={};data.browserCleanup={};data.browserCleanupUnresolved={};
  data.browserCleanupOutcomes={};
  data.events=[];
  if(next.httpIngress)next.httpIngress={files:{},signals:{}};
  delete next.maintenanceQuarantine;
  Object.assign(next,{activeSignal:null,activeTargetId:null,conversationUrl:null,lastActivityAt:null,lastSignalId:null,lastError:null,acceptedAt:null});
  return next;
}
export function filterTerminalHistoryEvents(text,snapshot,options=null) {
  if(options)removeTerminalHistorySnapshot(snapshot,options);
  const records=[...Object.values(snapshot.lifecycle.retired||{}),...Object.values(snapshot.lifecycle.requests||{})].filter(r=>terminalState(r.state));
  const keys=new Set(records.map(identityKey)),requests=new Set(records.map(r=>r.request_id));
  return text.split('\n').filter(line=>{
    if(!line)return false;
    let row;try{row=JSON.parse(line);}catch{throw new Error('operational event journal malformed; no journal modified');}
    const detail=row.details||row;
    if(options?.userConfirmedNoWork&&(detail.task_id||detail.turn_id||detail.request_id||row.signalId||row.conversationUrl))return false;
    return !keys.has(identityKey(detail))&&!requests.has(row.signalId);
  }).map(line=>line+'\n').join('');
}

// Current nonterminal work is retained; terminal payloads are never restart input.
export function discardTerminalHistorySnapshot(snapshot,extra=[],onlyExtra=false) {
  const next=structuredClone(snapshot),data=next.lifecycle;
  if(!data)return next;
  const activeTasks=new Set(Object.values(data.requests||{}).filter(r=>!terminalState(r.state)).map(r=>r.task_id));
  const records=onlyExtra?extra:[...Object.values(data.retired||{}).filter(r=>!currentCancellationProof(data,r)),...Object.values(data.requests||{}).filter(r=>terminalState(r.state)&&!currentCancellationProof(data,r)),...Object.values(data.controls||{}).filter(c=>!activeTasks.has(c.task_id)&&['STOPPED','DONE'].includes(c.state)).map(c=>({...c,state:'STOPPED'})),...extra];
  const keys=new Set(records.map(identityKey)),ids=new Set(records.map(r=>r.request_id)),paths=new Set(records.map(r=>r.path).filter(Boolean));
  const targets=new Set(records.flatMap(r=>[r.target_id,r.browser_cleanup?.target_id]).filter(Boolean));
  const ended=v=>v&&keys.has(identityKey(v));
  for(const kind of ['requests','retired','outbox','completionOutbox','browserCleanup','browserCleanupUnresolved','browserCleanupOutcomes'])
    for(const [key,v] of Object.entries(data[kind]||{}))if(targets.has(key)||ended(v)||ended(v?.body)||ended(v?.entry)||records.some(r=>r.key===key||r.key===v?.key||r.key===v?.entry?.key))delete data[kind][key];
  const ongoingTasks=new Set(Object.values(data.requests||{}).map(r=>r.task_id));
  // A shared control document alone is not current local work. Retain authority
  // only alongside a request; new dispatch admission supplies its freshly read control.
  for(const task of Object.keys(data.controls||{}))if(!ongoingTasks.has(task)&&!Object.values(data.retired||{}).some(r=>r.task_id===task&&currentCancellationProof(data,r)))delete data.controls[task];
  delete data.terminalReplayFences;delete data.controlQuarantine;delete next.maintenanceQuarantine;
  // Old snapshots can already lack the request while retaining its queued
  // audit event or cleanup receipt. Keep these two runtime collections only
  // for current execution authority; raw audit files are untouched.
  const current=[...Object.values(data.requests||{}),...Object.values(data.retired||{}).filter(r=>currentCancellationProof(data,r))];
  const currentKeys=new Set(current.map(r=>r.key).filter(Boolean));
  const currentIdentities=new Set(current.map(identityKey));
  const currentOwner=value=>value&&(currentKeys.has(value.key)||currentIdentities.has(identityKey(value)));
  data.events=(data.events||[]).filter(e=>!ended(e)&&currentOwner(e));
  for(const [target,value]of Object.entries(data.browserCleanupOutcomes||{}))if(!currentOwner(value)
    &&!current.some(r=>r.browser_cleanup?.target_id===target&&isDeepStrictEqual(r.browser_cleanup,value)))delete data.browserCleanupOutcomes[target];
  if(next.httpIngress){
    const files=next.httpIngress.files||{},removedPaths=new Set();
    const newer=(value,record)=>{
      const valueEpoch=value?.control_epoch??value?.server_control_epoch??value?.lifecycle_record?.control_epoch;
      return Number.isSafeInteger(valueEpoch)&&Number.isSafeInteger(record.control_epoch)&&valueEpoch>record.control_epoch
        ||Number.isSafeInteger(value?.dispatch_attempt)&&Number.isSafeInteger(record.dispatch_attempt)&&value.dispatch_attempt>record.dispatch_attempt;
    };
    for(const [key,value]of Object.entries(files)) {
      const matching=records.filter(record=>identityKey(value)===identityKey(record));
      if(!matching.length)continue;
      // HTTP registration is committed before lifecycle ingestion. A newer
      // current control or attempt must survive an older run's finalization.
      if(matching.some(record=>newer(value,record)||Object.values(files).some(other=>identityKey(other)===identityKey(record)&&newer(other,record))))continue;
      removedPaths.add(key);delete files[key];
    }
    for(const [key,value]of Object.entries(next.httpIngress.signals||{}))if(value===true||value&&(removedPaths.has(value.path)||!files[value.path]&&(ended(value)||ids.has(value.request_id)&&records.some(record=>record.task_id===value.task_id)||paths.has(value.path))))delete next.httpIngress.signals[key];
  }
  if(!Object.keys(data.requests||{}).length)Object.assign(next,{status:'IDLE',activeCount:0,activeDispatches:[],activeSignal:null,activeTargetId:null,conversationUrl:null,lastActivityAt:null,lastSignalId:null,lastError:null,acceptedAt:null});
  return next;
}
