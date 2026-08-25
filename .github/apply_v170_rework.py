from pathlib import Path
import re


def replace(path, old, new, count=1):
    p = Path(path)
    text = p.read_text()
    actual = text.count(old)
    if actual != count:
        raise SystemExit(f'{path}: expected {count} occurrences, found {actual}: {old[:100]!r}')
    p.write_text(text.replace(old, new, count))


pref = 'app/src/main/java/com/shaterguy/chatgptselfrun/ChatReasoningPreferenceStore.java'
replace(pref,
    '    static final String PRO = "pro";\n',
    '    static final String PRO_STANDARD = "pro_standard";\n    static final String PRO_EXTENDED = "pro_extended";\n')
replace(pref,
    '            case PRO -> 4;\n',
    '            case PRO_STANDARD -> 4;\n            case PRO_EXTENDED -> 5;\n')
replace(pref,
    '            case PRO -> "Pro";\n',
    '            case PRO_STANDARD -> "Pro Standard";\n            case PRO_EXTENDED -> "Pro Extended";\n')
replace(pref,
    '            case INSTANT, MEDIUM, HIGH, EXTRA_HIGH, PRO -> selection;\n',
    '            case INSTANT, MEDIUM, HIGH, EXTRA_HIGH, PRO_STANDARD, PRO_EXTENDED -> selection;\n')

# The former explicit Pro control represented the highest option; keep that user intent as Pro Extended.
for path in [
    'app/src/main/java/com/shaterguy/chatgptselfrun/SelfRunNewActivity.java',
    'app/src/androidTest/java/com/shaterguy/chatgptselfrun/ChatReasoningProcessRecreationAndroidTest.java',
]:
    p = Path(path)
    p.write_text(p.read_text().replace('ChatReasoningPreferenceStore.PRO', 'ChatReasoningPreferenceStore.PRO_EXTENDED'))
replace('app/src/main/java/com/shaterguy/chatgptselfrun/SelfRunNewActivity.java',
    '            "High · 확장 추론", "Extra High · 최대 추론", "Pro · 최고 성능"\n',
    '            "High · 확장 추론", "Extra High · 최대 추론", "Pro Extended · 최고 성능"\n')

option = 'app/src/main/java/com/shaterguy/chatgptselfrun/ChatReasoningOptionDom.java'
replace(option,
    "                  if(/^(pro|프로)(?:\\\\s|$)/.test(v))return'pro';\n",
    "                  if(/^(?:pro[\\\\s·:—-]*standard|프로[\\\\s·:—-]*표준)(?:\\\\s|$)/.test(v))return'pro_standard';\n"
    "                  if(/^(?:pro[\\\\s·:—-]*extended|프로[\\\\s·:—-]*확장)(?:\\\\s|$)/.test(v))return'pro_extended';\n")

network = 'app/src/main/java/com/shaterguy/chatgptselfrun/SelfRunNetworkState.java'
replace(network, '    private volatile boolean validated;\n', '    private volatile boolean validated;\n    private volatile long validatedSince;\n')
replace(network, '                validated = isValidated(capabilities);\n', '                updateValidated(isValidated(capabilities));\n')
replace(network, '                validated = false;\n', '                updateValidated(false);\n', 2)
replace(network,
    '            validated = active != null && isValidated(connectivity.getNetworkCapabilities(active));\n',
    '            updateValidated(active != null && isValidated(connectivity.getNetworkCapabilities(active)));\n')
replace(network,
    '            validated = false;\n            registered = false;\n',
    '            updateValidated(false);\n            registered = false;\n')
replace(network,
    '        registered = false;\n    }\n\n    boolean isValidated() {\n        return validated;\n    }\n',
    '        registered = false;\n        updateValidated(false);\n    }\n\n    boolean isValidated() {\n        return validated;\n    }\n\n    long validatedSince() {\n        return validated ? validatedSince : 0L;\n    }\n\n    private void updateValidated(boolean next) {\n        if (next) {\n            if (!validated || validatedSince <= 0L) validatedSince = System.currentTimeMillis();\n        } else {\n            validatedSince = 0L;\n        }\n        validated = next;\n    }\n')

