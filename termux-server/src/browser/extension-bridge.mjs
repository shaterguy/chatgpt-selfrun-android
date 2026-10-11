import http from 'node:http';
import {EventEmitter} from 'node:events';
import {randomUUID,timingSafeEqual} from 'node:crypto';
import {EXTENSION_ID} from './extension-package.mjs';
const LIMIT=2*1024*1024;
export const EXTENSION_OPERATIONS=new Set(['registerTarget','listTargets','idleStatus','controllerStop','discardTarget','snapshot','progress','recoveryInput']);
const error=(message,code,released=false)=>Object.assign(new Error(message),{code,released});
const aborted=(signal,released=false)=>Object.assign(error('Extension command aborted',
  signal?.reason?.code==='OBSERVATION_SUPERSEDED'?'OBSERVATION_SUPERSEDED':signal?.reason?.name==='TimeoutError'?'EXTENSION_TIMEOUT':'ABORT_ERR',released),{name:'AbortError',cause:signal?.reason});
const equal=(a,b)=>{const x=Buffer.from(String(a||'')),y=Buffer.from(String(b||''));return x.length===y.length&&timingSafeEqual(x,y);};
export class ExtensionBridge extends EventEmitter {
  constructor({port,ownerId,capability,browserSessionId,extensionId=EXTENSION_ID,activeOwnedTargets=null}) {
    super();if(!ownerId||!browserSessionId||typeof capability!=='string'||capability.length<16)throw Error('Extension pairing configuration required');
    this.activeOwnedTargets=activeOwnedTargets;
    this.port=port;this.ownerId=ownerId;this.capability=capability;this.browserSessionId=browserSessionId;this.extensionId=extensionId;
    this.server=null;this.connection=null;this.pending=new Map();this.commandCount=0;this.waiters=new Set();this.rejections=0;this.requests=0;this.lastRejection=null;
  }
  get address(){return this.server?.address();}
  get url(){return `http://127.0.0.1:${this.address?.port}`;}
  get pendingCount(){return this.pending.size;}
  status(){return{adapter:'extension',connected:!!this.connection?.ready,extensionVersion:this.connection?.ready?this.connection.extensionVersion??null:null,controllerBuild:this.connection?.ready?this.connection.controllerBuild??null:null,pending:this.pending.size,commandCount:this.commandCount,requests:this.requests,rejections:this.rejections,lastRejection:this.lastRejection,browserSessionId:this.browserSessionId};}
  async start(){if(this.server)return;this.server=http.createServer((req,res)=>void this.#handle(req,res).catch(()=>{if(!res.headersSent)this.#json(res,400,{error:'invalid_request'});else res.destroy();}));this.server.requestTimeout=15000;this.server.headersTimeout=10000;this.server.keepAliveTimeout=5000;
    await new Promise((resolve,reject)=>{this.server.once('error',reject);this.server.listen(this.port,'127.0.0.1',resolve);});
  }
  #authorized(req){return ['127.0.0.1','::ffff:127.0.0.1'].includes(req.socket.remoteAddress)&&req.headers.host===`127.0.0.1:${this.address.port}`&&req.headers.origin===`chrome-extension://${this.extensionId}`&&equal(req.headers.authorization,`Bearer ${this.capability}`)&&equal(req.headers['x-selfrun-owner'],this.ownerId);}
  #json(res,status,value){res.writeHead(status,{'Content-Type':'application/json','Cache-Control':'no-store'});res.end(JSON.stringify(value));}
  #drop(connection,reason){if(!connection||this.connection!==connection)return;this.connection=null;connection.res.end();for(const p of [...this.pending.values()])p.finish(error(reason,'EXTENSION_DISCONNECTED',p.operation==='submit'));this.emit('disconnect');}
  async #body(req){let size=0;const pieces=[];for await(const part of req){size+=part.length;if(size>LIMIT)throw error('Payload too large','TOO_LARGE');pieces.push(part);}return JSON.parse(Buffer.concat(pieces).toString('utf8'));}
  #ownership(){
    const ids=this.activeOwnedTargets?.();if(!Array.isArray(ids))return {};
    const prefix='ext:'+this.browserSessionId+':';
    return {ownership:{schema:1,targets:[...new Set(ids)].filter(id=>typeof id==='string'&&id.startsWith(prefix)),
      creatingCommands:[...this.pending].filter(([,p])=>p.operation==='createTarget').map(([id])=>id)}};
  }
  async #handle(req,res){
    if(req.method==='GET'&&/^\/bootstrap\/[0-9a-f-]{36}$/.test(req.url||'')&&['127.0.0.1','::ffff:127.0.0.1'].includes(req.socket.remoteAddress)&&req.headers.host===`127.0.0.1:${this.address.port}`){res.writeHead(200,{'Content-Type':'text/html','Cache-Control':'no-store','Content-Security-Policy':"default-src 'none'; frame-ancestors 'none'"});res.end('<!doctype html><title>SelfRun initial tab</title>');return;}
    this.requests+=1;if(!this.#authorized(req)){this.rejections+=1;this.lastRejection={originMatch:req.headers.origin===`chrome-extension://${this.extensionId}`,originPresent:typeof req.headers.origin==='string',hostMatch:req.headers.host===`127.0.0.1:${this.address.port}`,ownerMatch:equal(req.headers['x-selfrun-owner'],this.ownerId),capabilityMatch:equal(req.headers.authorization,`Bearer ${this.capability}`)};return this.#json(res,403,{error:'forbidden'});}
    if(req.method==='POST'&&['/session','/events'].includes(req.url)){
      let body;try{body=await this.#body(req);}catch{return this.#json(res,400,{error:'invalid_body'});}
      // Reject stale/legacy controllers before they can displace current work.
      if(body?.browserSessionId!==this.browserSessionId)return this.#json(res,409,{error:'stale_session'});
      if(body?.controllerBuild!=='selfrun-monitor-only-v1')return this.#json(res,409,{error:'stale_controller_build'});
      if(req.url==='/session')return this.#json(res,200,{ok:true,...this.#ownership()});
      if(this.connection)this.#drop(this.connection,'Extension connection replaced');
      const c={id:randomUUID(),res,ready:false};this.connection=c;
      res.writeHead(200,{'Content-Type':'application/x-ndjson','Cache-Control':'no-store','X-Content-Type-Options':'nosniff'});res.flushHeaders();
      res.write(JSON.stringify({kind:'welcome',connectionId:c.id,...this.#ownership()})+'\n');res.on('close',()=>this.#drop(c,'Extension connection closed'));return;
    }
    if(req.method!=='POST'||!['/event','/result'].includes(req.url))return this.#json(res,404,{error:'not_found'});
    let body;try{body=await this.#body(req);}catch(e){return this.#json(res,e.code==='TOO_LARGE'?413:400,{error:'invalid_body'});}
    const c=this.connection;if(!c||body.connectionId!==c.id||body.browserSessionId!==this.browserSessionId)return this.#json(res,409,{error:'stale_connection'});
    if(req.url==='/result'){
      const p=this.pending.get(body.id);if(!p||p.connectionId!==c.id)return this.#json(res,409,{error:'unknown_command'});
      if(body.ok===true)p.finish(null,body.result);else p.finish(error(String(body.error?.message||'Extension command failed').slice(0,250),String(body.error?.code||'EXTENSION_COMMAND_FAILED'),body.error?.released===true));
    }else if(body.event==='hello'){
      if(body.extensionId!==this.extensionId)return this.#json(res,403,{error:'identity_mismatch'});c.extensionVersion=typeof body.extensionVersion==='string'&&/^\d+\.\d+\.\d+$/.test(body.extensionVersion)?body.extensionVersion:null;if(body.controllerBuild!=='selfrun-monitor-only-v1')return this.#json(res,409,{error:'stale_controller_build'});c.controllerBuild=body.controllerBuild;c.ready=true;for(const done of [...this.waiters])done();this.emit('ready');
    }else if(c.ready&&['targetReady','targetDirty','targetRemoved'].includes(body.event))this.emit(body.event,body);
    else return this.#json(res,400,{error:'unsupported_event'});
    this.#json(res,200,{ok:true});
  }
  async waitReady(timeoutMs=15000,{signal,guard=()=>true}={}){
    const permitted=()=>{try{return !signal?.aborted&&guard()===true;}catch{return false;}};
    const cancelled=()=>Object.assign(error('Extension readiness cancelled','ABORT_ERR'),{name:'AbortError'});
    if(!permitted())throw cancelled();if(this.connection?.ready)return;
    return new Promise((resolve,reject)=>{
      const done=reason=>{clearTimeout(timer);clearInterval(check);signal?.removeEventListener('abort',abort);this.waiters.delete(done);reason?reject(reason):permitted()?resolve():reject(cancelled());};
      const abort=()=>done(cancelled());
      const timer=setTimeout(()=>done(error('Extension controller unavailable','EXTENSION_UNAVAILABLE')),timeoutMs);
      const check=setInterval(()=>{if(!permitted())abort();},50);
      signal?.addEventListener('abort',abort,{once:true});this.waiters.add(done);
      if(!permitted())abort();else if(this.connection?.ready)done();
    });
  }
  async request(operation,payload={},options={}){
    if(!EXTENSION_OPERATIONS.has(operation))throw error('Unsupported named extension operation','EXTENSION_UNSUPPORTED');
    const {timeoutMs=20000,signal}=options;if(signal?.aborted)throw aborted(signal);
    const c=this.connection;if(!c?.ready)throw error('Extension controller disconnected','EXTENSION_UNAVAILABLE');
    const id=randomUUID(),message={kind:'command',id,connectionId:c.id,operation,payload,deadlineAt:Date.now()+timeoutMs};const frame=JSON.stringify(message)+'\n';if(Buffer.byteLength(frame)>LIMIT)throw error('Extension command too large','EXTENSION_PAYLOAD_TOO_LARGE');
    return new Promise((resolve,reject)=>{
      const cancel=()=>{if(this.connection===c&&!c.res.destroyed)c.res.write(JSON.stringify({kind:'cancel',id,connectionId:c.id})+'\n');};
      const abort=()=>{cancel();finish(aborted(signal,operation==='submit'));};
      const timer=setTimeout(()=>{cancel();finish(error('Extension command timeout','EXTENSION_TIMEOUT',operation==='submit'));},timeoutMs);
      const finish=(e,value)=>{if(!this.pending.has(id))return;this.pending.delete(id);clearTimeout(timer);signal?.removeEventListener('abort',abort);e?reject(e):resolve(value);};
      this.pending.set(id,{finish,operation,connectionId:c.id});signal?.addEventListener('abort',abort,{once:true});
      this.commandCount+=1;c.res.write(frame);
    });
  }
  async close(){for(const done of [...this.waiters])done(error('Extension bridge stopped','EXTENSION_DISCONNECTED'));if(this.connection)this.#drop(this.connection,'Extension bridge stopped');for(const p of [...this.pending.values()])p.finish(error('Extension bridge stopped','EXTENSION_DISCONNECTED',p.operation==='submit'));if(this.server){const server=this.server;this.server=null;server.closeAllConnections();await new Promise(resolve=>server.close(resolve));}}
}
