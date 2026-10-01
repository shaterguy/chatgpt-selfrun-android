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
