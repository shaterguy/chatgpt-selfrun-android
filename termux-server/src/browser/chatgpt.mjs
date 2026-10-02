export function normalizeProgressText(value) {
  return String(value || '')
    .replace(/(?:Today|Yesterday|오늘|어제)\s+\d{1,2}:\d{2}(?::\d{2})?\s*(?:AM|PM|오전|오후)?/gi, ' ')
    .replace(/(?:Worked|Working|Thought|Thinking)\s+for\s+\d+(?:\.\d+)?\s*(?:seconds?|minutes?|hours?|s|m|h)(?:\s+\d+(?:\.\d+)?\s*(?:seconds?|minutes?|hours?|s|m|h))*/gi, ' ')
    .replace(/(?:You said:|ChatGPT said:)/gi, ' ')
    .replace(/\s+/g, ' ').trim();
}

const RUNTIME_EVALUATE_TIMEOUT_MS = 5000;
const SUBMIT_POST_GRACE_MS = 5000;

const PAGE_ERROR_PATTERN = [
  'too many requests',
  'could not load this chatgpt conversation',
  'something went wrong',
  'error generating',
  '문제가 발생',
  '오류가 발생',
].join('|');

export function detectPageErrorText(body) {
  const match = String(body || '').match(new RegExp(PAGE_ERROR_PATTERN, 'i'));
  return match ? match[0] : null;
}

export function conversationStateReady(probe) {
  const userMessageId = String(probe?.userMessageId || '').trim();
  const assistantMessageId = String(probe?.assistantMessageId || '').trim();
  const hasResponseState = !!probe?.streaming || !!probe?.paused || !!assistantMessageId
    || Number(probe?.responseTextLength || 0) > 0;
  return !!userMessageId && hasResponseState;
}

export function conversationResumeReady(probe) {
  const userMessageId = String(probe?.userMessageId || '').trim();
  return !!probe?.composer && !!userMessageId;
}

const delay = (ms, signal) => new Promise((resolve, reject) => {
  if (signal?.aborted) return reject(signal.reason || new Error('aborted'));
  const timer = setTimeout(resolve, ms);
  signal?.addEventListener('abort', () => {
    clearTimeout(timer);
    reject(signal.reason || new Error('aborted'));
  }, { once: true });
});

async function evaluate(session, expression, timeoutMs = RUNTIME_EVALUATE_TIMEOUT_MS) {
  const result = await session.call('Runtime.evaluate', {
    expression,
    returnByValue: true,
    awaitPromise: true,
  }, { timeoutMs });
  if (result.exceptionDetails) {
    const detail = result.exceptionDetails.exception?.description
      || result.exceptionDetails.exception?.value
      || result.exceptionDetails.text
      || 'Browser script evaluation failed';
    throw new Error(String(detail));
  }
  return result.result?.value;
}

function runtimeEvaluateTimedOut(error) {
  return String(error?.message || error) === 'CDP command timeout: Runtime.evaluate';
}

function conversationId(url) {
  try {
    const parts = new URL(url).pathname.split('/').filter(Boolean);
    const index = parts.indexOf('c');
    const raw = index >= 0 && index + 1 < parts.length ? parts[index + 1] : '';
    const id = decodeURIComponent(raw);
    if (!id || id.toLowerCase().startsWith('local-chatgpt')) return '';
    return /^[A-Za-z0-9_-]{1,160}$/.test(id) ? id : '';
  } catch {
    return '';
  }
}


const DECORATIVE_ANIMATION_PAUSE_SOURCE = "(() => {" +
  "const id='__selfrun_decorative_animation_pause';" +
  "const css=[" +
    "'[class*=\\\"LoadingResultsShimmer\\\"]'," +
    "'[class*=\\\"LoadingResultsShimmer\\\"]::before'," +
    "'[class*=\\\"LoadingResultsShimmer\\\"]::after'," +
    "'.loading-shimmer-pure-text'," +
    "'.loading-shimmer-pure-text::before'," +
    "'.loading-shimmer-pure-text::after'," +
    "'.pulsing-dot'," +
    "'.pulsing-dot::before'," +
    "'.pulsing-dot::after'" +
  "].join(',')+'{animation-play-state:paused !important;}';" +
  "const install=()=>{" +
    "if(document.getElementById(id))return true;" +
    "const style=document.createElement('style');" +
    "style.id=id;style.textContent=css;" +
    "(document.head||document.documentElement).appendChild(style);" +
    "return true;" +
  "};" +
  "if(document.documentElement)return install();" +
  "document.addEventListener('DOMContentLoaded',install,{once:true});" +
  "return false;" +
"})()";

async function installDecorativeAnimationPause(session) {
  await session.call('Page.addScriptToEvaluateOnNewDocument', {
    source: DECORATIVE_ANIMATION_PAUSE_SOURCE,
  });
  await evaluate(session, DECORATIVE_ANIMATION_PAUSE_SOURCE);
}

function androidWebViewUserAgent(config) {
  const android=String(config?.browserAndroidVersion||'17').trim()||'17';
  const model=String(config?.browserAndroidModel||'Android').trim()||'Android';
  const build=String(config?.browserAndroidBuildId||'UNKNOWN').trim()||'UNKNOWN';
  const chrome=String(config?.browserAndroidChromeVersion||'149.0.7827.155').trim()||'149.0.7827.155';
  return String(config?.browserAndroidUserAgent||'').trim()
    || `Mozilla/5.0 (Linux; Android ${android}; ${model} Build/${build}; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/${chrome} Mobile Safari/537.36`;
}