policy = 'app/src/main/java/com/shaterguy/chatgptselfrun/SelfRunRolloverPolicy.java'
replace(policy,
    '    static final String CONTINUATION_NO_PROGRESS = "CONTINUATION_NO_PROGRESS";\n',
    '    static final String CONTINUATION_NO_PROGRESS = "CONTINUATION_NO_PROGRESS";\n    static final String CONTINUATION_NO_START_TIMEOUT = "CONTINUATION_NO_START_TIMEOUT";\n')
replace(policy,
    '    static final long CONTINUATION_SOFT_STALL_GRACE_MS = 15_000L;\n',
    '    static final long CONTINUATION_SOFT_STALL_GRACE_MS = 15_000L;\n    static final long CONTINUATION_NO_START_MAX_WAIT_MS = 60_000L;\n')
replace(policy,
    '    static boolean continuationProgressStatus(String status) {\n',
    '    static boolean postDispatchNoStartTimedOut(long phaseStartedAt, boolean sawStop,\n'
    '                                                      long validatedSince, long now) {\n'
    '        if (sawStop || phaseStartedAt <= 0L || validatedSince <= 0L\n'
    '                || now < phaseStartedAt || now < validatedSince) return false;\n'
    '        long continuouslyValidatedStart = Math.max(phaseStartedAt, validatedSince);\n'
    '        return now - continuouslyValidatedStart >= CONTINUATION_NO_START_MAX_WAIT_MS;\n'
    '    }\n\n'
    '    static boolean continuationProgressStatus(String status) {\n')

service = 'app/src/main/java/com/shaterguy/chatgptselfrun/SelfRunService.java'
replace(service,
    '    String phase=store.phase();\n    if(!SelfRunStore.PHASE_WAIT_TURN_COMPLETION.equals(phase))resumeWebView();\n',
    '    String phase=store.phase();\n'
    '    if(SelfRunStore.PHASE_WAIT_TURN_COMPLETION.equals(phase)\n'
    '            && SelfRunRolloverPolicy.knownConversation(store.conversationUrl())\n'
    '            && SelfRunRolloverPolicy.postDispatchNoStartTimedOut(store.phaseStartedAt(),\n'
    '                    store.turnObserverSawStop(), networkState.validatedSince(), System.currentTimeMillis())){\n'
    '        rolloverConversation(SelfRunRolloverPolicy.CONTINUATION_NO_START_TIMEOUT);return;\n'
    '    }\n'
    '    if(!SelfRunStore.PHASE_WAIT_TURN_COMPLETION.equals(phase))resumeWebView();\n')

policy_test = 'app/src/test/java/com/shaterguy/chatgptselfrun/SelfRunRolloverPolicyTest.java'
replace(policy_test,
    '    @Test public void lineageCauseSetBlocksSameCauseFromRecurring() {\n',
    '    @Test public void postDispatchNoStartNeedsAContinuousValidatedWindowAndStopsTimingAfterGenerationStarts() {\n'
    '        long phaseStarted=10_000L;\n'
    '        long deadline=phaseStarted+SelfRunRolloverPolicy.CONTINUATION_NO_START_MAX_WAIT_MS;\n'
    '        assertTrue(SelfRunRolloverPolicy.postDispatchNoStartTimedOut(phaseStarted,false,phaseStarted,deadline));\n'
    '        assertFalse(SelfRunRolloverPolicy.postDispatchNoStartTimedOut(phaseStarted,true,phaseStarted,deadline+60_000L));\n'
    '        assertFalse(SelfRunRolloverPolicy.postDispatchNoStartTimedOut(phaseStarted,false,0L,deadline+60_000L));\n'
    '        long revalidated=deadline-1_000L;\n'
    '        assertFalse(SelfRunRolloverPolicy.postDispatchNoStartTimedOut(phaseStarted,false,revalidated,deadline));\n'
    '        assertTrue(SelfRunRolloverPolicy.postDispatchNoStartTimedOut(phaseStarted,false,revalidated,\n'
    '                revalidated+SelfRunRolloverPolicy.CONTINUATION_NO_START_MAX_WAIT_MS));\n'
    '    }\n\n'
    '    @Test public void lineageCauseSetBlocksSameCauseFromRecurring() {\n')

