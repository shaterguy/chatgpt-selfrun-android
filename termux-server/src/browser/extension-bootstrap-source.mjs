// The extension opens its own controller only after its startup event.
// Keep endpoint attestation independent of pre-authentication CDP mutations.
export function controllerBootstrapRuntime() {
  'use strict';
  let opening;
  function start() {
    if (opening) return;
    opening = (async () => {
      const url = chrome.runtime.getURL('controller.html');
      const contexts = await chrome.runtime.getContexts({contextTypes: ['TAB'], documentUrls: [url]});
      if (!contexts.length) await chrome.windows.create({url, focused: false, type: 'normal'});
    })().catch(() => {
      opening = null;
      console.error('SelfRun controller bootstrap failed');
    });
  }
  chrome.runtime.onInstalled.addListener(start);
  chrome.runtime.onStartup.addListener(start);
}
