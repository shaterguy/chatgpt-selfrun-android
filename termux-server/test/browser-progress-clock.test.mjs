import vm from 'node:vm';
import assert from 'node:assert/strict';
import {ChatGptBrowser} from '../src/browser/chatgpt.mjs';
let elapsed=10, label='Thinking', answer='unchanged answer';
const rootElement={},turn={parentElement:rootElement,getAttribute:()=> 'response-1'};
const parent={parentElement:turn,closest:()=>null};
const document={title:'test',readyState:'complete',body:{innerText:''},querySelectorAll:s=>s==='main [data-turn-key]'?[turn]:[],
 createTreeWalker(){const nodes=[{nodeValue:label+' for '+elapsed+' seconds',parentElement:parent},{nodeValue:answer,parentElement:parent}];let i=0;return {currentNode:null,nextNode(){this.currentNode=nodes[i++];return !!this.currentNode;}};}};
const session={call:async(_m,p)=>({result:{value:vm.runInNewContext(p.expression,{document,location:{href:'https://chatgpt.com/c/synthetic',pathname:'/c/synthetic'},NodeFilter:{SHOW_TEXT:4},getComputedStyle:()=>({display:'block',visibility:'visible',opacity:'1'})})}})};
const browser=new ChatGptBrowser(null,{});
const a=await browser.livenessSnapshot({session});elapsed=11;const b=await browser.livenessSnapshot({session});
console.log(JSON.stringify({clock_only_change:{before:a.responseFingerprint,after:b.responseFingerprint,length_before:a.responseTextLength,length_after:b.responseTextLength,equal:a.responseFingerprint===b.responseFingerprint}}));

assert.equal(a.responseFingerprint,b.responseFingerprint,'elapsed-time label must not count as response progress');
for (const value of ['Working','Worked','Thought','Thinking']) {
 label=value;elapsed=10;const before=await browser.livenessSnapshot({session});
 elapsed=11;const after=await browser.livenessSnapshot({session});
 assert.equal(before.responseFingerprint,after.responseFingerprint,value+' timer must not count as progress');
 assert.equal(before.responseTextLength,after.responseTextLength);
}
answer='answer with actual new content';
const changed=await browser.livenessSnapshot({session});
assert.notEqual(changed.responseFingerprint,b.responseFingerprint,'actual answer changes must count as progress');
console.log('PASS emitted browser probe ignores elapsed-time labels and preserves real progress');
