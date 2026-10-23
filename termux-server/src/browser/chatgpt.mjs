import {nativePreferenceExpression,nativePreferenceHomeUrl,nativeProfileFromOperations} from './native-profile-preparation.mjs';
import { canonicalUrl, cursor } from '../lifecycle.mjs';
import { isActionNotSubmitted, actionNotSubmittedStage } from './server-action-errors.mjs';

// Local call-stack evidence only. Page errors and imported Drive records cannot
// manufacture proof that this invocation never reached the submit operation.
const preSubmitFailures = new WeakMap();
export const preSubmitFailureStage = error => error && typeof error === 'object'
  ? preSubmitFailures.get(error) || null : null;

// Address observation is scoped by the caller's exact owned target, not by the
// requested project's display name, route prefix, or submission receipt.
export function observedConversationUrl(value) {
  try {
    const to=new URL(value);
    if(to.origin!=='https://chatgpt.com'||to.username||to.password)return '';
    return /^(?:\/g\/[^/]+)?\/c\/[A-Za-z0-9_-]{1,160}\/?$/.test(to.pathname)?to.href:'';
  } catch {return '';}
}

// Optimistic WEB routes are the same owned tab's pending initial navigation.
// Their age cannot establish failure; a different optimistic route is not adopted.
export function pendingConversationTransition(fromUrl,value) {
  try {
    const from=new URL(fromUrl),to=new URL(value);
    if(from.origin!=='https://chatgpt.com'||to.origin!==from.origin||from.username||from.password||to.username||to.password)return false;
    const pending=u=>/^\/(?:g\/[^/]+\/)?c\/WEB:[0-9a-f-]{36}\/?$/i.test(u.pathname);
    if(!pending(to))return false;
    if(pending(from))return from.pathname===to.pathname;
    return from.pathname==='/'||/^\/g\/[^/]+(?:\/project)?\/?$/.test(from.pathname);
  } catch{return false;}
}

// Action ownership follows the first conversation on the already owned tab.
// Once bound to a conversation, another conversation cannot inherit its input
// authority. Route aliases of that same conversation are harmless.
export function actionConversationUrl(fromUrl,value) {
  try {
    const from=new URL(fromUrl),to=observedConversationUrl(value);
    if(!to||from.origin!=='https://chatgpt.com'||from.username||from.password)return '';
    const previous=observedConversationUrl(fromUrl);
    if(previous)return new URL(previous).pathname.split('/c/')[1].replace(/\/$/,'')===new URL(to).pathname.split('/c/')[1].replace(/\/$/,'')?to:'';
    const entry=from.pathname.replace(/\/$/,'');
    return entry===''||/^\/g\/[^/]+(?:\/project)?$/.test(entry)||/^\/(?:g\/[^/]+\/)?c\/WEB:[0-9a-f-]{36}$/.test(entry)?to:'';
  } catch {return '';}
}

export function normalizeProgressText(value) {
  return String(value || '')
    .replace(/(?:Today|Yesterday|오늘|어제)\s+\d{1,2}:\d{2}(?::\d{2})?\s*(?:AM|PM|오전|오후)?/gi, ' ')
    .replace(/(?:Worked|Working|Thought|Thinking)\s+for\s+\d+(?:\.\d+)?\s*(?:seconds?|minutes?|hours?|s|m|h)(?:\s+\d+(?:\.\d+)?\s*(?:seconds?|minutes?|hours?|s|m|h))*/gi, ' ')
    .replace(/(?:You said:|ChatGPT said:)/gi, ' ')
    .replace(/\s+/g, ' ').trim();
}

const RUNTIME_EVALUATE_TIMEOUT_MS = 5000;
const OBSERVATION_DEADLINE_MS = 75000;
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

