import test from 'node:test';
import assert from 'node:assert/strict';
import { applyNativePreference, nativeProfileFromOperations } from '../src/browser/native-profile-preparation.mjs';

function fixture() {
  const calls=[];
  const values=new Map([['oai-tpp-model-settings',JSON.stringify({modelSettings:{defaultModelSlug:'prior-default'},keep:true})]]);
  const env={api:{safePatch:async(...args)=>{calls.push(['patch',...args]);return {success:false};},safeGet:()=>assert.fail('settings must not be read back')},
    atoms:{u:(...args)=>calls.push(['atom',...args]),k:()=>assert.fail('selection must not be read back'),f:()=>calls.push(['flushAtoms'])},
    cookie:{a:key=>values.get(key),b:(key,value)=>{calls.push(['cookie',key,value]);values.set(key,value);return false;}},flush:async()=>calls.push(['flush'])};
  return {env,calls,values};
}
test('Chat uses exact profile values, awaits saving and does not compare the saved state',async()=>{
  const f=fixture();
  await applyNativePreference(f.env,{mode:'chat',model:'gpt-6-thinking',thinking_effort:'extended'});
  assert.deepEqual(f.calls[0],['patch','/settings/user_last_used_model_config',{parameters:{query:{model_slug:'gpt-6-thinking',thinking_effort:'extended'}}}]);
  assert.deepEqual(f.calls.filter(x=>x[0]==='atom'),[
    ['atom','chatgpt-last-selected-model-v1',{slug:'gpt-6-thinking',thinkingEffort:'extended'}],
    ['atom','chatgpt-last-selected-home-model-v1',{slug:'gpt-6-thinking',thinkingEffort:'extended'}]]);
  assert.deepEqual(f.calls.at(-1),['flush']);
});
for(const model of ['gpt-6-instant','gpt-6-pro'])test(model+' removes the previous effort',async()=>{
  const f=fixture();await applyNativePreference(f.env,{mode:'chat',model,thinking_effort:null});
  assert.deepEqual(f.calls[0][2],{parameters:{query:{model_slug:model}}});
  assert.equal(f.calls.find(x=>x[0]==='atom')[2].thinkingEffort,null);
});
test('Work writes server preference, cookie and native selection without readback',async()=>{
  const f=fixture();await applyNativePreference(f.env,{mode:'work',model:'gpt-6.1-sol-wm',thinking_effort:'ultra'});
  assert.equal(f.calls[0][1],'/settings/user_tpp_last_used_model_config');
  const cookie=JSON.parse(f.values.get('oai-tpp-model-settings'));
  assert.equal(cookie.modelSettings.lastUsedModelSlug,'gpt-6.1-sol-wm');
  assert.equal(cookie.modelSettings.defaultModelSlug,'prior-default');
  assert.equal(cookie.thinkingEffort,'ultra');assert.equal(cookie.selectionMode,'model');
  assert.equal(f.values.get('codex_composer_mode'),'work');
});
test('profile operations retain raw model and effort values',()=>{
  const ops=[{op:'SET',path:'model',value:'raw-work-model'},{op:'SET',path:'thinking_effort',value:'extended'},{op:'SET',path:'conversation_origin',value:'tpp'},{op:'REMOVE',path:'service_tier'}];
  assert.deepEqual(nativeProfileFromOperations(ops),{mode:'work',model:'raw-work-model',thinking_effort:'extended'});
});
