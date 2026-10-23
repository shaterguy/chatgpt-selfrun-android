// Use the same native preferences as the scheduler, before entering the composer.
export function nativeProfileFromOperations(operations=[]) {
  const value=path=>operations.find(op=>op.path===path&&op.op==='SET')?.value??null;
  return {mode:value('conversation_origin')==='tpp'?'work':'chat',model:value('model'),thinking_effort:value('thinking_effort')};
}

export async function applyNativePreference(env,profile) {
  const query={model_slug:profile.model};
  if(profile.thinking_effort!==null)query.thinking_effort=profile.thinking_effort;
  await env.api.safePatch(profile.mode==='work'?'/settings/user_tpp_last_used_model_config':'/settings/user_last_used_model_config',{parameters:{query}});
  const selection={slug:profile.model,thinkingEffort:profile.thinking_effort};
  if(profile.mode==='chat') {
    env.atoms.u('chatgpt-last-selected-model-v1',selection);
    env.atoms.u('chatgpt-last-selected-home-model-v1',selection);
  } else {
    let prior={};try{prior=JSON.parse(env.cookie.a('oai-tpp-model-settings')||'{}')||{};}catch{}
    const cookie={...prior,modelSettings:{defaultModelSlug:prior.modelSettings?.defaultModelSlug??null,lastUsedModelSlug:profile.model},thinkingEffort:profile.thinking_effort,selectionMode:'model'};
    if(profile.thinking_effort!=='ultra')cookie.lastStartedModelConfig={modelSlug:profile.model,thinkingEffort:profile.thinking_effort};
    env.cookie.b('oai-tpp-model-settings',JSON.stringify(cookie),{maxAge:2592000});
    env.atoms.u('chatgpt-last-selected-tpp-model-v1',selection);
    env.atoms.u('chatgpt-work-model-picker-selection-mode-v1','model');
  }
  env.cookie.b('codex_composer_mode',profile.mode,{maxAge:2592000});
  env.atoms.f();
  await env.flush();
}

async function prepareNativePreference(profile,apply) {
  const resources=performance.getEntriesByType('resource').map(e=>e.name);
  const elements=[...document.querySelectorAll('script[src],link[rel="modulepreload"][href]')].map(e=>e.src||e.href);
  const assets=[...new Set([...elements,...resources])].filter(x=>{try{const u=new URL(x,location.href);return u.origin===location.origin&&/^\/cdn\/assets\/[^/]+\.js$/.test(u.pathname);}catch{return false;}});
  let runtime=assets.find(x=>/\/633146\.[a-f0-9]+\.js$/.test(new URL(x).pathname));
  if(!runtime)for(const candidate of assets.slice(0,4)) {
    const source=await fetch(candidate,{credentials:'same-origin'}).then(r=>r.text());
    const match=source.match(/import\s*\{[^}]*\b__webpack_require__\b[^}]*\}\s*from\s*["']([^"']+)["']/);
    if(match){runtime=new URL(match[1],candidate).href;break;}
  }
  const native=await import(runtime),req=native.__webpack_require__;
  const env={api:req('Kwur').Request,atoms:req('kpIY'),cookie:req('ZhRn'),flush:req('YAwS').flushBrowserHostStorageWrites};
  env.atoms.k('chatgpt-last-selected-model-v1',null);
  await apply(env,profile);
  return true;
}

export function nativePreferenceExpression(operations) {
  return `(${prepareNativePreference.toString()})(${JSON.stringify(nativeProfileFromOperations(operations))},${applyNativePreference.toString()})`;
}

export function nativePreferenceHomeUrl(operations) {
  return 'https://chatgpt.com/?surface='+nativeProfileFromOperations(operations).mode+'&disable_auto_send=1';
}
