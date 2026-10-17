import test from 'node:test';
import assert from 'node:assert/strict';
import vm from 'node:vm';

test('static extension package has a narrow manifest and no runtime source evaluation',async()=>{
  const mod=await import('../src/browser/extension-package.mjs').catch(()=>({}));
  assert.equal(typeof mod.buildExtensionFiles,'function','static extension packager is required');
  const files=mod.buildExtensionFiles({bridgeUrl:'http://127.0.0.1:17832',ownerId:'owner-test',capability:'local-test-capability'});
  const manifest=JSON.parse(files['manifest.json']);
  assert.equal(manifest.version,'1.0.3');
  assert.deepEqual(manifest.permissions,['scripting','storage']);
  assert.deepEqual(manifest.host_permissions,['https://chatgpt.com/*','http://127.0.0.1/*']);
  assert.equal(manifest.permissions.includes('debugger'),false);
  assert.equal(manifest.permissions.includes('cookies'),false);
  for(const [name,source] of Object.entries(files).filter(([n])=>n.endsWith('.js'))){
    assert.doesNotMatch(source,/\beval\s*\(|new\s+Function\s*\(|Runtime\.evaluate|Page\.enable/,name);
    assert.doesNotThrow(()=>new vm.Script(source,{filename:name}));
  }
});
test('static page operations keep prompt text as data',async()=>{
  const mod=await import('../src/browser/extension-package.mjs').catch(()=>({}));
  assert.equal(typeof mod.buildPageRuntimeSource,'function','static named page operations are required');
  const source=mod.buildPageRuntimeSource();
  assert.doesNotMatch(source,/\beval\s*\(|new\s+Function\s*\(/);
  const window={};vm.runInNewContext(source,{window,Set,Object,Error,TextEncoder,crypto});
  assert.throws(()=>window.__selfrunExtensionPage.run('arbitraryJavaScript',{source:'throw new Error()'}),/Unsupported/);
});
test('page cannot preseed a matching-version helper to forge an operation result',async()=>{const {buildPageRuntimeSource}=await import('../src/browser/extension-package.mjs');const window={__selfrunExtensionPage:{version:'selfrun-extension-page-v1',run:()=>true}};assert.throws(()=>vm.runInNewContext(buildPageRuntimeSource(),{window,Set,Object,Error,TextEncoder,crypto}),/Unexpected page helper/);});