export async function applyBrowserClientProfile(session, config = {}) {
  const mode=String(config?.browserClientProfile||'native').trim().toLowerCase();
  if(mode!=='android-webview')return {mode:'native'};

  const chrome=String(config?.browserAndroidChromeVersion||'149.0.7827.155').trim()||'149.0.7827.155';
  const major=chrome.split('.')[0]||'149';
  const model=String(config?.browserAndroidModel||'Android').trim()||'Android';
  const platformVersion=String(config?.browserAndroidPlatformVersion||config?.browserAndroidVersion||'17.0.0').trim()||'17.0.0';
  const width=Math.max(320,Number(config?.browserAndroidWidth||412));
  const height=Math.max(480,Number(config?.browserAndroidHeight||915));
  const deviceScaleFactor=Math.max(1,Number(config?.browserAndroidDeviceScaleFactor||3));
  const acceptLanguage=String(config?.browserAndroidAcceptLanguage||'ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7');

  await session.call('Emulation.setUserAgentOverride',{
    userAgent:androidWebViewUserAgent(config),
    acceptLanguage,
    platform:'Android',
    userAgentMetadata:{
      brands:[
        {brand:'Chromium',version:major},
        {brand:'Google Chrome',version:major},
        {brand:'Not_A Brand',version:'99'},
      ],
      fullVersionList:[
        {brand:'Chromium',version:chrome},
        {brand:'Google Chrome',version:chrome},
        {brand:'Not_A Brand',version:'99.0.0.0'},
      ],
      platform:'Android',
      platformVersion,
      architecture:'',
      model,
      mobile:true,
      bitness:'',
      wow64:false,
    },
  });
  await session.call('Emulation.setDeviceMetricsOverride',{
    width,height,deviceScaleFactor,mobile:true,
    screenWidth:width,screenHeight:height,
    screenOrientation:{type:'portraitPrimary',angle:0},
  });
  await session.call('Emulation.setTouchEmulationEnabled',{enabled:true,maxTouchPoints:5});
  return {mode:'android-webview',width,height,deviceScaleFactor,model,platformVersion,userAgent:androidWebViewUserAgent(config)};
}

export function effectiveStopButtonVisible({ rawStopButtonVisible, inConversation, userCount, assistantCount, responseTurnId }) {
  return Boolean(rawStopButtonVisible && (inConversation || userCount > 0 || assistantCount > 0 || responseTurnId));
}

function probeExpression() {
  const errorPattern = JSON.stringify(PAGE_ERROR_PATTERN);
  return `(() => {
    const visible=e=>!!e&&e.isConnected&&e.offsetParent!==null;
    const buttons=[...document.querySelectorAll('button')].filter(visible);
    const selectors=['textarea#prompt-textarea','textarea[data-testid="prompt-textarea"]',
      'div#prompt-textarea[contenteditable="true"]','main form [contenteditable="true"][data-lexical-editor="true"]',
      'main form [contenteditable="true"]'];
    let composer=null;
    for(const selector of selectors){composer=[...document.querySelectorAll(selector)].find(visible);if(composer)break;}
    const rawStopButtonVisible=buttons.some(b=>b.dataset.testid==='stop-button'||/stop|중지/i.test((b.getAttribute('aria-label')||'')+' '+(b.title||'')));
    const paused=buttons.some(b=>/resume|continue generating|재개|계속 생성/i.test((b.getAttribute('aria-label')||'')+' '+(b.title||'')+' '+(b.innerText||'')));
    const assistants=[...document.querySelectorAll('[data-message-author-role="assistant"],[data-chatgpt-search-unit-key$=":assistant"],[data-content-search-unit-key$=":assistant"]')];
    const last=assistants.length?assistants[assistants.length-1]:null;
    const users=[...document.querySelectorAll('[data-message-author-role="user"],[data-chatgpt-search-unit-key$=":user"],[data-content-search-unit-key$=":user"]')];
    const lastUser=users.length?users[users.length-1]:null;
    const messageId=e=>{
      for(let node=e,depth=0;node&&depth<6;node=node.parentElement,depth+=1){
        for(const name of ['data-message-id','data-chatgpt-search-unit-key','data-content-search-unit-key']){
          const value=String(node.getAttribute?.(name)||'').trim();
          if(value)return name+':'+value;
        }
        const testid=String(node.getAttribute?.('data-testid')||'').trim();
        if(/conversation-turn|message/i.test(testid))return 'data-testid:'+testid;
        const id=String(node.id||'').trim();
        if(/conversation|message|turn/i.test(id))return 'id:'+id;
      }
      return null;
    };
    const normalizeProgressText=${normalizeProgressText.toString()};
    const fingerprint=value=>{
      const input=String(value||'');
      if(!input)return '';
      let hash=2166136261;
      for(let i=0;i<input.length;i+=1){hash^=input.charCodeAt(i);hash=Math.imul(hash,16777619);}
      return (hash>>>0).toString(16).padStart(8,'0');
    };
    const turns=[...document.querySelectorAll('main [data-turn-key]')];
    const responseTurn=turns.length?turns[turns.length-1]:null;
    const responseTurnId=String(responseTurn?.getAttribute?.('data-turn-key')||'').trim()||null;
    const pathParts=location.pathname.split('/').filter(Boolean);
    const inConversation=pathParts.includes('c')&&pathParts.indexOf('c')+1<pathParts.length;
    const effectiveStopButtonVisible=${effectiveStopButtonVisible.toString()};
    const stopButtonVisible=effectiveStopButtonVisible({
      rawStopButtonVisible,
      inConversation,
      userCount:users.length,
      assistantCount:assistants.length,
      responseTurnId,
    });
    let responseText='';
    if(responseTurn){
      const excludedSelector=[
        '[data-user-message-bubble]',
        '[data-message-author-role="user"]',
        '[data-chatgpt-search-unit-key$=":user"]',
        '[data-content-search-unit-key$=":user"]',
        'form','textarea','input','select','time','script','style','noscript','svg',
        '[data-markdown-copy="exclude"]','[aria-hidden="true"]','.sr-only'
      ].join(',');
      const parts=[];
      const walker=document.createTreeWalker(responseTurn,NodeFilter.SHOW_TEXT);
      while(walker.nextNode()){
        const node=walker.currentNode;
        const parent=node.parentElement;
        if(!parent||parent.closest(excludedSelector))continue;
        const button=parent.closest('button');
        if(button){
          const label=String(button.getAttribute('aria-label')||button.title||button.innerText||'');
          if(/copy|good response|bad response|share|regenerate|retry|more|복사|공유|다시 생성|재시도|더보기/i.test(label))continue;
        }
        let hidden=false;
        for(let el=parent;el&&el!==responseTurn.parentElement;el=el.parentElement){
          const style=getComputedStyle(el);
          if(style.display==='none'||style.visibility==='hidden'||style.opacity==='0'){hidden=true;break;}
        }
        if(hidden)continue;
        const value=String(node.nodeValue||'').trim();
        if(value)parts.push(value);
      }
      responseText=normalizeProgressText(parts.join(' '));
    }
    const responseTextLength=responseText.length;
    const responseFingerprint=fingerprint(responseText);
    const body=String(document.body?.innerText||'');
    const errorMatch=body.match(new RegExp(${errorPattern},'i'));
    return {
      url:location.href,
      title:document.title,
      readyState:document.readyState,
      loginPage:(()=>{const p=String(location.pathname||'').toLowerCase();return p==='/auth'||p.startsWith('/auth/')||p==='/login'||p.startsWith('/login/');})(),
      composer:!!composer,
      streaming:stopButtonVisible,
      stopButtonVisible,
      paused,
      assistantCount:assistants.length,
      assistantTextLength:String(last?.innerText||last?.textContent||'').length,
      assistantMessageId:messageId(last),
      responseTurnId,
      responseTextLength,
      responseFingerprint,
      userCount:users.length,
      userTextLength:String(lastUser?.innerText||lastUser?.textContent||'').length,
      userMessageId:messageId(lastUser),
      bodyTextLength:body.length,
      errorText:errorMatch?errorMatch[0]:null
    };
  })()`;
}

