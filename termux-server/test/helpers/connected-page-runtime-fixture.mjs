import vm from 'node:vm';
import {buildContentSource} from '../../src/browser/extension-page-source.mjs';

// Only browser DOM/CDP delivery and the remote site's response are simulated.
// Every action expression, profile/receipt engine and content observer is the
// current candidate's generated source, evaluated without success-result stubs.
export function connectedPageRuntime({id,tabs,calls,notify,composerKind='textarea',deferConversationUrl=false,conversationUrlOnSend=''}) {
 const scripts=new Map(),effects=[],requests=[],documents=[];let context,content,receiver,documentId,enabled=false,serial=0,appendResponse,observeSubmitted;
 const timers=new Set();
 const timer=(fn,ms)=>{const t=setTimeout(()=>{timers.delete(t);fn();},ms);t.unref();timers.add(t);return t;};
 const clear=t=>{clearTimeout(t);timers.delete(t);};
 const replaceDocument=url=>{
  tabs.get(id).url=url;documentId='vm-'+id+'-'+(++serial);
  let value='',capturedFetch,selected=null;const users=[],assistants=[],events={},contentEvents={};
  const location={get href(){return tabs.get(id).url;},set href(v){tabs.get(id).url=v;},get pathname(){return new URL(this.href).pathname;},get origin(){return new URL(this.href).origin;}};
  const message=(role,text,guid,index)=>({isConnected:true,offsetParent:{},innerText:text,textContent:text,parentElement:null,index,
   getAttribute:name=>name==='data-message-id'?guid:null,closest:()=>null,contains(other){return this===other;},compareDocumentPosition(other){return other.index>this.index?4:2;}});
  appendResponse=text=>assistants.push(message('assistant',text,'later-'+assistants.length,users.length*2+assistants.length));
  const composer={isConnected:true,offsetParent:{},focus(){},dispatchEvent(){return true;},closest:()=>null};
  if(composerKind==='textarea')Object.defineProperty(composer,'value',{get:()=>value,set:v=>{value=v;effects.push({kind:'input',method:'nativeSet',length:v.length});}});
  else Object.defineProperty(composer,'innerText',{get:()=>value});
  const hiddenTextarea={isConnected:true,offsetParent:null,value:''};
  const stop={isConnected:true,offsetParent:null,dataset:{testid:'stop-button'},getAttribute:()=>null};
  observeSubmitted=prompt=>{value='';users.push(message('user',prompt,crypto.randomUUID(),users.length*2));stop.offsetParent={};};
  const send={isConnected:true,offsetParent:{},dataset:{testid:'send-button'},disabled:false,getAttribute:()=>null,focus(){},click(){
   effects.push({kind:'click'});const prompt=value,guid=crypto.randomUUID(),conversation=location.pathname.split('/c/')[1];value='';
   const body={messages:[{id:guid,author:{role:'user'},content:{parts:[prompt]}}],model:'old',thinking_effort:'old',conversation_origin:'old',service_tier:'old',...(conversation?{conversation_id:conversation}:{})};
   void capturedFetch('https://chatgpt.com/backend-api/conversation',{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify(body)}).catch(error=>effects.push({kind:'fetch-error',message:error.message}));
  }};
  const document={readyState:'complete',title:'fixture',body:{innerText:''},documentElement:{},head:{appendChild(){}},getElementById:()=>null,createElement:()=>({}),
   querySelectorAll(selector){if(selector==='button')return[send,stop];if(selector.startsWith('textarea'))return composerKind==='textarea'?[composer]:[hiddenTextarea];if(selector.includes('prompt-textarea')||selector==='main form [contenteditable="true"]')return composerKind==='textarea'?[]:[composer];if(selector.includes('data-message-author-role="assistant"'))return assistants;if(selector.includes('data-message-author-role="user"'))return users;return[];},
   createRange(){return{selectNodeContents:node=>selected=node};},execCommand(command,_ui,text){if(selected!==composer)return false;if(command==='delete')value='';if(command==='insertText'){value=text;effects.push({kind:'input',method:'execCommand',length:text.length});}return true;},
   createTreeWalker(root){let pending=true;return{nextNode(){if(!pending)return false;pending=false;this.currentNode={parentElement:root,nodeValue:root.innerText};return true;}};}};
  const window={getSelection:()=>({removeAllRanges(){},addRange(){}}),addEventListener:(name,fn)=>events[name]=fn,history:{pushState(_a,_b,next){location.href=new URL(next,location.href).href;},replaceState(){}},
   fetch:async(input,init)=>{const request=input instanceof Request?input:new Request(input,init),body=JSON.parse(await request.text());requests.push(body);
    const conversation=location.pathname.split('/c/')[1]||(conversationUrlOnSend?new URL(conversationUrlOnSend).pathname.split('/c/')[1]:'owned-'+id);if(!deferConversationUrl)window.history.pushState({},'',location.pathname.includes('/c/')?location.pathname:conversationUrlOnSend||'/g/test/c/'+conversation);
    const user=body.messages[0],index=users.length*2;users.push(message('user',user.content.parts.join(''),user.id,index));assistants.push(message('assistant','fixture response '+users.length,'assistant-'+users.length,index+1));
    return new Response('data: '+JSON.stringify({conversation_id:conversation,type:'message_marker',event:'first',marker:'user_visible_token'})+'\n\n',{headers:{'content-type':'text/event-stream'}});
   }};
  context=vm.createContext({window,document,location,Date,URL,TextEncoder,TextDecoder,Uint8Array,crypto,Request,Response,Headers,ReadableStream,AbortController,Event,
   setTimeout:timer,clearTimeout:clear,performance,NodeFilter:{SHOW_TEXT:4},getComputedStyle:()=>({display:'block',visibility:'visible',opacity:'1'})});
  if(enabled)for(const source of scripts.values())vm.runInContext(source,context);
  capturedFetch=window.fetch;
  const chrome={runtime:{id:'test',getURL:path=>'chrome-extension://test/'+path,sendMessage:async message=>notify(message),onMessage:{addListener:fn=>receiver=fn}}};
  class MutationObserver {observe(){}disconnect(){effects.push({kind:'observer-disconnected'});}}
  content=vm.createContext({chrome,crypto,document,location,Date,URL,MutationObserver,NodeFilter:{SHOW_TEXT:4},getComputedStyle:()=>({display:'block',visibility:'visible',opacity:'1'}),addEventListener:(name,fn)=>contentEvents[name]=fn});
  vm.runInContext(buildContentSource(),content);documents.push({context,content,events,contentEvents});
 };
 replaceDocument(tabs.get(id).url);
 const transport={socket:{readyState:1},close(){this.socket.readyState=3;},async call(method,params){
  calls.push(['server',method,id]);
  if(method==='Page.enable'){enabled=true;return{};}
  if(method==='Page.addScriptToEvaluateOnNewDocument'){const identifier='script-'+scripts.size;scripts.set(identifier,params.source);return{identifier};}
  if(method==='Page.removeScriptToEvaluateOnNewDocument'){scripts.delete(params.identifier);return{};}
  if(method==='Page.navigate'){effects.push({kind:'navigate',url:params.url});replaceDocument(params.url);return{};}
  if(method==='Runtime.evaluate'){const name=params.expression.match(/helper\.run\(("[^"]+")/);if(name)effects.push({kind:'action',operation:JSON.parse(name[1])});
   try{return{result:{value:await vm.runInContext(params.expression,context)}};}catch(error){return{exceptionDetails:{exception:{description:error.message}}};}}
  throw Error('Unsupported fixture transport: '+method);
 }};
 return{transport,effects,requests,documents,get documentId(){return documentId;},get context(){return context;},
  appendResponse:text=>appendResponse(text),
  observeSubmitted:prompt=>observeSubmitted(prompt),
  replaceDocument:url=>{const doc=documents.at(-1);doc?.events.pagehide?.();doc?.contentEvents.pagehide?.();replaceDocument(url);},
  attest:challenge=>context.window.__selfrunExtensionPage?.attest(challenge),
  contentMessage(message){let result;receiver(message,{id:'test',url:'chrome-extension://test/controller.html'},value=>result=value);return Promise.resolve(result);},
  closeDocument(){const doc=documents.at(-1);doc?.events.pagehide?.();doc?.contentEvents.pagehide?.();},
  disposeTimers(){for(const t of timers)clear(t);},
 };
}