submit_test = 'app/src/test/java/com/shaterguy/chatgptselfrun/SelfRunContinuationSubmissionVerificationTest.java'
replace(submit_test,
    '    private static String source(String name) throws Exception {\n',
    '    @Test public void waitTurnCompletionHasBoundedNoStartConvergenceWithoutNewPolling() throws Exception {\n'
    '        String service = source("SelfRunService.java");\n'
    '        assertTrue(service.contains("postDispatchNoStartTimedOut(store.phaseStartedAt()"));\n'
    '        assertTrue(service.contains("store.turnObserverSawStop(), networkState.validatedSince()"));\n'
    '        assertTrue(service.contains("CONTINUATION_NO_START_TIMEOUT"));\n'
    '        String network = source("SelfRunNetworkState.java");\n'
    '        assertTrue(network.contains("registerDefaultNetworkCallback"));\n'
    '        assertTrue(network.contains("validatedSince()"));\n'
    '        assertFalse(network.contains("setInterval"));\n'
    '        String observer = SelfRunContinuationDom.observeTurnCompletion(URL, "SR-TEST", "bounded-token", 5000L, false);\n'
    '        assertTrue(observer.contains("state.sawStop"));\n'
    '        assertFalse(observer.contains("setInterval"));\n'
    '    }\n\n'
    '    private static String source(String name) throws Exception {\n')

reason_test = Path('app/src/test/java/com/shaterguy/chatgptselfrun/ChatReasoningPolicyTest.java')
text = reason_test.read_text()
text = text.replace('fiveChatReasoningSelectionsMapLeftToRight', 'sixChatReasoningSelectionsMapLeftToRight')
text = text.replace('        assertEquals(4, ChatReasoningPreferenceStore.ordinal(ChatReasoningPreferenceStore.PRO));\n',
    '        assertEquals(4, ChatReasoningPreferenceStore.ordinal(ChatReasoningPreferenceStore.PRO_STANDARD));\n'
    '        assertEquals(5, ChatReasoningPreferenceStore.ordinal(ChatReasoningPreferenceStore.PRO_EXTENDED));\n'
    '        assertEquals(ChatReasoningPreferenceStore.KEEP, ChatReasoningPreferenceStore.normalize("pro"));\n')
text = text.replace('ChatReasoningPreferenceStore.PRO, "SR-LEGACY"', 'ChatReasoningPreferenceStore.EXTRA_HIGH, "SR-LEGACY"')
text = text.replace('ChatReasoningPreferenceStore.PRO, "SR-ADVANCED"', 'ChatReasoningPreferenceStore.PRO_EXTENDED, "SR-ADVANCED"')
reason_test.write_text(text)

# Legacy slider fixtures only expose five numeric positions; production exact picker owns the six-state contract.
delayed = Path('app/src/androidTest/java/com/shaterguy/chatgptselfrun/ChatReasoningDelayedDomWebViewTest.java')
dt = delayed.read_text()
dt = dt.replace('chatReasoningScript(ChatReasoningPreferenceStore.PRO, "SR-DELAYED-TRIGGER")',
                'chatReasoningScript(ChatReasoningPreferenceStore.EXTRA_HIGH, "SR-DELAYED-TRIGGER")')
dt = dt.replace('assertEquals("4", read(scenario, web, "document.getElementById(\'slider\').value"));',
                'assertEquals("3", read(scenario, web, "document.getElementById(\'slider\').value"));', 1)
dt = dt.replace('assertEquals("pro", ready.getJSONObject("diagnostics").getString("observed"));',
                'assertEquals("xhigh", ready.getJSONObject("diagnostics").getString("observed"));', 1)