function newChatExpression() {
  return `(() => {
    const visible=e=>!!e&&e.isConnected&&e.offsetParent!==null;
    const parts=location.pathname.split('/').filter(Boolean);
    const inConversation=parts.includes('c')&&parts.indexOf('c')+1<parts.length;
    if(!inConversation)return {status:'READY',url:location.href};
    const label=e=>String(e.innerText||e.textContent||e.getAttribute?.('aria-label')||'').replace(/\\s+/g,' ').trim();
    const control=[...document.querySelectorAll('button,a,[role="button"]')].filter(visible)
      .find(e=>/^(new chat|new conversation|새 채팅|새 대화)$/i.test(label(e)));
    if(!control)return {status:'MISSING',url:location.href};
    control.focus?.();control.click();
    return {status:'CLICKED',url:location.href};
  })()`;
}

function inputExpression(prompt) {
  const expected = JSON.stringify(prompt);
  return `(() => {
    const visible=e=>!!e&&e.isConnected&&e.offsetParent!==null;
    const selectors=['textarea#prompt-textarea','textarea[data-testid="prompt-textarea"]',
      'div#prompt-textarea[contenteditable="true"]','main form [contenteditable="true"][data-lexical-editor="true"]',
      'main form [contenteditable="true"]'];
    let composer=null;
    for(const selector of selectors){composer=[...document.querySelectorAll(selector)].find(visible);if(composer)break;}
    if(!composer)return {status:'NO_COMPOSER'};
    const norm=s=>String(s??'').replace(/[\\u200B-\\u200D\\uFEFF]/g,'').replace(/\\u00a0/g,' ').replace(/\\r\\n?/g,'\\n').replace(/[\\u2028\\u2029]/g,'\\n').trim();
    const canonical=s=>norm(s).replace(/[ \\t]+/g,' ').replace(/ *\\n+ */g,'\\n');
    const expected=${expected};
    const raw=()=>('value'in composer?composer.value:(composer.innerText||composer.textContent||''));
    const same=()=>canonical(raw())===canonical(expected);
    const fire=(type,inputType,data)=>{try{return composer.dispatchEvent(new InputEvent(type,{bubbles:true,cancelable:type==='beforeinput',inputType,data}));}catch(_){return composer.dispatchEvent(new Event(type,{bubbles:true,cancelable:type==='beforeinput'}));}};
    const selectAll=()=>{composer.focus();const selection=window.getSelection();if(!selection)return false;const range=document.createRange();range.selectNodeContents(composer);selection.removeAllRanges();selection.addRange(range);return true;};
    const nativeSet=value=>{const proto=Object.getPrototypeOf(composer);const own=Object.getOwnPropertyDescriptor(proto,'value');const base=typeof HTMLTextAreaElement!=='undefined'?Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype,'value'):null;const setter=own?.set||base?.set;if(setter)setter.call(composer,value);else composer.value=value;fire('input','insertText',value);composer.dispatchEvent(new Event('change',{bubbles:true}));};
    const execInsert=()=>{selectAll();try{document.execCommand('delete',false,null);}catch(_){}try{document.execCommand('insertText',false,expected);}catch(_){}};
    composer.focus();
    if('value'in composer)nativeSet(expected);else execInsert();
    return {status:same()?'READY':'MISMATCH',length:String(raw()).length};
  })()`;
}

