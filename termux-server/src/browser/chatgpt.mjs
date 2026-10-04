import { canonicalUrl, cursor } from '../lifecycle.mjs';

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
  const assistantMessageId = String(probe?.assistantMessageId || '').trim();
  const hasResponseState = !!probe?.streaming || !!probe?.paused || !!assistantMessageId
    || Number(probe?.responseTextLength || 0) > 0;
  return hasResponseState;
}

export function conversationResumeReady(probe) {
  return !!probe?.composer;
}

const delay = (ms, signal) => new Promise((resolve, reject) => {
  if (signal?.aborted) return reject(signal.reason || new Error('aborted'));
  const cleanup = () => signal?.removeEventListener('abort', onAbort);
  const onAbort = () => {
    clearTimeout(timer); cleanup();
    reject(signal.reason || new Error('aborted'));
  };
  const timer = setTimeout(() => { cleanup(); resolve(); }, ms);
  signal?.addEventListener('abort', onAbort, { once: true });
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

function recoveryInputExpression() {
  return `(() => {
    const visible=e=>e.isConnected&&e.offsetParent!==null;
    const selectors=['textarea#prompt-textarea','textarea[data-testid="prompt-textarea"]',
      'div#prompt-textarea[contenteditable="true"]','main form [contenteditable="true"][data-lexical-editor="true"]',
      'main form [contenteditable="true"]'];
    const composer=selectors.some(selector=>[...document.querySelectorAll(selector)].some(visible));
    const stopButtonVisible=[...document.querySelectorAll('button')].filter(visible).some(b=>
      b.dataset.testid==='stop-button'||/stop|중지/i.test((b.getAttribute('aria-label')||'')+' '+(b.title||'')));
    return {url:location.href,composer,streaming:stopButtonVisible,stopButtonVisible};
  })()`;
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
    const legacyTurns=[...document.querySelectorAll('main [data-turn-key]')];
    const sections=[...document.querySelectorAll('main section[data-turn-id][data-testid^="conversation-turn"]')];
    const follows=(node,other)=>!!node&&!!(other?.compareDocumentPosition?.(node)&4);
    const newest=nodes=>nodes.reduce((prior,node)=>!prior||follows(node,prior)?node:prior,null);
    const contains=(root,node)=>!!root?.contains?.(node);
    const userSection=section=>String(section.getAttribute('data-turn')||'').toLowerCase()==='user'
      ||users.some(user=>contains(section,user));
    const userSections=sections.filter(userSection);
    // A pending user section can precede its message bubble. Do not revive the
    // preceding assistant when deciding which response belongs to this input.
    const latestUser=newest([...users,...userSections])||lastUser;
    const eligible=turn=>{
      if(String(turn.getAttribute('data-turn')||'').toLowerCase()==='user')return false;
      if(sections.includes(turn)&&userSection(turn))return false;
      if(!latestUser)return legacyTurns.includes(turn)
        ||String(turn.getAttribute('data-turn')||'').toLowerCase()==='assistant'
        ||assistants.some(assistant=>contains(turn,assistant));
      if(follows(turn,latestUser))return true;
      // Older wrappers can contain the user and thinking before an assistant
      // bubble appears. The text walker below still excludes every user node.
      return legacyTurns.includes(turn)&&contains(turn,latestUser);
    };
    const responseTurn=newest([...new Set([...legacyTurns,...sections])].filter(eligible));
    const responseRoot=responseTurn||((!latestUser||follows(last,latestUser))
      &&!sections.some(section=>userSection(section)&&contains(section,last))?last:null);
    const responseAssistants=assistants.filter(assistant=>contains(responseRoot,assistant)
      &&!userSections.some(section=>contains(section,assistant)));
    const responseAssistant=responseAssistants.length?responseAssistants[responseAssistants.length-1]:null;
    const responseTurnId=String(responseTurn?.getAttribute?.('data-turn-key')
      ||responseTurn?.getAttribute?.('data-turn-id')||'').trim()||null;
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
    if(responseRoot){
      const excludedSelector=[
        '[data-user-message-bubble]',
        '[data-message-author-role="user"]',
        '[data-chatgpt-search-unit-key$=":user"]',
        '[data-content-search-unit-key$=":user"]',
        'form','textarea','input','select','time','script','style','noscript','svg',
        '[data-markdown-copy="exclude"]','[aria-hidden="true"]','.sr-only'
      ].join(',');
      const parts=[];
      const walker=document.createTreeWalker(responseRoot,NodeFilter.SHOW_TEXT);
      while(walker.nextNode()){
        const node=walker.currentNode;
        const parent=node.parentElement;
        if(!parent||parent.closest(excludedSelector)||userSections.some(section=>contains(section,parent)))continue;
        const button=parent.closest('button');
        if(button){
          const label=String(button.getAttribute('aria-label')||button.title||button.innerText||'');
          if(/copy|good response|bad response|share|regenerate|retry|more|복사|공유|다시 생성|재시도|더보기/i.test(label))continue;
        }
        let hidden=false;
        for(let el=parent;el&&el!==responseRoot.parentElement;el=el.parentElement){
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
      responseAssistantCount:responseAssistants.length,
      responseAssistantTextLength:normalizeProgressText(responseAssistant?.innerText||responseAssistant?.textContent||'').length,
      responseAssistantMessageId:messageId(responseAssistant),
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

// Receipt-only observer: the existing v1 request-profile engine remains the sole body mutator.
// Event vocabulary/framing follows Android ChatGptTurnProtocolScript (436da865).
function installReceiptObserver() {
  const version='selfrun-passive-receipt-v1';
  if(window.__selfrunReceiptObserver?.version===version)return true;
  const downstreamFetch=window.fetch;
  const MAX_BYTES=262144,MAX_EVENT=65536,MAX_MS=30000;
  let current=null,sequence=0;
  const canonical=value=>typeof value==='string'&&/^[A-Za-z0-9_-]{1,160}$/.test(value)
    &&!value.toLowerCase().startsWith('local-chatgpt')?value:'';
  const same=(entry,key)=>!!entry&&entry.intent_id===key?.intent_id&&entry.generation===key?.generation;
  const active=entry=>current===entry&&!entry.retired&&!entry.invalid;
  const stopReader=entry=>{
    clearTimeout(entry.timer);entry.timer=null;
    const reader=entry.reader;entry.reader=null;
    // Awaiting tee cancellation can wait for the original response. Never do that.
    if(reader)try{Promise.resolve(reader.cancel()).catch(()=>{});}catch{}
  };
  const invalidate=(entry,reason)=>{
    if(current!==entry)return;
    entry.invalid=reason;entry.ack=null;stopReader(entry);
  };
  const retire=entry=>{if(entry){entry.retired=true;stopReader(entry);}};
  const bind=(entry,id)=>{
    if(id===undefined||id===null||id==='')return true;
    const value=canonical(id);
    if(!value||(entry.conversation_id&&entry.conversation_id!==value)){
      invalidate(entry,'conversation_mismatch');return false;
    }
    entry.conversation_id=value;return true;
  };
  const inspect=(entry,value)=>{
    if(!active(entry)||!value||typeof value!=='object'||Array.isArray(value))return;
    if(value.error||value.type==='error'){invalidate(entry,'response_error');return;}
    if(!bind(entry,value.conversation_id))return;
    const message=value.message||value.v?.message;
    if(message?.conversation_id&&!bind(entry,message.conversation_id))return;
    if(value.type==='message_marker'&&value.event==='first'
        &&['cot_token','user_visible_token','final_channel_token'].includes(value.marker))entry.ack=value.marker;
    else if(message&&typeof message==='object'&&typeof message.id==='string'&&message.id.length>0&&message.id.length<=160
        &&message.author?.role==='assistant'&&['analysis','final','commentary'].includes(message.channel)
        &&message.content&&Array.isArray(message.content.parts)&&message.content.parts.every(part=>typeof part==='string'))entry.ack='assistant_message';
  };
  const inspectResponse=async(entry,response)=>{
    if(!active(entry))return;
    if(!response?.ok){invalidate(entry,'http_error');return;}
    let reader;
    try{reader=response.clone().body?.getReader();}catch{invalidate(entry,'clone_unavailable');return;}
    if(!reader){invalidate(entry,'response_body_unavailable');return;}
    entry.reader=reader;
    entry.timer=setTimeout(()=>invalidate(entry,'response_bound'),MAX_MS);
    const decoder=new TextDecoder();let buffer='',bytes=0;
    const drain=final=>{
      // Keep a trailing CR until the next chunk so a split CRLF is not two newlines.
      let source=buffer,carry='';
      if(!final&&source.endsWith('\r')){source=source.slice(0,-1);carry='\r';}
      source=source.replace(/\r\n/g,'\n').replace(/\r/g,'\n');
      let boundary;
      while((boundary=source.indexOf('\n\n'))>=0){
        const block=source.slice(0,boundary);source=source.slice(boundary+2);
        if(block.length>MAX_EVENT){invalidate(entry,'event_bound');break;}
        const data=block.split('\n').filter(line=>line.startsWith('data:')).map(line=>line.slice(5).replace(/^ /,'')).join('\n');
        if(data.trim()&&data.trim()!=='[DONE]'){
          let value;try{value=JSON.parse(data);}catch{invalidate(entry,'malformed_event');break;}
          inspect(entry,value);
        }
        if(!active(entry))break;
      }
      buffer=source+carry;
      if(buffer.length>MAX_EVENT)invalidate(entry,'event_bound');
    };
    try{
      while(active(entry)){
        const chunk=await reader.read();
        if(!active(entry))break;
        if(chunk.done){buffer+=decoder.decode();drain(true);break;}
        bytes+=chunk.value.byteLength;
        if(bytes>MAX_BYTES){invalidate(entry,'response_bound');break;}
        buffer+=decoder.decode(chunk.value,{stream:true});drain(false);
        if(entry.ack&&entry.conversation_id)break;
      }
    }catch{if(active(entry))invalidate(entry,'response_read_error');}
    finally{stopReader(entry);}
  };
  const requestBody=(entry,text)=>{
    if(!active(entry))return false;
    let body;try{body=JSON.parse(text);}catch{invalidate(entry,'malformed_request');return false;}
    const messages=body?.messages;
    if(!Array.isArray(messages)||messages.length!==1){invalidate(entry,'ambiguous_messages');return false;}
    const message=messages[0],parts=message?.content?.parts;
    if(message?.author?.role!=='user'||typeof message.id!=='string'
        ||!/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(message.id)
        ||!Array.isArray(parts)||!parts.length||!parts.every(part=>typeof part==='string')||parts.join('')!==entry.prompt){
      invalidate(entry,'submission_mismatch');return false;
    }
    if(entry.expected_conversation&&body.conversation_id!==entry.expected_conversation){invalidate(entry,'request_conversation_mismatch');return false;}
    if(!entry.expected_conversation&&body.conversation_id){invalidate(entry,'unexpected_request_conversation');return false;}
    if(!bind(entry,body.conversation_id))return false;
    entry.message_id=message.id;return true;
  };
  window.fetch=function(input,init){
    const entry=current;let eligible=false,requestInput=false;
    try{
      requestInput=typeof Request!=='undefined'&&input instanceof Request;
      const url=new URL(requestInput?input.url:String(input),location.href);
      const method=String(init?.method??(requestInput?input.method:'GET')).toUpperCase();
      const path=url.pathname.toLowerCase().replace(/\/+$/,'');
      eligible=active(entry)&&method==='POST'&&url.origin===location.origin
        &&(path==='/backend-api/conversation'||path==='/backend-api/f/conversation');
    }catch{}
    let bodyText;
    if(eligible){
      entry.released=true;
      if(entry.request_id)invalidate(entry,'duplicate_request');
      else {
        entry.request_id=entry.generation+':'+(++sequence);
        try{
          bodyText=init&&Object.hasOwn(init,'body')?init.body:requestInput?input.clone().text():null;
          if(typeof bodyText!=='string'&&!(bodyText&&typeof bodyText.then==='function'))invalidate(entry,'request_body_unavailable');
        }catch{invalidate(entry,'request_body_unavailable');}
      }
    }
    // No request reconstruction, mutation, additional fetch, or streaming delay here.
    let result;
    try{result=Reflect.apply(downstreamFetch,this,[input,init]);}
    catch(error){if(eligible)invalidate(entry,'fetch_failed');throw error;}
    if(!eligible)return result;
    // Capture outgoing identity independently of response headers. This is only
    // correlation metadata; acknowledgement still requires a semantic response.
    const bodyObserved=Promise.resolve(bodyText).then(text=>requestBody(entry,text))
      .catch(()=>{if(active(entry))invalidate(entry,'observer_error');return false;});
    return Promise.resolve(result).then(response=>{
      void bodyObserved.then(valid=>{
        if(valid)return inspectResponse(entry,response);
      }).catch(()=>{if(active(entry))invalidate(entry,'observer_error');});
      return response;
    },error=>{invalidate(entry,'fetch_failed');throw error;});
  };
  window.__selfrunReceiptObserver={version,
    arm:key=>{
      if(typeof key?.intent_id!=='string'||!key.intent_id||typeof key.generation!=='string'||!key.generation
          ||typeof key.prompt!=='string'||!key.prompt||key.conversation_id&&!canonical(key.conversation_id))throw new Error('RECEIPT_INTENT_INVALID');
      if(same(current,key)&&!current.retired){
        if(current.prompt!==key.prompt||current.expected_conversation!==(key.conversation_id||''))throw new Error('RECEIPT_INTENT_CHANGED');
        return true;
      }
      retire(current);
      current={intent_id:key.intent_id,generation:key.generation,prompt:key.prompt,
        expected_conversation:key.conversation_id||'',conversation_id:key.conversation_id||'',
        request_id:null,message_id:null,ack:null,invalid:null,released:false,retired:false,reader:null,timer:null};
      return true;
    },
    snapshot:key=>same(current,key)&&!current.retired&&(key.prompt===undefined||key.prompt===current.prompt)?{
      version:1,intent_id:current.intent_id,generation:current.generation,request_id:current.request_id,
      message_id:current.message_id,conversation_id:current.conversation_id,ack:current.ack,
      invalid:current.invalid,released:current.released,
    }:null,
    retire:key=>{if(same(current,key))retire(current);return true;},
  };
  return true;
}

export function receiptObserverDocumentStartSource() {
  return '('+installReceiptObserver.toString()+')()';
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

  async prepare({ projectUrl, prompt, signal, onTarget, guard=()=>true }) {
    const target = await this.chromium.createTarget('about:blank');
    let session;
    try {
      await onTarget?.(target);
      if(signal?.aborted)throw signal.reason||new Error('aborted');
      session = await this.chromium.connectTarget(target);
      await session.call('Page.enable');
      await session.call('Runtime.enable');
      await session.call('Network.enable');
      await applyBrowserClientProfile(session,this.config);
      await installDecorativeAnimationPause(session);
      await installRequestProfileEngine(session);
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
      session?.close();
      await this.chromium.closeTarget(target.id,{guard});
      throw error;
    }
  }

  async resume({ conversationUrl, signal, onTarget, guard=()=>true }) {
    const expectedUrl = canonicalUrl(conversationUrl);
    if (!expectedUrl) throw new Error('canonical conversation URL unavailable for resume');

    const targets=await this.chromium.listExistingTargets();
    const existing=targets?.find(t=>t.type==='page'&&!this.chromium.targetClosing?.(t.id)&&canonicalUrl(t.url)===expectedUrl);
    const target = existing || await this.chromium.createTarget(conversationUrl);
    let session;
    try {
      await onTarget?.(target);
      if(signal?.aborted)throw signal.reason||new Error('aborted');
      session = await this.chromium.connectTarget(target);
      await session.call('Page.enable');
      await session.call('Runtime.enable');
      await session.call('Network.enable');
      await applyBrowserClientProfile(session,this.config);
      await installDecorativeAnimationPause(session);
      await installRequestProfileEngine(session);
      const probe = await this.#waitFor(session, (p) => p.loginPage
        || ((p.readyState === 'complete' || p.readyState === 'interactive')
          && canonicalUrl(p.url) === expectedUrl) || !!p.errorText, {
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
      session?.close();
      if(!existing) await this.chromium.closeTarget(target.id,{guard});
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
        if(signal?.aborted||!guard())throw signal?.reason||new Error('stale submission');
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
      // The canonical route is useful ownership metadata, never an acceptance signal.
      // Semantic receipt readback does not wait for a user/assistant DOM bubble.
      return routeProbe;
    } catch(error) {
      if(clickReleased)error.released=true;
      throw error;
    }
  }

  async livenessSnapshot({ session, signal }) {
    if (signal?.aborted) throw signal.reason || new Error('aborted');
    return evaluate(session, probeExpression());
  }

  async recoveryInputSnapshot({ session, signal }) {
    if (signal?.aborted) throw signal.reason || new Error('aborted');
    return evaluate(session, recoveryInputExpression());
  }

  async attachTarget({targetId,signal,onTarget}) {
    const targets=await this.chromium.listExistingTargets();
    const target=targets?.find(t=>t.id===targetId&&t.type==='page'&&!this.chromium.targetClosing?.(t.id));
    if(!target)throw Object.assign(new Error('original submission target unavailable'),{code:'ATTACH_FAILED'});
    await onTarget?.(target);
    if(signal?.aborted)throw signal.reason||new Error('aborted');
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
    const probe=await evaluate(session,kind==='recovery'?recoveryInputExpression():probeExpression());
    if(conversationUrl&&conversationId(probe.url)!==conversationId(conversationUrl))
      throw new Error('submission conversation ownership mismatch');
    if(kind==='initial'&&conversationId(probe.url))throw new Error('initial submission cannot target an existing conversation');
    await evaluate(session,requestProfileDocumentStartSource());
    await evaluate(session,configureRequestProfileExpression(profileOperations));
    await evaluate(session,receiptObserverDocumentStartSource());
    const staged=await evaluate(session,inputExpression(prompt));
    if(staged?.status!=='READY')throw new Error('submission composer not ready');
    if(signal?.aborted||!guard())throw new Error('stale submission');
    const key={intent_id:intent?.id,generation:intent?.receipt_generation,prompt,conversation_id:conversationId(conversationUrl)};
    if(intent?.prompt!==prompt)throw new Error('submission immutable prompt mismatch');
    await evaluate(session,'window.__selfrunReceiptObserver.arm('+JSON.stringify(key)+')');
    try {
      return await this.#submitWithProfile({session,prompt,signal,guard,conversationUrl,baseline:intent?.baseline||{},onConversation});
    } finally {
      if(signal?.aborted||!guard()) {
        // Retire this exact arm only. A late old cleanup cannot retire its successor.
        try{await evaluate(session,'window.__selfrunReceiptObserver?.retire('+JSON.stringify(key)+')');}catch{}
      }
    }
  }

  async readSubmission({session,intent,prompt,conversationUrl,signal,guard=()=>true,onRequest}) {
    const check=()=>{if(signal?.aborted||!guard())throw signal?.reason||new Error('stale submission readback');};
    check();
    const key={intent_id:intent?.id,generation:intent?.receipt_generation,prompt};
    const validKey=typeof key.intent_id==='string'&&!!key.intent_id&&typeof key.generation==='string'&&!!key.generation&&intent.prompt===prompt;
    const expected=conversationId(conversationUrl);
    const belongs=receipt=>validKey&&receipt?.version===1&&receipt.intent_id===key.intent_id&&receipt.generation===key.generation;
    const metadata=receipt=>({version:1,intent_id:receipt.intent_id,generation:receipt.generation,
      request_id:receipt.request_id||null,message_id:receipt.message_id||null,conversation_id:receipt.conversation_id||'',
      ack:receipt.ack||null,invalid:receipt.invalid||null,released:receipt.released===true});
    const positive=receipt=>belongs(receipt)&&receipt.released===true&&!receipt.invalid
      &&['cot_token','user_visible_token','final_channel_token','assistant_message'].includes(receipt.ack)
      &&typeof receipt.message_id==='string'&&/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(receipt.message_id)
      &&typeof receipt.request_id==='string'&&receipt.request_id.startsWith(key.generation+':')
      &&/^\d+$/.test(receipt.request_id.slice(key.generation.length+1))
      &&(!intent.message_id||intent.message_id===receipt.message_id)
      &&(!intent.receipt?.request_id||intent.receipt.request_id===receipt.request_id)
      &&!!receipt.conversation_id&&conversationId('https://chatgpt.com/c/'+receipt.conversation_id)===receipt.conversation_id
      &&(!expected||receipt.conversation_id===expected);
    let durable=belongs(intent?.receipt)?metadata(intent.receipt):null;
    let probe=null;
    const started=Date.now(),timeout=Math.min(SUBMIT_POST_GRACE_MS,Number(this.config.navigationTimeoutMs)||SUBMIT_POST_GRACE_MS);
    do {
      check();
      try{probe=await evaluate(session,probeExpression());}catch(error){check();return {state:'UNKNOWN',probe};}
      check();
      if(probe?.loginPage||(expected&&conversationId(probe?.url)!==expected))return {state:'UNKNOWN',probe};
      let observed=null;
      if(validKey)try{observed=await evaluate(session,'window.__selfrunReceiptObserver?.snapshot('+JSON.stringify(key)+')||null');}catch{check();}
      check();
      // A current-page conflict must never fall back to an older saved positive receipt.
      const receipt=observed===null?durable:belongs(observed)?metadata(observed):null;
      if(receipt&&((intent.message_id&&receipt.message_id&&intent.message_id!==receipt.message_id)
          ||(intent.receipt?.request_id&&receipt.request_id&&intent.receipt.request_id!==receipt.request_id)))return {state:'UNKNOWN',probe};
      if(receipt&&onRequest&&JSON.stringify(receipt)!==JSON.stringify(durable)) {
        await onRequest({message_id:receipt.message_id,released:receipt.released,receipt});
        check();durable=receipt;
      }
      if(positive(receipt)&&durable&&JSON.stringify(receipt)===JSON.stringify(durable)
          &&conversationId(probe?.url)===receipt.conversation_id) {
        check();return {state:'CONFIRMED',probe,messageId:receipt.message_id};
      }
      // Only a previously proven pre-release interception abort permits a retry.
      if(intent.absence_proof==='INTERCEPTED_REQUEST_ABORTED'&&!intent.released&&!receipt?.released)
        return {state:'ABSENT',probe};
      if(!receipt||receipt.invalid||!onRequest&&JSON.stringify(receipt)!==JSON.stringify(durable))break;
      if(Date.now()-started>=timeout)break;
      await delay(Math.min(100,Math.max(1,timeout-(Date.now()-started))),signal);
    }while(Date.now()-started<=timeout);
    return {state:'UNKNOWN',probe};
  }

  async quiesce({session,conversationUrl,signal,guard}) {
    const before=await this.recoveryInputSnapshot({session,signal});
    if(conversationId(before.url)!==conversationId(conversationUrl))throw new Error('quiesce conversation mismatch');
    if(!before.streaming&&!before.stopButtonVisible)return before;
    if(signal?.aborted||!guard())throw new Error('stale stream cleanup');
    await evaluate(session,'(() => {'+
      'const buttons=[...document.querySelectorAll("button")].filter(e=>e.isConnected&&e.offsetParent!==null);'+
      'const stop=buttons.find(b=>b.dataset.testid==="stop-button"||/stop|중지/i.test((b.getAttribute("aria-label")||"")+" "+(b.title||"")));'+
      'if(stop)stop.click();return !!stop;})()');
    const started=Date.now();
    do {
      if(signal?.aborted||!guard())throw signal?.reason||new Error('stale stream cleanup');
      const current=await this.recoveryInputSnapshot({session,signal});
      if(conversationId(current.url)!==conversationId(conversationUrl))throw new Error('quiesce conversation mismatch');
      if(!current.streaming&&!current.stopButtonVisible)return current;
      await delay(250,signal);
    } while(Date.now()-started<this.config.navigationTimeoutMs);
    throw new Error('Timed out waiting for stuck stream cleanup');
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
      const observedCursor=cursor(probe),previousCursor=cursor(previous);
      const progressDetected =
        observedCursor.assistantCount !== previousCursor.assistantCount ||
        observedCursor.assistantTextLength !== previousCursor.assistantTextLength ||
        observedCursor.assistantMessageId !== previousCursor.assistantMessageId ||
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

      // A live page with temporarily missing DOM is not a lost target.
      const pageError = probe.loginPage ? 'ChatGPT browser profile requires sign-in' : conversationAvailable ? null : probe.errorText;
      let status = conversationStateUnavailable ? 'OBSERVATION_UNKNOWN' : 'RUNNING';
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
          responseAssistantCount: probe.responseAssistantCount,
          responseAssistantTextLength: probe.responseAssistantTextLength,
          responseAssistantMessageId: probe.responseAssistantMessageId,
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