dt = dt.replace('                    ChatReasoningPreferenceStore.PRO,\n                    ChatReasoningPreferenceStore.INSTANT\n            };\n            int[] expected = {1, 2, 3, 4, 0};',
                '                    ChatReasoningPreferenceStore.INSTANT\n            };\n            int[] expected = {1, 2, 3, 0};')
dt = dt.replace('            assertEquals("5", read(scenario, web, "String(window.menuOpenClicks)"));\n            assertEquals("5", read(scenario, web, "String(window.menuCloseClicks)"));',
                '            assertEquals("4", read(scenario, web, "String(window.menuOpenClicks)"));\n            assertEquals("4", read(scenario, web, "String(window.menuCloseClicks)"));')
delayed.write_text(dt)

work_test = Path('app/src/androidTest/java/com/shaterguy/chatgptselfrun/WorkPreferenceDomWebViewTest.java')
wt = work_test.read_text().replace('ChatReasoningPreferenceStore.PRO,', 'ChatReasoningPreferenceStore.EXTRA_HIGH,')
wt = wt.replace('"document.getElementById(\'slider\').value", "4");', '"document.getElementById(\'slider\').value", "3");', 1)
work_test.write_text(wt)

hierarchy = Path('app/src/androidTest/java/com/shaterguy/chatgptselfrun/ChatReasoningHierarchicalMenuAndroidTest.java')
ht = hierarchy.read_text()
pattern = re.compile(r'    @Test public void englishAdvancedButtonReplacementMenuAppliesProWithoutSliderMutation\(\) throws Exception \{.*?\n    \}\n\n    @Test public void keepCapturesCurrentPickerValueWithoutChangingIt', re.S)
replacement = '''    @Test public void englishAdvancedButtonReplacementMenuAppliesProExtendedWithoutSliderMutation() throws Exception {
        assertEnglishProSelection(ChatReasoningPreferenceStore.PRO_EXTENDED, "pro_extended", "Pro Extended");
    }

    @Test public void englishAdvancedButtonReplacementMenuAppliesProStandardWithoutSliderMutation() throws Exception {
        assertEnglishProSelection(ChatReasoningPreferenceStore.PRO_STANDARD, "pro_standard", "Pro Standard");
    }

    private void assertEnglishProSelection(String selection, String expectedObserved, String expectedLabel) throws Exception {
        try (ActivityScenario<SelfRunNewActivity> scenario = ActivityScenario.launch(SelfRunNewActivity.class)) {
            AtomicReference<WebView> web = new AtomicReference<>();
            load(scenario, web, englishFixture());
            String runId = "SR-ADVANCED-EN-" + expectedObserved;
            scenario.onActivity(activity -> assertTrue(ChatReasoningPreferenceStore.save(activity, runId, selection)));
            JSONObject ready = runToReady(scenario, web, SelfRunDom.prepareInitialContext(PROJECT_URL, SelfRunStore.MODE_CHAT, runId));
            assertEquals(expectedObserved, ready.getJSONObject("diagnostics").getString("observed"));
            assertEquals("1", read(scenario, web, "String(window.triggerClicks)"));
            assertEquals("1", read(scenario, web, "String(window.advancedClicks)"));
            assertEquals("1", read(scenario, web, "String(window.reasoningClicks)"));
            assertEquals("1", read(scenario, web, "String(window.optionClicks)"));
            assertEquals("0", read(scenario, web, "String(window.inertReasoningClicks)"));
            assertEquals("0", read(scenario, web, "String(window.sliderEvents)"));
            assertEquals(expectedLabel, read(scenario, web, "document.getElementById('reasoning-trigger').textContent"));
        }
    }

    @Test public void keepCapturesCurrentPickerValueWithoutChangingIt'''
ht, n = pattern.subn(replacement, ht)
if n != 1:
    raise SystemExit(f'hierarchy method replacement count={n}')