function sendReadyExpression() {
  return `(() => {
    const visible=e=>!!e&&e.isConnected&&e.offsetParent!==null;
    const selectors=['textarea#prompt-textarea','textarea[data-testid="prompt-textarea"]',
      'div#prompt-textarea[contenteditable="true"]','main form [contenteditable="true"][data-lexical-editor="true"]',
      'main form [contenteditable="true"]'];
    let composer=null;
    for(const selector of selectors){composer=[...document.querySelectorAll(selector)].find(visible);if(composer)break;}
    if(!composer)return {ready:false,status:'NO_COMPOSER'};
    const scope=composer.closest('form')||document;
    const send=[...scope.querySelectorAll('button')].filter(visible)
      .find(b=>b.dataset.testid==='send-button'||b.dataset.testid==='composer-submit-button'||
        /send|보내기|submit/i.test((b.getAttribute('aria-label')||'')+' '+(b.title||'')));
    return {ready:!!send&&!send.disabled&&send.getAttribute('aria-disabled')!=='true',
      status:send?'SEND_FOUND':'NO_SEND'};
  })()`;
}

function submitExpression() {
  return `(() => {
    const visible=e=>!!e&&e.isConnected&&e.offsetParent!==null;
    const selectors=['textarea#prompt-textarea','textarea[data-testid="prompt-textarea"]',
      'div#prompt-textarea[contenteditable="true"]','main form [contenteditable="true"][data-lexical-editor="true"]',
      'main form [contenteditable="true"]'];
    let composer=null;
    for(const selector of selectors){composer=[...document.querySelectorAll(selector)].find(visible);if(composer)break;}
    if(!composer)return {status:'NO_COMPOSER'};
    const scope=composer.closest('form')||document;
    const send=[...scope.querySelectorAll('button')].filter(visible)
      .find(b=>b.dataset.testid==='send-button'||b.dataset.testid==='composer-submit-button'||
        /send|보내기|submit/i.test((b.getAttribute('aria-label')||'')+' '+(b.title||'')));
    if(!send||send.disabled||send.getAttribute('aria-disabled')==='true')return {status:'NO_SEND'};
    send.focus?.();send.click();
    return {status:'SUBMITTED'};
  })()`;
}


export const REQUEST_PROFILE_ENGINE_VERSION='selfrun-request-profile-engine-v1';
const REQUEST_PROFILE_CONTROL=Object.freeze(['model','thinking_effort','conversation_origin','service_tier']);

export function normalizeProfileOperations(operations=[]) {
  if(!Array.isArray(operations)||operations.length!==REQUEST_PROFILE_CONTROL.length)
    throw new Error('Profile operation set is incomplete');
  const seen=new Set(),out=[];
  for(const operation of operations) {
    const path=String(operation?.path||''),op=String(operation?.op||'').toUpperCase();
    if(!REQUEST_PROFILE_CONTROL.includes(path)||seen.has(path))throw new Error('Profile operation is not allowlisted');
    seen.add(path);
    if(op==='SET') {
      const value=String(operation?.value??'');
      if(!value||value.length>128)throw new Error('Profile operation value is invalid');
      out.push(['set',path,value]);
    } else if(op==='REMOVE') out.push(['remove',path]);
    else throw new Error('Unknown profile operation');
  }
  if(seen.size!==REQUEST_PROFILE_CONTROL.length)throw new Error('Profile operation set is incomplete');
  return out;
}