async function evaluate(session, expression, timeoutMs = RUNTIME_EVALUATE_TIMEOUT_MS, options = {}) {
  const result = await session.call('Runtime.evaluate', {
    expression,
    returnByValue: true,
    awaitPromise: true,
  }, { timeoutMs, ...options });
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

export function recoveryInputExpression() {
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

function newResponseAfterInput(intent, probe) {
  const before=intent?.baseline;
  if(!before||!(Number(probe?.responseTextLength)>0)||!probe.responseFingerprint)return false;
  // An initial empty conversation can show thinking/tool content before either
  // message bubble exists. The probe already excludes user and older turns.
  if(Number(before.userCount)===0&&Number(before.assistantCount)===0
      &&Number(before.responseTextLength)===0&&!before.responseTurnId)return true;
  const user=probe.userMessageId;
  if(!user||user===before.userMessageId)return false;
  // Recovery uses a response-only cursor. An absent prior user ID is unknown,
  // not proof that the currently displayed user belongs to this input.
  if(!before.userMessageId&&!intent.message_id)return false;
  if(intent.message_id&&user!==intent.message_id&&user!=='data-message-id:'+intent.message_id)return false;
  return (!!probe.responseTurnId&&probe.responseTurnId!==before.responseTurnId)
    ||probe.responseFingerprint!==before.responseFingerprint;
}


export const DECORATIVE_ANIMATION_PAUSE_SOURCE = "(() => {" +
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

export function probeExpression() {
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

export function newChatExpression() {
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

export function inputExpression(prompt) {
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

export function sendReadyExpression() {
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

export function submitExpression() {
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
  // Retain the input/receipt lifecycle facade; native settings own model selection.
  return `(()=>{
    if(window.__selfrunRequestProfileEngine?.version===${version})return true;
    const state={operations:null,last:{ok:false,reason:'not_configured'}};
    let disposed=false;
    window.__selfrunRequestProfileEngine={version:${version},
      configure:operations=>{if(disposed)throw new Error('REQUEST_PROFILE:runtime_disposed');state.operations=operations;state.last={ok:true,reason:'target_ready'};return true;},
      diagnostics:()=>({...state.last}),
      dispose:()=>{disposed=true;state.operations=null;state.last={ok:false,reason:'disposed'};return true;}
    };
    return true;
  })()`;
}

export function configureRequestProfileExpression(operations) {
  const normalized=JSON.stringify(normalizeProfileOperations(operations));
  const version=JSON.stringify(REQUEST_PROFILE_ENGINE_VERSION);
  return `(()=>{const engine=window.__selfrunRequestProfileEngine;if(!engine||engine.version!==${version}||typeof engine.configure!=='function')throw new Error('REQUEST_PROFILE_ENGINE_UNAVAILABLE');engine.configure(${normalized});return engine.diagnostics?.()||{ok:true};})()`;
}

async function installRequestProfileEngine(session) {
  const source=requestProfileDocumentStartSource();
  await session.call('Page.addScriptToEvaluateOnNewDocument',{source});
  await evaluate(session,source);
}

// Receipt-only observer; native preferences own the model and effort values.
// Event vocabulary/framing follows Android ChatGptTurnProtocolScript (436da865).
function installReceiptObserver() {
  const version='selfrun-passive-receipt-v2';
  let previousObserver=window.__selfrunReceiptObserver;
  if(previousObserver?.version===version)return true;
  const downstreamFetch=window.fetch;
  const MAX_BYTES=262144,MAX_EVENT=65536,MAX_MS=30000;
  let current=null,sequence=0;
  let disposed=false;
  const canonical=value=>typeof value==='string'&&/^[A-Za-z0-9_-]{1,160}$/.test(value)
    &&!value.toLowerCase().startsWith('local-chatgpt')?value:'';
  const same=(entry,key)=>!!entry&&entry.intent_id===key?.intent_id&&entry.generation===key?.generation;
  const active=entry=>!!entry&&current===entry&&!entry.retired&&!entry.invalid;
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
  const retire=entry=>{
    if(!entry)return;
    if(current===entry)current=null;
    entry.retired=true;stopReader(entry);
    // Pending response callbacks may retain this object. Release our data even
    // when the original response or request-body promise is still outstanding.
    for(const name of Object.keys(entry))entry[name]=null;
    entry.retired=true;
  };
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
    entry.http_status=Number.isInteger(response?.status)&&response.status>=100&&response.status<=599?response.status:null;
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
      if(eligible)entry.endpoint_category=path==='/backend-api/conversation'?'conversation':'f_conversation';
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
      if(disposed)throw new Error('RECEIPT_OBSERVER_DISPOSED');
      if(typeof key?.intent_id!=='string'||!key.intent_id||typeof key.generation!=='string'||!key.generation
          ||typeof key.prompt!=='string'||!key.prompt||key.conversation_id&&!canonical(key.conversation_id))throw new Error('RECEIPT_INTENT_INVALID');
      // A legacy wrapper remains passive; retire its matching arm before replacing it.
      previousObserver?.retire?.(key);
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
      invalid:current.invalid,released:current.released,http_status:current.http_status??null,endpoint_category:current.endpoint_category??null,
    }:null,
    retire:key=>{if(same(current,key))retire(current);return true;},
    dispose:()=>{disposed=true;retire(current);previousObserver=null;sequence=0;return true;},
  };
  return true;
}

export function receiptObserverDocumentStartSource() {
  return '('+installReceiptObserver.toString()+')()';
}


export const QUIESCE_EXPRESSION = '(() => {'+
  'const buttons=[...document.querySelectorAll("button")].filter(e=>e.isConnected&&e.offsetParent!==null);'+
  'const stop=buttons.find(b=>b.dataset.testid==="stop-button"||/stop|중지/i.test((b.getAttribute("aria-label")||"")+" "+(b.title||"")));'+
  'if(stop)stop.click();return !!stop;})()';

export function pageActionExpression(operation,payload={}) {
  switch(operation) {
    case 'snapshot': return probeExpression();
    case 'progress': return `(() => { const started = performance.now(); const value = (${probeExpression()});
      return {...value, __selfrun_engine_elapsed_ms: Math.max(0, Math.round(performance.now() - started))}; })()`;
    case 'prepareInput': return 'true';
    case 'prepareProfile': return nativePreferenceExpression(payload.operations);
    case 'recoveryInput': return recoveryInputExpression();
    case 'newChat': return newChatExpression();
    case 'input': return inputExpression(payload.prompt);
    case 'sendReady': return sendReadyExpression();
    case 'submit': return submitExpression();
    case 'installProfile': return requestProfileDocumentStartSource();
    case 'profileConfigure': return configureRequestProfileExpression(payload.operations);
    case 'installReceipt': return receiptObserverDocumentStartSource();
    case 'receiptArm': return 'window.__selfrunReceiptObserver.arm('+JSON.stringify(payload.key)+')';
    case 'receiptSnapshot': return 'window.__selfrunReceiptObserver?.snapshot('+JSON.stringify(payload.key)+')||null';
    case 'receiptRetire': return 'window.__selfrunReceiptObserver?.retire('+JSON.stringify(payload.key)+')';
    case 'pauseAnimations': return DECORATIVE_ANIMATION_PAUSE_SOURCE;
    case 'quiesce': return QUIESCE_EXPRESSION;
    default: throw Object.assign(new Error('Unsupported page operation'),{code:'PAGE_OPERATION_UNSUPPORTED'});
  }
}

async function runPageAction(session,operation,payload={},timeoutMs=RUNTIME_EVALUATE_TIMEOUT_MS,options={}) {
  if(session?.transport==='extension') {
    if(typeof session.run!=='function')throw new Error('Extension page session unavailable');
    return session.run(operation,payload,{timeoutMs,...options});
  }
  return evaluate(session,pageActionExpression(operation,payload),timeoutMs,options);
}

async function initializePageSession(session,config,{network=true,pause=true}={}) {
  if(session?.transport==='extension') {
    return session.run('initialize',{clientProfile:String(config?.browserClientProfile||'native')});
  }
  await session.call('Page.enable');
  await session.call('Runtime.enable');
  if(network)await session.call('Network.enable');
  await applyBrowserClientProfile(session,config);
  if(pause)await installDecorativeAnimationPause(session);
  await installRequestProfileEngine(session);
}

async function navigatePageSession(session,url) {
  if(session?.transport==='extension')return session.run('navigate',{url});
  return session.call('Page.navigate',{url});
}

export class ChatGptBrowser {
  constructor(chromium, config) {
    this.chromium = chromium;
    this.config = config;
    this.observations = new WeakMap();
  }

  async #observeProgress(session, signal, generation = null) {
    if (signal?.aborted) throw signal.reason || new Error('aborted');
    let pending = this.observations.get(session);
    if (pending && (pending.signal !== signal || pending.generation !== generation)) {
      pending.abort.abort(Object.assign(new Error('observation generation replaced'), { code: 'OBSERVATION_SUPERSEDED' }));
      pending = null;
    }
    if (!pending) {
      const abort = new AbortController();
      const combined = signal ? AbortSignal.any([signal, abort.signal]) : abort.signal;
      pending = { signal, abort, generation };
      pending.promise = runPageAction(session, 'progress', {}, OBSERVATION_DEADLINE_MS,
        { signal: combined, queryLabel: 'progress' }).then(value => {
          if (combined.aborted) throw combined.reason || new Error('aborted');
          if (value) delete value.__selfrun_engine_elapsed_ms;
          return value;
        }).finally(() => {
          if (this.observations.get(session) === pending) this.observations.delete(session);
        });
      this.observations.set(session, pending);
    }
    return pending.promise;
  }

  async prepare({ projectUrl, profileOperations=[], signal, onTarget, guard=()=>true, ownerTaskId=null }) {
    const check=()=>{if(signal?.aborted||!guard())throw signal?.reason||new Error('stale preparation');};
    check();
    // Every app-issued turn or retry starts with a new browser tab.
    let target;
    try{target=await this.chromium.createTarget('about:blank',{signal,guard,ownerTaskId});}
    catch(error){if(error.createdTarget)await onTarget?.(error.createdTarget);throw error;}
    let session,acquired=false;
    try {
      await onTarget?.(target);acquired=true;check();
      await this.chromium.acknowledgeTarget?.(target.id);check();
      session=await this.chromium.connectTarget(target);check();
      await initializePageSession(session,this.config);check();
      if(nativeProfileFromOperations(profileOperations).model) {
        await navigatePageSession(session,nativePreferenceHomeUrl(profileOperations));check();
        // Wait for the seed page to load. No model/effort readback is performed.
        let ready=false;
        while(!ready) {
          check();
          try {ready=!!(await runPageAction(session,'recoveryInput',{},undefined,{signal}))?.composer;}
          catch(error) {if(!/context|navigat|repurposed/i.test(String(error?.message||error)))throw error;}
          if(!ready)await delay(100,signal);
        }
        await runPageAction(session,'prepareProfile',{operations:profileOperations},30000,{signal});check();
      }
      await navigatePageSession(session,projectUrl);check();
      return {target,session,baseline:{}};
    } catch(error) {
      session?.close();
      if(!acquired)await this.chromium.closeTarget(target.id,{guard});
      throw error;
    }
  }

  async resume({ conversationUrl, signal, onTarget, guard=()=>true, automaticRecovery=false, previousTargetId=null, ownerTaskId=null }) {
    const expectedUrl = canonicalUrl(conversationUrl);
    if (!expectedUrl) throw new Error('canonical conversation URL unavailable for resume');

    const targets=await this.chromium.listExistingTargets();
    if(this.chromium.recoveryNavigationDisabled&&!Array.isArray(targets))
      throw Object.assign(new Error('Existing target inventory unavailable; no replacement navigation allowed'),{code:'PAGE_UNAVAILABLE'});
    const existing=targets?.find(t=>t.type==='page'&&!this.chromium.targetClosing?.(t.id)&&canonicalUrl(t.url)===expectedUrl);
    if(this.chromium.recoveryNavigationDisabled&&previousTargetId&&targets.some(t=>t.id===previousTargetId)&&existing?.id!==previousTargetId)
      throw Object.assign(new Error('Previous owned entry target is not safely disposed; no replacement navigation allowed'),{code:'PAGE_UNAVAILABLE'});
    if(!existing&&automaticRecovery&&this.chromium.recoveryNavigationDisabled)
      throw Object.assign(new Error('Existing conversation page unavailable; automatic navigation is disabled'),{code:'PAGE_UNAVAILABLE'});
    let target=existing;
    if(!target)try{target=await this.chromium.createTarget(conversationUrl,{signal,guard,ownerTaskId});}
    catch(error){if(error.createdTarget)await onTarget?.(error.createdTarget);throw error;}
    let session;
    try {
      await onTarget?.(target);
      if(signal?.aborted||!guard())throw signal?.reason||new Error('stale attachment');
      await this.chromium.acknowledgeTarget?.(target.id);
      session = await this.chromium.connectTarget(target);
      await initializePageSession(session,this.config);
      if(signal?.aborted||!guard())throw signal?.reason||new Error('stale attachment');
      // DOM availability is observation, not permission to start observing an
      // already identified conversation. The monitor owns the first sample.
      return {target,session,baseline:{}};
    } catch (error) {
      session?.close();
      if(!existing) await this.chromium.closeTarget(target.id,{guard});
      throw error;
    }
  }

  async #submitWithProfile({ session, prompt, signal, initialProjectUrl, handoff, guard = () => true, onSubmitStart = () => {}, onSubmitted = null }) {
    if (signal?.aborted) throw signal.reason || new Error('aborted');

    let clickReleased=false;
    try {
      try {
        if(signal?.aborted||!guard())throw signal?.reason||new Error('stale submission');
        onSubmitStart();
        const submitted=await runPageAction(session,'submit',{initialProjectUrl});
        if(submitted?.status==='CONVERSATION_OBSERVED'){const observed=await handoff(submitted.url);if(observed)return {...observed,noSubmitEffect:true};}
        if(submitted?.status!=='SUBMITTED')throw new Error(`Composer send failed: ${submitted?.status||'unknown'}`);
        clickReleased=true;
      } catch(error) {
        if(!runtimeEvaluateTimedOut(error))throw error;
        clickReleased=true;
      }
      if(signal?.aborted||!guard())throw new Error('stale submission');
      await onSubmitted?.();
      return {submitted:true};
    } catch(error) {
      if(clickReleased)error.released=true;
      throw error;
    }
  }

  async livenessSnapshot({ session, signal }) {
    if (signal?.aborted) throw signal.reason || new Error('aborted');
    return runPageAction(session, 'snapshot');
  }

  async recoveryInputSnapshot({ session, signal }) {
    if (signal?.aborted) throw signal.reason || new Error('aborted');
    return runPageAction(session, 'recoveryInput', {}, RUNTIME_EVALUATE_TIMEOUT_MS, {signal});
  }

  async attachTarget({targetId,signal,onTarget,observationOnly=false}) {
    const targets=await this.chromium.listExistingTargets();
    const target=targets?.find(t=>t.id===targetId&&t.type==='page'&&!this.chromium.targetClosing?.(t.id));
    if(!target)throw Object.assign(new Error('original submission target unavailable'),{code:'ATTACH_FAILED'});
    await onTarget?.(target);
    if(signal?.aborted)throw signal.reason||new Error('aborted');
    const session=await this.chromium.connectTarget(target);
    if(!observationOnly)await initializePageSession(session,this.config,{network:false,pause:false});
    if(signal?.aborted){session.close();throw signal.reason;}
    return {target,session,baseline:{}};
  }

  async ownedConversationUrl({targetId,projectUrl,signal,guard=()=>true}) {
    const check=()=>{if(signal?.aborted||!guard())throw signal?.reason||new Error('stale conversation lookup');};
    check();
    if(!targetId||!this.chromium?.listExistingTargets)return '';
    let targets;try{targets=await this.chromium.listExistingTargets();}catch{check();return '';}
    check();
    const target=targets?.find(t=>t.id===targetId&&t.type==='page'&&!t.closing);
    return target?observedConversationUrl(target.url):'';
  }

  async submitIntent({session,prompt,kind,intent,conversationUrl,projectUrl,targetId,profileOperations,signal,guard,observationGuard=guard,onRequest,onSubmitted,onConversation,resumeSubmit=false}) {
    let stage='recoveryInput',submitCalled=false;
    const check=()=>{if(signal?.aborted||!guard())throw signal?.reason||new Error('stale submission');};
    const onStage=value=>{check();stage=value;};
    const observeCheck=()=>{if(signal?.aborted||!observationGuard())throw signal?.reason||new Error('stale conversation observation');};
    const handoff=async value=>{const url=observedConversationUrl(value);if(kind!=='initial'||!url)return null;observeCheck();await onConversation?.({url});observeCheck();return {submitted:false,observationOnly:true,noSubmitEffect:!submitCalled,url};};
    const findConversation=async()=>kind==='initial'?handoff(await this.ownedConversationUrl({targetId,signal,guard:observationGuard})):null;
    const run=async(operation,payload={})=>{onStage(operation);const value=await runPageAction(session,operation,payload,undefined,{signal});check();return value;};
    try {
    const existing=await findConversation();if(existing)return existing;
    check();
    await run('prepareInput',{prompt,operations:profileOperations});
    const probe=await run('recoveryInput');
    const observed=await handoff(probe?.url);if(observed)return observed;
    if(conversationUrl&&conversationId(probe.url)!==conversationId(conversationUrl))
      throw new Error('submission conversation ownership mismatch');
    if(kind==='initial'&&conversationId(probe.url))throw new Error('initial submission cannot target an existing conversation');
    if(!resumeSubmit){
    await run('installProfile');
    await run('profileConfigure',{operations:profileOperations});
    await run('installReceipt');
    const staged=await run('input',{prompt,...(kind==='initial'?{initialProjectUrl:projectUrl}: {})});
    if(staged?.status==='CONVERSATION_OBSERVED'){const observed=await handoff(staged.url);if(observed)return observed;}
    if(staged?.status!=='READY')throw new Error('submission composer not ready: '+(staged?.status||'UNKNOWN'));
    }
    if(signal?.aborted||!guard())throw new Error('stale submission');
    const key={intent_id:intent?.id,generation:intent?.receipt_generation,prompt,conversation_id:conversationId(conversationUrl)};
    if(intent?.prompt!==prompt)throw new Error('submission immutable prompt mismatch');
    await run('receiptArm',{key});
    try {
      const submitted=await this.#submitWithProfile({session,prompt,signal,guard,initialProjectUrl:kind==='initial'?projectUrl:null,handoff,
        onSubmitStart:()=>{check();submitCalled=true;},onSubmitted});
      if(!submitted?.observationOnly)await onRequest?.({released:true});
      return submitted;
    } finally {
      if(signal?.aborted||!guard()) {
        // Retire this exact arm only. A late old cleanup cannot retire its successor.
        try{await runPageAction(session,'receiptRetire',{key});}catch{}
      }
    }
    } catch(error) {
      if(!signal?.aborted&&observationGuard()){const observed=await findConversation();if(observed)return observed;}
      if(isActionNotSubmitted(error))preSubmitFailures.set(error,actionNotSubmittedStage(error));
      else if(!submitCalled&&error&&typeof error==='object')preSubmitFailures.set(error,stage);
      throw error;
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
      ack:receipt.ack||null,invalid:receipt.invalid||null,released:receipt.released===true,
      http_status:Number.isInteger(receipt.http_status)&&receipt.http_status>=100&&receipt.http_status<=599?receipt.http_status:null,
      endpoint_category:['conversation','f_conversation'].includes(receipt.endpoint_category)?receipt.endpoint_category:null});
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
      // These are independent reads. A delayed progress query must not prevent
      // receipt collection, and a delayed receipt must not stop observation.
      const observing=this.#observeProgress(session,signal,key.generation||null)
        .then(value=>({value}),error=>({error}));
      let observed=null;
      if(validKey)try{observed=await runPageAction(session,'receiptSnapshot',{key},RUNTIME_EVALUATE_TIMEOUT_MS,{signal});}catch{check();}
      const observation=await observing;
      if(!observation.error)probe=observation.value;
      check();
      if(!observation.error&&(probe?.loginPage||(expected&&conversationId(probe?.url)!==expected)))return {state:'UNKNOWN',probe};
      // A current-page conflict must never fall back to an older saved positive receipt.
      const receipt=observed===null?durable:belongs(observed)?metadata(observed):null;
      if(receipt&&((intent.message_id&&receipt.message_id&&intent.message_id!==receipt.message_id)
          ||(intent.receipt?.request_id&&receipt.request_id&&intent.receipt.request_id!==receipt.request_id)
          ||(expected&&receipt.conversation_id&&receipt.conversation_id!==expected)))return {state:'UNKNOWN',probe};
      if(receipt&&onRequest&&JSON.stringify(receipt)!==JSON.stringify(durable)) {
        await onRequest({message_id:receipt.message_id,released:receipt.released,receipt});
        check();durable=receipt;
      }
      // An independent observation failure must not discard same-intent receipt
      // diagnostics. Without current-page proof it still cannot confirm delivery.
      if(observation.error)return {state:'UNKNOWN',probe};
      if(positive(receipt)&&durable&&JSON.stringify(receipt)===JSON.stringify(durable)
          &&conversationId(probe?.url)===receipt.conversation_id) {
        check();return {state:'CONFIRMED',probe,messageId:receipt.message_id};
      }
      const responseReadFailure=['response_bound','event_bound','malformed_event','response_read_error',
        'clone_unavailable','response_body_unavailable'].includes(receipt?.invalid);
      if(validKey&&expected&&conversationId(probe?.url)===expected
          &&(intent.released===true||receipt?.released===true)
          &&(observed===null||belongs(observed))
          &&(!receipt?.conversation_id||receipt.conversation_id===expected)
          &&(!receipt?.invalid||responseReadFailure)&&newResponseAfterInput(intent,probe)) {
        check();return {state:'CONFIRMED',probe,messageId:receipt?.message_id||intent.message_id||null,evidence:'NEW_RESPONSE'};
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
    await runPageAction(session,'quiesce');
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

  async monitor({session,signal,onActivity}) {
    while(!signal?.aborted) {
      const probe=await this.#observeProgress(session,signal);
      if(signal?.aborted)break;
      await onActivity?.({url:probe?.url,pageUrl:probe?.url,
        responseTextLength:probe?.responseTextLength??probe?.assistantTextLength});
      await delay(this.config.probeIntervalMs||60000,signal);
    }
    return {status:'ABORTED'};
  }
}