old_menu = '<div id="submenu" role="menu" hidden><button type="button" role="menuitemradio" aria-checked="false">Instant</button><button type="button" role="menuitemradio" aria-checked="false">Medium</button><button type="button" role="menuitemradio" aria-checked="false">High</button><button type="button" role="menuitemradio" aria-checked="true">Extra high</button><button id="pro" type="button" role="menuitemradio" aria-checked="false">Pro</button></div>'
new_menu = '<div id="submenu" role="menu" hidden><button type="button" role="menuitemradio" aria-checked="false">Instant</button><button type="button" role="menuitemradio" aria-checked="false">Medium</button><button type="button" role="menuitemradio" aria-checked="false">High</button><button type="button" role="menuitemradio" aria-checked="true">Extra high</button><button id="pro-standard" type="button" role="menuitemradio" aria-checked="false">Pro Standard</button><button id="pro-extended" type="button" role="menuitemradio" aria-checked="false">Pro Extended</button></div>'
if ht.count(old_menu) != 1:
    raise SystemExit('english pro menu fixture not found exactly once')
ht = ht.replace(old_menu, new_menu)
old_handler = "                document.getElementById('pro').onclick=event=>{window.optionClicks++;for(const option of submenu.querySelectorAll('[role=menuitemradio]'))option.setAttribute('aria-checked','false');event.currentTarget.setAttribute('aria-checked','true');trigger.textContent='Pro';trigger.setAttribute('aria-expanded','false');sheet.hidden=true;submenu.hidden=true;};"
new_handler = "                const applyPro=event=>{window.optionClicks++;for(const option of submenu.querySelectorAll('[role=menuitemradio]'))option.setAttribute('aria-checked','false');event.currentTarget.setAttribute('aria-checked','true');trigger.textContent=event.currentTarget.textContent;trigger.setAttribute('aria-expanded','false');sheet.hidden=true;submenu.hidden=true;};document.getElementById('pro-standard').onclick=applyPro;document.getElementById('pro-extended').onclick=applyPro;"
if ht.count(old_handler) != 1:
    raise SystemExit('english pro handler not found exactly once')
ht = ht.replace(old_handler, new_handler)
hierarchy.write_text(ht)

coordinator_test = 'app/src/androidTest/java/com/shaterguy/chatgptselfrun/SelfRunRolloverCoordinatorAndroidTest.java'
replace(coordinator_test,
    '    private SelfRunStore predecessor() {\n',
    '''    @Test public void proStandardAndExtendedRemainDistinctAcrossRollover() {
        for (String selection : new String[]{ChatReasoningPreferenceStore.PRO_STANDARD, ChatReasoningPreferenceStore.PRO_EXTENDED}) {
            clearAll();
            SelfRunStore store = predecessor();
            assertTrue(ChatPickerStateStore.saveObserved(context, store.runId(), selection));
            SelfRunRolloverCoordinator.Result result = new SelfRunRolloverCoordinator(context)
                    .beginOrResume(store, SelfRunRolloverPolicy.ROUTE_MISMATCH);
            assertTrue(result.started());
            assertEquals(selection, ChatReasoningPreferenceStore.selectionForRun(context, result.successorRunId));
        }
    }

    @Test public void postDispatchNoStartPolicyDoesNotCountUnvalidatedOrStartedGeneration() {
        long start = 1_000L;
        long deadline = start + SelfRunRolloverPolicy.CONTINUATION_NO_START_MAX_WAIT_MS;
        assertFalse(SelfRunRolloverPolicy.postDispatchNoStartTimedOut(start, false, 0L, deadline + 1L));
        assertFalse(SelfRunRolloverPolicy.postDispatchNoStartTimedOut(start, true, start, deadline + 1L));
        assertTrue(SelfRunRolloverPolicy.postDispatchNoStartTimedOut(start, false, start, deadline));
    }

    private SelfRunStore predecessor() {
''')

for p in Path('app').rglob('*.java'):
    if 'ChatReasoningPreferenceStore.PRO' in p.read_text():
        raise SystemExit(f'ambiguous PRO reference remains: {p}')