export function requestProfileDocumentStartSource() {
  const version=JSON.stringify(REQUEST_PROFILE_ENGINE_VERSION);
  return `(()=>{
    if(window.__selfrunRequestProfileEngine?.version===${version})return true;
    const CONTROL=['model','thinking_effort','conversation_origin','service_tier'];
    const state={operations:null,last:{ok:false,reason:'not_configured'}};
    const norm=value=>String(value??'').trim().toLowerCase();
    const fail=reason=>{state.last={ok:false,reason:String(reason||'profile_failure').slice(0,100)};throw new Error('REQUEST_PROFILE:'+state.last.reason);};
    const validateOps=operations=>{
      if(!Array.isArray(operations)||operations.length!==CONTROL.length)fail('operation_count_invalid');
      const seen=new Set(),out=[];
      for(const raw of operations){
        if(!Array.isArray(raw)||(raw.length!==2&&raw.length!==3))fail('operation_shape_invalid');
        const kind=norm(raw[0]),path=String(raw[1]??'');
        if(!CONTROL.includes(path)||seen.has(path))fail('control_allowlist_violation');
        seen.add(path);
        if(kind==='set'){
          if(raw.length!==3||typeof raw[2]!=='string'||raw[2].length<1||raw[2].length>128)fail('control_value_invalid');
          out.push(['set',path,raw[2]]);
        }else if(kind==='remove'){
          if(raw.length!==2)fail('remove_value_forbidden');
          out.push(['remove',path]);
        }else fail('unknown_operation');
      }
      if(seen.size!==CONTROL.length)fail('operation_set_incomplete');
      return out;
    };
    const sameOrigin=url=>{try{return new URL(url,location.href).origin===location.origin;}catch(_){return false;}};
    const conversationRoute=url=>{try{
      const path=new URL(url,location.href).pathname.toLowerCase().replace(/\\/+$/,'');
      return path==='/backend-api/conversation'||path==='/backend-api/f/conversation';
    }catch(_){return false;}};
    const strip=object=>{const copy={...object};for(const key of CONTROL)delete copy[key];return copy;};
    const patchObject=(body,url)=>{
      if(!conversationRoute(url))fail('conversation_route_not_allowed');
      if(!body||typeof body!=='object'||Array.isArray(body)||!Array.isArray(body.messages))fail('unknown_conversation_schema');
      if(!state.operations)fail('target_not_ready');
      const before=JSON.stringify(strip(body)),output={...body};
      for(const [kind,path,value] of state.operations){if(kind==='set')output[path]=value;else delete output[path];}
      if(JSON.stringify(strip(output))!==before)fail('data_plane_changed');
      state.last={ok:true,reason:'patched'};
      return output;
    };
    const patchText=(url,method,text)=>{
      if(norm(method)!=='post'||!sameOrigin(url)||!conversationRoute(url))return null;
      if(typeof text!=='string')fail('non_text_conversation_body');
      let body;try{body=JSON.parse(text);}catch(_){fail('invalid_conversation_json');}
      return JSON.stringify(patchObject(body,url));
    };
    const nativeFetch=window.fetch.bind(window);
    const fetchProbe=(input,init)=>{try{
      const requestInput=typeof Request!=='undefined'&&input instanceof Request;
      const url=requestInput?input.url:String(input??'');
      const method=init&&init.method!==undefined?init.method:(requestInput?input.method:'GET');
      return{url,method,eligible:norm(method)==='post'&&sameOrigin(url)&&conversationRoute(url)};
    }catch(_){return{url:'',method:'',eligible:false};}};
    window.fetch=async function(input,init){
      const probe=fetchProbe(input,init);
      if(!probe.eligible)return nativeFetch(input,init);
      let request;try{const source=typeof Request!=='undefined'&&input instanceof Request?input.clone():input;request=new Request(source,init);}catch(_){fail('request_construction_failed');}
      let text;try{text=await request.clone().text();}catch(_){fail('request_body_unreadable');}
      const patched=patchText(request.url,request.method,text);
      if(patched===null)fail('target_patch_not_applied');
      try{return nativeFetch(new Request(request,{body:patched}));}catch(_){fail('patched_request_construction_failed');}
    };
    const nativeOpen=XMLHttpRequest.prototype.open,nativeSend=XMLHttpRequest.prototype.send,metadata=new WeakMap();
    XMLHttpRequest.prototype.open=function(method,url,...rest){metadata.set(this,{method:String(method||''),url:String(url||'')});return nativeOpen.call(this,method,url,...rest);};
    XMLHttpRequest.prototype.send=function(body){const request=metadata.get(this)||{method:'',url:''};const patched=patchText(request.url,request.method,body);return nativeSend.call(this,patched===null?body:patched);};
    window.__selfrunRequestProfileEngine={
      version:${version},
      configure:operations=>{state.operations=validateOps(operations);state.last={ok:true,reason:'target_ready'};return true;},
      diagnostics:()=>({...state.last})
    };
    return true;
  })()`;
}

function configureRequestProfileExpression(operations) {
  const normalized=JSON.stringify(normalizeProfileOperations(operations));
  const version=JSON.stringify(REQUEST_PROFILE_ENGINE_VERSION);
  return `(()=>{const engine=window.__selfrunRequestProfileEngine;if(!engine||engine.version!==${version}||typeof engine.configure!=='function')throw new Error('REQUEST_PROFILE_ENGINE_UNAVAILABLE');engine.configure(${normalized});return engine.diagnostics?.()||{ok:true};})()`;
}

async function installRequestProfileEngine(session) {
  const source=requestProfileDocumentStartSource();
  await session.call('Page.addScriptToEvaluateOnNewDocument',{source});
  await evaluate(session,source);
}

export class ChatGptBrowser {
  constructor(chromium, config) {
    this.chromium = chromium;
    this.config = config;
  }

