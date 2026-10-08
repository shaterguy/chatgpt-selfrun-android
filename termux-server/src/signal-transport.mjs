import { keyFor } from './lifecycle.mjs';

const sameIdentity=(a,b)=>!!a&&!!b&&['task_id','turn_id','request_id'].every(k=>!!a[k]&&a[k]===b[k]);
const epoch=value=>Number.isSafeInteger(value)&&value>=0;
const noStoppedCache=Symbol('no stopped HTTP cache');

function stoppedCacheFiles(state,requested) {
  const data=state.lifecycle,http=state.httpIngress,removed=new Map();
  if(!data||!http)return removed;
  const entries=Object.entries(http.files||{});
  const controls=requested?[requested]:Object.values(data.controls||{});
  for(const candidate of controls) {
    const control=data.controls?.[candidate.task_id],key=keyFor(candidate),marker=data.retired?.[key];
    if(candidate.state!=='STOPPED'||!epoch(candidate.control_epoch)||candidate.control_epoch<1
      ||control?.state!=='STOPPED'||!sameIdentity(control,candidate)||control.control_epoch!==candidate.control_epoch
      ||marker?.state!=='STOPPED'||marker.retired!==true||marker.key!==key||!sameIdentity(marker,control)
      ||marker.control_epoch!==control.control_epoch||data.requests?.[key])continue;
    const cutoff=Math.max(...[marker.updated_at,control.updated_at_ms].filter(Number.isFinite));
    // HTTP acceptance precedes lifecycle application. A queued resume may still
    // need the previous dispatch, even when its payload has not changed yet.
    if(entries.some(([,body])=>body?.schema==='selfrun-task-control-v1'&&sameIdentity(body,control)
      &&(!epoch(body.control_epoch)||body.control_epoch>control.control_epoch
        ||(body.control_epoch===control.control_epoch&&body.state!=='STOPPED'))))continue;
    for(const [path,body] of entries) {
      if(!sameIdentity(body,control))continue;
      if(body.schema==='selfrun-task-control-v1') {
        if(epoch(body.control_epoch)&&body.control_epoch<=control.control_epoch)removed.set(path,body);
      } else if(body.schema==='selfrun-server-dispatch-v1'&&path===marker.path) {
        if(!Number.isSafeInteger(body.dispatch_attempt)||body.dispatch_attempt<1
          ||!Number.isSafeInteger(marker.dispatch_attempt)||body.dispatch_attempt>marker.dispatch_attempt)continue;
        if([body.server_control_epoch,body.lifecycle_record?.control_epoch].some(value=>value!==undefined
          &&(!epoch(value)||value>control.control_epoch)))continue;
        if([body.lifecycle_state,body.lifecycle_record?.state].some(value=>['COMMITTED','COMPLETED'].includes(value)))continue;
        if(['created_at_ms','send_requested_at_ms','cancelled_at_ms'].some(field=>body[field]!==undefined
          &&(!Number.isFinite(cutoff)||!Number.isFinite(body[field])||body[field]>cutoff)))continue;
        removed.set(path,body);
      }
    }
  }
  return removed;
}

// Legacy HTTP ingress uses the same durable lifecycle and Result authority as Drive.
export class SignalDispatchTransport {
  constructor(base,stateStore){this.base=base;this.stateStore=stateStore;}
  local(){return this.stateStore.snapshot().httpIngress||{files:{},signals:{}};}
  async register(signalId,path,body,controlPath,control) {
    await this.stateStore.update(state=>{
      const http=structuredClone(state.httpIngress||{files:{},signals:{}});
      if(http.signals[signalId])return state;
      http.files[path]=body;http.files[controlPath]=control;
      http.signals[signalId]={path,task_id:body.task_id,request_id:body.request_id};
      return {...state,httpIngress:http};
    });
  }
  async setControl(signalId,path,control) {
    await this.stateStore.update(state=>{
      const http=structuredClone(state.httpIngress||{files:{},signals:{}});
      if(http.signals[signalId])return state;
      http.files[path]=control;http.signals[signalId]={path,task_id:control.task_id,request_id:control.request_id};
      return {...state,httpIngress:http};
    });
  }
  async purgeStopped(control=null) {
    // Avoid durable writes on ordinary polling; queued changes are revalidated
    // inside the StateStore transaction before touching any payload or signal.
    const requested=control?structuredClone(control):null;
    if(!stoppedCacheFiles(this.stateStore.snapshot(),requested).size)return false;
    try {
      await this.stateStore.update(state=>{
        const removed=stoppedCacheFiles(state,requested);
        if(!removed.size)throw noStoppedCache;
        const http=structuredClone(state.httpIngress);
        for(const path of removed.keys())delete http.files[path];
        for(const [id,signal] of Object.entries(http.signals||{})) {
          const body=signal&&removed.get(signal.path);
          if(body&&signal.task_id===body.task_id&&signal.request_id===body.request_id
            &&(signal.turn_id===undefined||signal.turn_id===body.turn_id))http.signals[id]=true;
        }
        return {...state,httpIngress:http};
      });
      return true;
    } catch(error) {
      if(error===noStoppedCache)return false;
      throw error;
    }
  }
  async list() {
    const remote=await this.base.list();
    return [...remote,...Object.entries(this.local().files).map(([path,body])=>({
      path,modTime:String(body.updated_at_ms||body.control_epoch||0),size:JSON.stringify(body).length}))];
  }
  async read(path) {
    const body=this.local().files[path];
    return body?structuredClone(body):this.base.read(path);
  }
  async write(path,body,options={}) {
    if(!this.local().files[path])return this.base.write(path,body,options);
    await this.stateStore.update(state=>{
      const http=structuredClone(state.httpIngress);
      if(options.expected&&JSON.stringify(http.files[path])!==JSON.stringify(options.expected))throw new Error('HTTP projection revision conflict');
      http.files[path]=structuredClone(body);
      return {...state,httpIngress:http};
    });
  }
  readGoogleDocText(id){return this.base.readGoogleDocText(id);}
  close(){return this.base.close();}
}