  async #waitFor(session, predicate, { timeoutMs, signal, label }) {
    const started = Date.now();
    let last = null;
    while (Date.now() - started < timeoutMs) {
      if (signal?.aborted) throw signal.reason || new Error('aborted');
      try {
        last = await evaluate(session, probeExpression());
      } catch (error) {
        if(/websocket.*closed|websocket.*not open|ECONNREFUSED/i.test(String(error?.message||error))) {
          error.code='BROWSER_DEAD';throw error;
        }
        last = { probeError: String(error?.message || error) };
        await delay(250, signal);
        continue;
      }
      if (predicate(last)) return last;
      await delay(250, signal);
    }
    const error = new Error(`Timed out waiting for ${label}`);
    error.lastProbe = last;
    error.code=last?.probeError?'ATTACH_FAILED':'CONVERSATION_LOAD_FAILED';
    throw error;
  }

  async #prepareNewChat(session, projectUrl, signal) {
    await session.call('Page.navigate', { url: projectUrl });
    await this.#waitFor(session, (p) => p.readyState === 'complete' || p.readyState === 'interactive', {
      timeoutMs: this.config.navigationTimeoutMs,
      signal,
      label: 'project page',
    });

    let probe = await evaluate(session, probeExpression());
    if (probe.loginPage) {
      const error = new Error('ChatGPT browser profile requires sign-in');
      error.code = 'AUTH_REQUIRED';
      throw error;
    }

    for (let attempt = 0; attempt < 3; attempt += 1) {
      if (!/\/c\//.test(new URL(probe.url).pathname)) break;
      const result = await evaluate(session, newChatExpression());
      if (result?.status === 'MISSING') break;
      await delay(700, signal);
      probe = await evaluate(session, probeExpression());
    }
    if(conversationId(probe.url))throw Object.assign(new Error('new conversation could not be established'),{code:'NEW_CONVERSATION_REQUIRED'});
    return this.#waitFor(session, (p) => p.composer&&!conversationId(p.url), {
      timeoutMs: this.config.navigationTimeoutMs,
      signal,
      label: 'ChatGPT composer',
    });
  }

  async prepare({ projectUrl, prompt, signal }) {
    const target = await this.chromium.createTarget('about:blank');
    const session = await this.chromium.connectTarget(target);
    await session.call('Page.enable');
    await session.call('Runtime.enable');
    await session.call('Network.enable');
    await applyBrowserClientProfile(session,this.config);
    await installDecorativeAnimationPause(session);
    await installRequestProfileEngine(session);

    try {
      const before = await this.#prepareNewChat(session, projectUrl, signal);
      if (signal?.aborted) throw signal.reason || new Error('aborted');
      return {
        target,
        session,
        baseline: {
          assistantCount: before.assistantCount || 0,
          assistantTextLength: before.assistantTextLength || 0,
          responseTurnId: before.responseTurnId || null,
          responseTextLength: before.responseTextLength || 0,
          responseFingerprint: before.responseFingerprint || '',
          userCount: before.userCount || 0,
          userTextLength: before.userTextLength || 0,
        },
      };
    } catch (error) {
      session.close();
      await this.chromium.closeTarget(target.id);
      throw error;
    }
  }

  async resume({ conversationUrl, signal }) {
    const expectedId = conversationId(conversationUrl);
    if (!expectedId) throw new Error('canonical conversation URL unavailable for resume');

    const targets=await this.chromium.listExistingTargets();
    const existing=targets?.find(t=>t.type==='page'&&conversationId(t.url)===expectedId);
    const target = existing || await this.chromium.createTarget(conversationUrl);
    const session = await this.chromium.connectTarget(target);
    await session.call('Page.enable');
    await session.call('Runtime.enable');
    await session.call('Network.enable');
    await applyBrowserClientProfile(session,this.config);
    await installDecorativeAnimationPause(session);
    await installRequestProfileEngine(session);

    try {
      const probe = await this.#waitFor(session, (p) => p.loginPage
        || ((p.readyState === 'complete' || p.readyState === 'interactive')
          && conversationId(p.url) === expectedId
          && conversationResumeReady(p)) || !!p.errorText, {
        timeoutMs: this.config.navigationTimeoutMs,
        signal,
        label: 'existing conversation',
      });
      if (probe.loginPage) {
        const error = new Error('ChatGPT browser profile requires sign-in');
        error.code = 'AUTH_REQUIRED';
        throw error;
      }
      if(probe.errorText)throw Object.assign(new Error('conversation load failed'),{code:'CONVERSATION_LOAD_FAILED'});
      return {
        target,
        session,
        baseline: {
          assistantCount: Math.max(0, Number(probe.assistantCount || 0) - 1),
          assistantTextLength: 0,
          responseTurnId: probe.responseTurnId || null,
          responseTextLength: probe.responseTextLength || 0,
          responseFingerprint: probe.responseFingerprint || '',
          userCount: probe.userCount || 0,
          userTextLength: probe.userTextLength || 0,
        },
      };
    } catch (error) {
      session.close();
      if(!existing) await this.chromium.closeTarget(target.id);
      throw error;
    }
  }

  async #submitWithProfile({ session, prompt, signal, guard = () => true, conversationUrl = '', baseline = {}, onConversation = null }) {
    if (signal?.aborted) throw signal.reason || new Error('aborted');

    const sendReadyStarted = Date.now();
    let sendReady = null;
    while (Date.now() - sendReadyStarted < 5000) {
      if (signal?.aborted) throw signal.reason || new Error('aborted');
      sendReady = await evaluate(session, sendReadyExpression());
      if (sendReady?.ready) break;
      if (sendReady?.status==='NO_COMPOSER'||sendReady?.status==='NO_SEND') {
        const restaged=await evaluate(session,inputExpression(prompt));
        if(restaged?.status==='READY') {
          sendReady=await evaluate(session,sendReadyExpression());
          if(sendReady?.ready) break;
        }
      }
      await delay(150, signal);
    }
    if (!sendReady?.ready) {
      const error=new Error(`Composer send control not ready: ${sendReady?.status || 'unknown'}`);
      error.released=false;
      throw error;
    }

    let clickReleased=false;
    try {
      try {
        const submitted=await evaluate(session,submitExpression());
        if(submitted?.status!=='SUBMITTED')throw new Error(`Composer send failed: ${submitted?.status||'unknown'}`);
        clickReleased=true;
      } catch(error) {
        if(!runtimeEvaluateTimedOut(error))throw error;
        clickReleased=true;
      }
      if(signal?.aborted||!guard())throw new Error('stale submission');
      const expected=conversationId(conversationUrl);
      const routeProbe=await this.#waitFor(session,p=>{
        const current=conversationId(p.url);
        return expected ? current===expected : !!current;
      },{timeoutMs:this.config.navigationTimeoutMs,signal,label:'canonical conversation URL'});
      if(onConversation)await onConversation({url:routeProbe.url,probe:routeProbe});
      if(signal?.aborted||!guard())throw new Error('stale submission');
      const baselineCount=Number(baseline?.userCount||0);
      const baselineId=String(baseline?.userMessageId||'');
      if(Number(routeProbe.userCount||0)>baselineCount
          ||(!baselineId&&!!routeProbe.userMessageId)
          ||(!!baselineId&&!!routeProbe.userMessageId&&routeProbe.userMessageId!==baselineId))return routeProbe;
      return await this.#waitFor(session,p=>{
        const current=conversationId(p.url);
        if(expected ? current!==expected : !current)return false;
        const countAdvanced=Number(p.userCount||0)>baselineCount;
        const id=String(p.userMessageId||'');
        return countAdvanced||(!baselineId&&!!id)||(!!baselineId&&!!id&&id!==baselineId);
      },{timeoutMs:this.config.navigationTimeoutMs,signal,label:'submitted user message'});
    } catch(error) {
      if(clickReleased)error.released=true;
      throw error;
    }
  }

  async livenessSnapshot({ session, signal }) {
    if (signal?.aborted) throw signal.reason || new Error('aborted');
    return evaluate(session, probeExpression());
  }

  async attachTarget({targetId,signal}) {
    const targets=await this.chromium.listExistingTargets();
    const target=targets?.find(t=>t.id===targetId&&t.type==='page');
    if(!target)throw Object.assign(new Error('original submission target unavailable'),{code:'ATTACH_FAILED'});
    const session=await this.chromium.connectTarget(target);
    await session.call('Page.enable');
    await session.call('Runtime.enable');
    await applyBrowserClientProfile(session,this.config);
    await installRequestProfileEngine(session);
    if(signal?.aborted){session.close();throw signal.reason;}
    return {target,session,baseline:await evaluate(session,probeExpression())};
  }

  async submitIntent({session,prompt,kind,intent,conversationUrl,profileOperations,signal,guard,onRequest,onConversation}) {
    if(signal?.aborted||!guard())throw new Error('stale submission');
    const probe=await evaluate(session,probeExpression());
    if(conversationUrl&&conversationId(probe.url)!==conversationId(conversationUrl))
      throw new Error('submission conversation ownership mismatch');
    if(kind==='initial'&&conversationId(probe.url))throw new Error('initial submission cannot target an existing conversation');
    await evaluate(session,requestProfileDocumentStartSource());
    await evaluate(session,configureRequestProfileExpression(profileOperations));
    const staged=await evaluate(session,inputExpression(prompt));
    if(staged?.status!=='READY')throw new Error('submission composer not ready');
    if(signal?.aborted||!guard())throw new Error('stale submission');
    return this.#submitWithProfile({session,prompt,signal,guard,conversationUrl,baseline:intent?.baseline||{},onConversation});
  }

  async readSubmission({session,intent,prompt,conversationUrl,signal}) {
    if(signal?.aborted)throw signal.reason;
    let probe=await evaluate(session,probeExpression());
    const expectedId=conversationId(conversationUrl)||conversationId(probe.url);
    if(conversationUrl&&conversationId(probe.url)!==expectedId)return {state:'UNKNOWN',probe};
    // Optimistic DOM bubbles do not prove server acceptance. Reload the canonical page before classifying.
    try {
      await session.call('Page.reload',{ignoreCache:true});
      probe=await this.#waitFor(session,p=>p.loginPage||((p.readyState==='complete'||p.readyState==='interactive')&&p.composer
        &&(!expectedId||conversationId(p.url)===expectedId)),{timeoutMs:this.config.navigationTimeoutMs,signal,label:'submission readback'});
      if(probe.loginPage)return {state:'UNKNOWN',probe};
    }catch(error){if(signal?.aborted)throw error;return {state:'UNKNOWN',probe};}
    if(conversationUrl&&conversationId(probe.url)!==conversationId(conversationUrl))
      return {state:'UNKNOWN',probe};
    const expected=JSON.stringify(String(prompt).replace(/\s+/g,' ').trim());
    const messageId=JSON.stringify(intent.message_id||'');
    const expression='(() => {const expected='+expected+', id='+messageId+';'+
      'const users=[...document.querySelectorAll(\'[data-message-author-role="user"],[data-chatgpt-search-unit-key$=":user"],[data-content-search-unit-key$=":user"]\')];'+
      'const matched=users.map(e=>{let found=false;'+
      'for(let n=e,depth=0;n&&depth<6;n=n.parentElement,depth++){'+
      'const ids=["data-message-id","data-chatgpt-search-unit-key","data-content-search-unit-key"].map(k=>String(n.getAttribute?.(k)||""));'+
      'if(id&&ids.some(v=>v===id||v===id+":user"))found=true;}'+
      'const same=String(e.innerText||e.textContent||"").replace(/\\s+/g," ").trim()===expected;return {found,same};});'+
      'return {idMatch:matched.some(m=>m.found),lastTextMatch:matched.at(-1)?.same||false};})()';
    const evidence=await evaluate(session,expression);
    const hasBaseline=Number.isFinite(intent.baseline?.userCount)||!!intent.baseline?.userMessageId;
    const advanced=hasBaseline&&(Number(probe.userCount)>Number(intent.baseline?.userCount||0)||
      (!!probe.userMessageId&&probe.userMessageId!==intent.baseline?.userMessageId));
    if(conversationId(probe.url)&&(evidence.idMatch||(advanced&&evidence.lastTextMatch)))
      return {state:'CONFIRMED',probe,messageId:intent.message_id||probe.userMessageId};
    // A request released to ChatGPT may arrive later; a quiet page alone cannot prove absence.
    if(intent.absence_proof==='INTERCEPTED_REQUEST_ABORTED'&&!intent.released&&!advanced&&!probe.streaming&&probe.readyState==='complete'&&probe.composer)
      return {state:'ABSENT',probe};
    return {state:'UNKNOWN',probe};
  }

  async quiesce({session,conversationUrl,signal,guard}) {
    const before=await evaluate(session,probeExpression());
    if(conversationId(before.url)!==conversationId(conversationUrl))throw new Error('quiesce conversation mismatch');
    if(!before.streaming&&!before.stopButtonVisible)return before;
    if(signal?.aborted||!guard())throw new Error('stale stream cleanup');
    await evaluate(session,'(() => {'+
      'const buttons=[...document.querySelectorAll("button")].filter(e=>e.isConnected&&e.offsetParent!==null);'+
      'const stop=buttons.find(b=>b.dataset.testid==="stop-button"||/stop|중지/i.test((b.getAttribute("aria-label")||"")+" "+(b.title||"")));'+
      'if(stop)stop.click();return !!stop;})()');
    return this.#waitFor(session,p=>!p.streaming&&!p.stopButtonVisible,{
      timeoutMs:this.config.navigationTimeoutMs,signal,label:'stuck stream cleanup'});
  }

  async monitor({ session, baseline, signal, onActivity, livenessGate }) {
    let previous = { ...baseline, streaming: false, paused: false, url: null };
    let lastActivityAt = Date.now();
    let stalled = false;
    let lastReportedStatus = null;
    let lastGateToken = null;
    let nextStalledVerificationAt = 0;
    let evaluateTimeoutRetries = 0;
    let conversationStateMissingSince = 0;

    while (!signal?.aborted) {
      let probe;
      try {
        probe = await evaluate(session, probeExpression());
        evaluateTimeoutRetries = 0;
      } catch (error) {
        if (signal?.aborted) throw signal.reason || new Error('aborted');
        const message = String(error?.message || error);
        if (message !== 'CDP command timeout: Runtime.evaluate'
            || evaluateTimeoutRetries >= 3) {
          throw error;
        }
        evaluateTimeoutRetries += 1;
        await delay(this.config.probeIntervalMs, signal);
        continue;
      }
      const gate = await livenessGate?.() || {};
      const gateState = String(gate.state || 'UNKNOWN');
      const gateEpoch = Number(gate.epoch || 0);
      const gateToken = gateState + ':' + gateEpoch;
      if (gateToken !== lastGateToken) {
        lastGateToken = gateToken;
        lastActivityAt = Date.now();
        stalled = false;
      }
      const livenessSuspended = gateState !== 'RUNNING';
      if (livenessSuspended) {
        lastActivityAt = Date.now();
        stalled = false;
      }
      const progressDetected =
        probe.assistantCount !== previous.assistantCount ||
        probe.assistantTextLength !== previous.assistantTextLength ||
        probe.assistantMessageId !== previous.assistantMessageId ||
        probe.responseTurnId !== previous.responseTurnId ||
        probe.responseTextLength !== previous.responseTextLength ||
        probe.responseFingerprint !== previous.responseFingerprint ||
        probe.userCount !== previous.userCount ||
        probe.userTextLength !== previous.userTextLength ||
        probe.userMessageId !== previous.userMessageId;
      const changed =
        probe.url !== previous.url ||
        probe.paused !== previous.paused ||
        progressDetected;

      if (changed) {
        lastActivityAt = Date.now();
        stalled = false;
      }

      const structurallyReady = conversationStateReady(probe);
      const resumeReady = conversationResumeReady(probe);
      const conversationAvailable = structurallyReady || resumeReady;
      if (conversationAvailable) conversationStateMissingSince = 0;
      else if (!conversationStateMissingSince) conversationStateMissingSince = Date.now();
      const stateGraceMs = Math.max(0, Number(this.config.conversationStateGraceMs ?? 5000));
      const conversationStateUnavailable = !livenessSuspended
        && conversationStateMissingSince > 0
        && Date.now() - conversationStateMissingSince >= stateGraceMs;

      // ChatGPT response lifecycle is telemetry only. It must never terminate a SelfRun dispatch.
      const isStalled = !livenessSuspended
        && Date.now() - lastActivityAt >= this.config.stallAfterMs;
      if (isStalled) stalled = true;

      const structuralError = conversationStateUnavailable ? 'Conversation state unavailable' : null;
      const pageError = conversationAvailable ? null : (probe.errorText || structuralError);
      let status = 'RUNNING';
      if (pageError) status = 'PAGE_ERROR';
      else if (stalled) status = 'STALLED';

      const verificationRetry = status === 'STALLED'
        && nextStalledVerificationAt > 0
        && Date.now() >= nextStalledVerificationAt;
      { // Every observation permits Result reconciliation even when UI telemetry is stale.
        const action = await onActivity?.({
          status,
          pageUrl: probe.url,
          streaming: probe.streaming,
          stopButtonVisible: probe.stopButtonVisible,
          paused: probe.paused,
          assistantCount: probe.assistantCount,
          assistantTextLength: probe.assistantTextLength,
          assistantMessageId: probe.assistantMessageId,
          responseTurnId: probe.responseTurnId,
          responseTextLength: probe.responseTextLength,
          responseFingerprint: probe.responseFingerprint,
          userCount: probe.userCount,
          userTextLength: probe.userTextLength,
          userMessageId: probe.userMessageId,
          lastActivityAt: new Date(lastActivityAt).toISOString(),
          pageError,
          conversationStateReady: structurallyReady,
          verificationRetry,
          progressDetected,
        });
        if (action?.resetLiveness) {
          lastActivityAt = Date.now();
          stalled = false;
          nextStalledVerificationAt = 0;
        } else if (status === 'STALLED' && Number(action?.retryAfterMs) > 0) {
          nextStalledVerificationAt = Date.now() + Number(action.retryAfterMs);
        } else if (status !== 'STALLED') {
          nextStalledVerificationAt = 0;
        }
        lastReportedStatus = status;
      }

      previous = probe;
      if (pageError) {
        return {
          status,
          probe: { ...probe, errorText: pageError },
          lastActivityAt,
        };
      }
      await delay(this.config.probeIntervalMs, signal);
    }

    return { status: 'ABORTED' };
  }
}
