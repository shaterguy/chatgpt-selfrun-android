from pathlib import Path


def read(path):
    return Path(path).read_text(encoding="utf-8")


def write(path, text):
    Path(path).parent.mkdir(parents=True, exist_ok=True)
    Path(path).write_text(text, encoding="utf-8")


def replace_once(path, old, new):
    text = read(path)
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one match, found {count}: {old[:120]!r}")
    write(path, text.replace(old, new, 1))


# V-01: KEEP is no longer implicit. Read the current Chat picker state without changing it,
# persist the observed effective selection, and explicitly apply that selection in a successor.
path = "app/src/main/java/com/shaterguy/chatgptselfrun/ChatReasoningOptionDom.java"
replace_once(path,
'''        String wanted = ChatReasoningPreferenceStore.normalize(selection);\n        int ordinal = ChatReasoningPreferenceStore.ordinal(wanted);\n        if (ordinal < 0) return "";\n        return """\n                const __sroWanted=__WANTED__,__sroWantedOrdinal=__ORDINAL__,__sroRunId=__RUN_ID__;''',
'''        String wanted = ChatReasoningPreferenceStore.normalize(selection);\n        boolean captureOnly = ChatReasoningPreferenceStore.KEEP.equals(wanted);\n        int ordinal = ChatReasoningPreferenceStore.ordinal(wanted);\n        if (ordinal < 0 && !captureOnly) return "";\n        return """\n                const __sroWanted=__WANTED__,__sroWantedOrdinal=__ORDINAL__,__sroRunId=__RUN_ID__,__sroCaptureOnly=__CAPTURE_ONLY__;''')
replace_once(path,
'''                const __sroMayClick=(count,max)=>Number(count)<1||(__sroSinceActionMs>=__sroRetryMs&&Number(count)<max);\n                if(__sroTriggerLevel===__sroWanted){''',
'''                const __sroMayClick=(count,max)=>Number(count)<1||(__sroSinceActionMs>=__sroRetryMs&&Number(count)<max);\n                if(__sroCaptureOnly){\n                  const __sroObserved=__sroSelectedLevels.length===1?__sroSelectedLevels[0]:__sroTriggerLevel;\n                  if(__sroObserved){\n                    if(__sroPopups.length===0)return __sroReady(__sroObserved,{action:'capture-current'});\n                    if(__sroMayClick(__sroState.closeAttempts,3)){__sroState.closeAttempts++;__sroState.lastAction='close-captured-current';__sroState.lastActionAt=__sroNow;__sroSave();const method=__sroClose();return result('UI_WAIT','현재 Chat picker 선택값 확인 후 메뉴 닫힘 대기',__sroDiagnostics({action:'close-captured-current',observed:__sroObserved,closeMethod:method}));}\n                    if(__sroElapsedMs>=__sroOverallTimeoutMs||__sroState.attempts>=__sroMaxAttempts)return __sroResult('CHAT_REASONING_MENU_CLOSE_FAILED','현재 Chat picker 선택값 확인 후 메뉴가 닫히지 않았습니다.',{action:'capture-close-timeout',observed:__sroObserved});\n                    return __sroResult('UI_WAIT','현재 Chat picker 선택값 확인 후 메뉴 닫힘 대기',{action:'wait-capture-close',observed:__sroObserved});\n                  }\n                  if(__sroPopups.length===0&&__sroTrigger){\n                    if(__sroMayClick(__sroState.triggerClicks,2)){__sroState.triggerClicks++;__sroState.lastAction='open-picker-for-capture';__sroState.lastActionAt=__sroNow;__sroSave();__sroActivate(__sroTrigger);return result('UI_WAIT','현재 Chat picker 선택값 readback을 위한 메뉴 열림 대기',__sroDiagnostics({action:'open-picker-for-capture'}));}\n                    if(__sroElapsedMs>=__sroOverallTimeoutMs||__sroState.attempts>=__sroMaxAttempts)return __sroResult('CHAT_REASONING_READBACK_MISMATCH','현재 Chat picker 선택값을 확인하지 못했습니다.',{action:'capture-trigger-timeout'});\n                    return __sroResult('UI_WAIT','현재 Chat picker 메뉴 열림 확인 대기',{action:'wait-capture-trigger'});\n                  }\n                  if(!__sroTrigger&&__sroPopups.length===0&&(__sroElapsedMs>=__sroOverallTimeoutMs||__sroState.attempts>=__sroMaxAttempts))return __sroResult('CHAT_REASONING_TRIGGER_NOT_FOUND','현재 Chat picker를 찾지 못했습니다.',{action:'capture-missing-trigger'});\n                  if(__sroPopups.length>0&&(__sroElapsedMs>=__sroRenderTimeoutMs||__sroState.attempts>=14))return __sroResult('CHAT_REASONING_READBACK_MISMATCH','열린 Chat picker에서 현재 선택값을 확인하지 못했습니다.',{action:'capture-open-popup-timeout'});\n                  return __sroResult('UI_WAIT','현재 Chat picker 선택값 readback 대기',{action:'wait-capture-readback'});\n                }\n                if(__sroTriggerLevel===__sroWanted){''')
replace_once(path,
'''                .replace("__WANTED__", SelfRunScript.quote(wanted))\n                .replace("__ORDINAL__", String.valueOf(ordinal))\n                .replace("__RUN_ID__", SelfRunScript.quote(runId));''',
'''                .replace("__WANTED__", SelfRunScript.quote(wanted))\n                .replace("__ORDINAL__", String.valueOf(ordinal))\n                .replace("__RUN_ID__", SelfRunScript.quote(runId))\n                .replace("__CAPTURE_ONLY__", String.valueOf(captureOnly));''')

write("app/src/main/java/com/shaterguy/chatgptselfrun/ChatPickerStateStore.java", r'''package com.shaterguy.chatgptselfrun;

import android.content.Context;
import android.content.SharedPreferences;

/** Durable per-run readback of the effective Chat model-picker selection. */
final class ChatPickerStateStore {
    private static final String PREFS = "selfrun_drive_chat_picker_state";
    private static final String PREFIX = "run:";

    private ChatPickerStateStore() {}

    static boolean saveObserved(Context context, String runId, String selection) {
        if (context == null || runId == null || runId.isEmpty()) return false;
        String normalized = ChatReasoningPreferenceStore.normalize(selection);
        if (!ChatReasoningPreferenceStore.shouldApply(normalized)) return false;
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putString(PREFIX + runId, normalized).commit();
    }

    static String observedForRun(Context context, String runId) {
        if (context == null || runId == null || runId.isEmpty()) return "";
        String stored = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(PREFIX + runId, "");
        String normalized = ChatReasoningPreferenceStore.normalize(stored);
        return ChatReasoningPreferenceStore.shouldApply(normalized) ? normalized : "";
    }

    static String effectiveForRun(Context context, String runId) {
        String observed = observedForRun(context, runId);
        if (!observed.isEmpty()) return observed;
        String requested = ChatReasoningPreferenceStore.selectionForRun(context, runId);
        return ChatReasoningPreferenceStore.shouldApply(requested)
                ? ChatReasoningPreferenceStore.normalize(requested) : "";
    }
}
''')

# Persist bootstrap readback for both explicit and KEEP Chat selections.
path = "app/src/main/java/com/shaterguy/chatgptselfrun/SelfRunService.java"
replace_once(path,
'''private boolean completeBootstrap(JSONObject result){\n    String runId=store.runId();String requested=ChatReasoningPreferenceStore.selectionForRun(runId);\n    if(SelfRunStore.MODE_CHAT.equals(store.mode())&&ChatReasoningPreferenceStore.shouldApply(requested)){\n        String observed=BootstrapResultPolicy.observedReasoning(result);\n        if(!requested.equals(ChatReasoningPreferenceStore.normalize(observed))){failBootstrap(BootstrapResultPolicy.READBACK_MISSING,"requested="+requested+";observed="+observed,result==null?null:result.optJSONObject("diagnostics"));return false;}\n        if(!BootstrapRunStateStore.markReasoningApplied(this,runId,observed)){failBootstrap(BootstrapResultPolicy.STATE_PERSIST_FAILED,"reasoning applied state persistence failed",result==null?null:result.optJSONObject("diagnostics"));return false;}\n    }\n    if(!BootstrapRunStateStore.markBootstrapCompleted(this,runId,"READY")){failBootstrap(BootstrapResultPolicy.STATE_PERSIST_FAILED,"bootstrap completion persistence failed",result==null?null:result.optJSONObject("diagnostics"));return false;}\n    new SelfRunHistoryStore(this).sync(store);\n    return true;\n}''',
'''private boolean completeBootstrap(JSONObject result){\n    String runId=store.runId();String requested=ChatReasoningPreferenceStore.selectionForRun(runId);\n    if(SelfRunStore.MODE_CHAT.equals(store.mode())){\n        String observed=BootstrapResultPolicy.observedReasoning(result);\n        String normalizedObserved=ChatReasoningPreferenceStore.normalize(observed);\n        if(!ChatReasoningPreferenceStore.shouldApply(normalizedObserved)){failBootstrap(BootstrapResultPolicy.READBACK_MISSING,"effective Chat picker readback missing",result==null?null:result.optJSONObject("diagnostics"));return false;}\n        if(ChatReasoningPreferenceStore.shouldApply(requested)){\n            if(!requested.equals(normalizedObserved)){failBootstrap(BootstrapResultPolicy.READBACK_MISSING,"explicit Chat picker readback mismatch",result==null?null:result.optJSONObject("diagnostics"));return false;}\n            if(!BootstrapRunStateStore.markReasoningApplied(this,runId,normalizedObserved)){failBootstrap(BootstrapResultPolicy.STATE_PERSIST_FAILED,"reasoning applied state persistence failed",result==null?null:result.optJSONObject("diagnostics"));return false;}\n        }\n        if(!ChatPickerStateStore.saveObserved(this,runId,normalizedObserved)){failBootstrap(BootstrapResultPolicy.STATE_PERSIST_FAILED,"effective Chat picker state persistence failed",result==null?null:result.optJSONObject("diagnostics"));return false;}\n    }\n    if(!BootstrapRunStateStore.markBootstrapCompleted(this,runId,"READY")){failBootstrap(BootstrapResultPolicy.STATE_PERSIST_FAILED,"bootstrap completion persistence failed",result==null?null:result.optJSONObject("diagnostics"));return false;}\n    new SelfRunHistoryStore(this).sync(store);\n    return true;\n}''')

# V-02: do not erase failure evidence on every diagnostic callback. Only clear on genuine progress,
# and route persistent no-progress states through a bounded status-specific failure budget.
replace_once(path,
'''  JSONObject result=parsed.result;String status=parsed.status,detail=parsed.detail;\n  if(isContinuationDiagnosticPhase(phase))rollover.clearLocalFailures(runId);''',
'''  JSONObject result=parsed.result;String status=parsed.status,detail=parsed.detail;\n  if(isContinuationDiagnosticPhase(phase)&&SelfRunRolloverPolicy.continuationProgressStatus(status))rollover.clearLocalFailures(runId);''')
replace_once(path,
'''  if(SelfRunStore.PHASE_SEND_CONTINUE.equals(phase)){\n      if("CONTINUE_CLICKED".equals(status)||"SUBMISSION_CONFIRMED".equals(status)||"VERIFY_REQUIRED".equals(status)){continuationSubmitted(detail);return;}\n      if("SUBMISSION_FAILED".equals(status)||"COMPOSER_CLEARING".equals(status)||"COMPOSER_INPUTTING".equals(status)||SelfRunContinuationDom.STOP.equals(status)||SelfRunContinuationDom.SEND_DISABLED.equals(status)||SelfRunContinuationDom.UNKNOWN.equals(status)||"SCRIPT_ERROR".equals(status)){recordContinuationWait(phase,status,detail);scheduleWeb(CONTINUATION_VERIFY_INTERVAL_MS);return;}\n  }''',
'''  if(SelfRunStore.PHASE_SEND_CONTINUE.equals(phase)){\n      if("CONTINUE_CLICKED".equals(status)||"SUBMISSION_CONFIRMED".equals(status)||"VERIFY_REQUIRED".equals(status)){rollover.clearLocalFailures(runId);continuationSubmitted(detail);return;}\n      if(SelfRunRolloverPolicy.shouldCountContinuationFailure(status,store.phaseStartedAt(),System.currentTimeMillis())&&SelfRunRolloverPolicy.knownConversation(store.conversationUrl())){\n          recordContinuationWait(phase,status,detail);\n          if(!networkState.isValidated()){rollover.clearLocalFailures(runId);scheduleWeb(1200L);return;}\n          int failures=rollover.recordLocalFailure(runId,status);\n          if(SelfRunRolloverPolicy.localFailureBudgetExhausted(failures)){rolloverConversation(SelfRunRolloverPolicy.CONTINUATION_NO_PROGRESS);return;}\n          scheduleWeb(1200L);return;\n      }\n      if("SUBMISSION_FAILED".equals(status)||"COMPOSER_CLEARING".equals(status)||"COMPOSER_INPUTTING".equals(status)||SelfRunContinuationDom.STOP.equals(status)||SelfRunContinuationDom.SEND_DISABLED.equals(status)||SelfRunContinuationDom.UNKNOWN.equals(status)||"SCRIPT_ERROR".equals(status)){recordContinuationWait(phase,status,detail);scheduleWeb(CONTINUATION_VERIFY_INTERVAL_MS);return;}\n  }''')
replace_once(path,
'''private static boolean isConversationLocalFailureStatus(String status){return "SUBMISSION_AMBIGUOUS".equals(status)||"MARKER_FAILED".equals(status)||"SUBMISSION_PENDING".equals(status);}''',
'''private static boolean isConversationLocalFailureStatus(String status){return SelfRunRolloverPolicy.hardContinuationFailureStatus(status);}''')
replace_once(path,
'''  if(isConversationLocalFailureStatus(status)&&SelfRunRolloverPolicy.knownConversation(store.conversationUrl())){\n      int failures=rollover.incrementLocalFailure(runId);''',
'''  if(isConversationLocalFailureStatus(status)&&SelfRunRolloverPolicy.knownConversation(store.conversationUrl())){\n      int failures=rollover.recordLocalFailure(runId,status);''')

# Failure taxonomy with time-based grace on existing callbacks only; no new polling is introduced.
path = "app/src/main/java/com/shaterguy/chatgptselfrun/SelfRunRolloverPolicy.java"
replace_once(path,
'''    static final String CONTINUATION_CALLBACK_TIMEOUT = "CONTINUATION_CALLBACK_TIMEOUT";\n    static final String WEBVIEW_CREATE_FAILURE = "WEBVIEW_CREATE_FAILURE";''',
'''    static final String CONTINUATION_CALLBACK_TIMEOUT = "CONTINUATION_CALLBACK_TIMEOUT";\n    static final String CONTINUATION_NO_PROGRESS = "CONTINUATION_NO_PROGRESS";\n    static final String WEBVIEW_CREATE_FAILURE = "WEBVIEW_CREATE_FAILURE";''')
replace_once(path,
'''    static final int MAX_LOCAL_FAILURES = 3;''',
'''    static final int MAX_LOCAL_FAILURES = 3;\n    static final long CONTINUATION_HARD_FAILURE_GRACE_MS = 5_000L;\n    static final long CONTINUATION_SOFT_STALL_GRACE_MS = 15_000L;''')
replace_once(path,
'''    static boolean localFailureBudgetExhausted(int failures) {\n        return failures >= MAX_LOCAL_FAILURES;\n    }''',
'''    static boolean hardContinuationFailureStatus(String status) {\n        return "SUBMISSION_AMBIGUOUS".equals(status) || "MARKER_FAILED".equals(status)\n                || "SUBMISSION_PENDING".equals(status) || "SUBMISSION_FAILED".equals(status)\n                || SelfRunContinuationDom.UNKNOWN.equals(status) || "SCRIPT_ERROR".equals(status);\n    }\n\n    static boolean softContinuationStallStatus(String status) {\n        return "COMPOSER_CLEARING".equals(status) || "COMPOSER_INPUTTING".equals(status)\n                || SelfRunContinuationDom.STOP.equals(status) || SelfRunContinuationDom.SEND_DISABLED.equals(status);\n    }\n\n    static boolean shouldCountContinuationFailure(String status, long phaseStartedAt, long now) {\n        if (phaseStartedAt <= 0L || now < phaseStartedAt) return false;\n        long elapsed = now - phaseStartedAt;\n        if (hardContinuationFailureStatus(status)) return elapsed >= CONTINUATION_HARD_FAILURE_GRACE_MS;\n        return softContinuationStallStatus(status) && elapsed >= CONTINUATION_SOFT_STALL_GRACE_MS;\n    }\n\n    static boolean continuationProgressStatus(String status) {\n        return "READY".equals(status) || "READY_TO_SUBMIT".equals(status)\n                || "CONTINUE_CLICKED".equals(status) || "SUBMISSION_CONFIRMED".equals(status)\n                || "VERIFY_REQUIRED".equals(status) || "OBSERVER_ARMED".equals(status);\n    }\n\n    static boolean localFailureBudgetExhausted(int failures) {\n        return failures >= MAX_LOCAL_FAILURES;\n    }''')

# Persist one reserved successor and one status-specific local-failure streak. CHAT claims require a
# concrete readback picker state and successors explicitly apply it.
path = "app/src/main/java/com/shaterguy/chatgptselfrun/SelfRunRolloverCoordinator.java"
replace_once(path,
'''    private static final String FAILURE_PREFIX = "localFailures:";\n    private static final String STORE_PREFS = "selfrun_drive";''',
'''    private static final String FAILURE_PREFIX = "localFailures:";\n    private static final String FAILURE_KEY_PREFIX = "localFailureKey:";\n    private static final String STORE_PREFS = "selfrun_drive";''')
replace_once(path,
'''        String model = store.pendingModel();\n        String reasoning = store.pendingReasoning();\n        if (SelfRunStore.MODE_WORK.equals(store.mode()) && !SelfRunProtocol.validWorkProfile(model, reasoning)) {\n            return failed(cause);\n        }\n        String successorRunId = SelfRunRunId.create();''',
'''        String model = store.pendingModel();\n        String reasoning = store.pendingReasoning();\n        if (SelfRunStore.MODE_WORK.equals(store.mode()) && !SelfRunProtocol.validWorkProfile(model, reasoning)) {\n            return failed(cause);\n        }\n        String chatPickerSelection = SelfRunStore.MODE_CHAT.equals(store.mode())\n                ? ChatPickerStateStore.effectiveForRun(app, predecessorRunId) : "";\n        if (SelfRunStore.MODE_CHAT.equals(store.mode())\n                && !ChatReasoningPreferenceStore.shouldApply(chatPickerSelection)) return failed(cause);\n        String successorRunId = SelfRunRunId.create();''')
replace_once(path,
'''            next.put("chatReasoning", ChatReasoningPreferenceStore.selectionForRun(app, predecessorRunId));''',
'''            next.put("chatPickerSelection", chatPickerSelection);''')
replace_once(path,
'''        String chatReasoning = state.optString("chatReasoning", ChatReasoningPreferenceStore.KEEP);\n        if (!ChatReasoningPreferenceStore.save(app, successorRunId, chatReasoning)) return failed(cause);''',
'''        String chatPickerSelection = state.optString("chatPickerSelection",\n                state.optString("chatReasoning", ChatReasoningPreferenceStore.KEEP));\n        if (SelfRunStore.MODE_CHAT.equals(state.optString("mode"))\n                && !ChatReasoningPreferenceStore.shouldApply(chatPickerSelection)) return failed(cause);\n        String successorChatSelection = SelfRunStore.MODE_CHAT.equals(state.optString("mode"))\n                ? chatPickerSelection : ChatReasoningPreferenceStore.KEEP;\n        if (!ChatReasoningPreferenceStore.save(app, successorRunId, successorChatSelection)) return failed(cause);''')
replace_once(path,
'''    int incrementLocalFailure(String runId) {\n        if (!SelfRunProtocolRules.validRunId(runId)) return Integer.MAX_VALUE;\n        String key = FAILURE_PREFIX + runId;\n        int current = prefs.getInt(key, 0);\n        int next = current == Integer.MAX_VALUE ? Integer.MAX_VALUE : current + 1;\n        return prefs.edit().putInt(key, next).commit() ? next : Integer.MAX_VALUE;\n    }\n\n    void clearLocalFailures(String runId) {\n        if (SelfRunProtocolRules.validRunId(runId)) prefs.edit().remove(FAILURE_PREFIX + runId).commit();\n    }''',
'''    int incrementLocalFailure(String runId) {\n        return recordLocalFailure(runId, "GENERIC");\n    }\n\n    int recordLocalFailure(String runId, String rawKey) {\n        if (!SelfRunProtocolRules.validRunId(runId)) return Integer.MAX_VALUE;\n        String normalized = SelfRunRolloverPolicy.normalizeCause(rawKey);\n        String countKey = FAILURE_PREFIX + runId, statusKey = FAILURE_KEY_PREFIX + runId;\n        String prior = prefs.getString(statusKey, "");\n        int current = normalized.equals(prior) ? prefs.getInt(countKey, 0) : 0;\n        int next = current == Integer.MAX_VALUE ? Integer.MAX_VALUE : current + 1;\n        return prefs.edit().putString(statusKey, normalized).putInt(countKey, next).commit()\n                ? next : Integer.MAX_VALUE;\n    }\n\n    void clearLocalFailures(String runId) {\n        if (SelfRunProtocolRules.validRunId(runId)) prefs.edit()\n                .remove(FAILURE_PREFIX + runId).remove(FAILURE_KEY_PREFIX + runId).commit();\n    }''')

# Extend pure policy regression coverage.
path = "app/src/test/java/com/shaterguy/chatgptselfrun/SelfRunRolloverPolicyTest.java"
replace_once(path,
'''    @Test public void lineageCauseSetBlocksSameCauseFromRecurring() {''',
'''    @Test public void repeatedContinuationFailuresAreBoundedButTransientStatesGetGrace() {\n        long started=1_000L;\n        assertFalse(SelfRunRolloverPolicy.shouldCountContinuationFailure("UNKNOWN",started,5_999L));\n        assertTrue(SelfRunRolloverPolicy.shouldCountContinuationFailure("UNKNOWN",started,6_000L));\n        assertFalse(SelfRunRolloverPolicy.shouldCountContinuationFailure(SelfRunContinuationDom.STOP,started,15_999L));\n        assertTrue(SelfRunRolloverPolicy.shouldCountContinuationFailure(SelfRunContinuationDom.STOP,started,16_000L));\n        assertTrue(SelfRunRolloverPolicy.hardContinuationFailureStatus("SUBMISSION_FAILED"));\n        assertTrue(SelfRunRolloverPolicy.continuationProgressStatus("READY_TO_SUBMIT"));\n    }\n\n    @Test public void lineageCauseSetBlocksSameCauseFromRecurring() {''')

# Structural regression pins the verification fixes in the service/coordinator wiring.
path = "app/src/test/java/com/shaterguy/chatgptselfrun/SelfRunRolloverWiringTest.java"
replace_once(path,
'''    @Test public void successorBootstrapCarriesPredecessorReferences() throws Exception {''',
'''    @Test public void continuationFailureEvidenceIsNotClearedBeforeClassification() throws Exception {\n        String service=src("SelfRunService.java");\n        assertFalse(service.contains("if(isContinuationDiagnosticPhase(phase))rollover.clearLocalFailures(runId)"));\n        assertTrue(service.contains("shouldCountContinuationFailure(status,store.phaseStartedAt(),System.currentTimeMillis())"));\n        assertTrue(service.contains("rollover.recordLocalFailure(runId,status)"));\n        assertTrue(service.contains("CONTINUATION_NO_PROGRESS"));\n        String coordinator=src("SelfRunRolloverCoordinator.java");\n        assertTrue(coordinator.contains("ChatPickerStateStore.effectiveForRun"));\n        assertTrue(coordinator.contains("chatPickerSelection"));\n    }\n\n    @Test public void successorBootstrapCarriesPredecessorReferences() throws Exception {''')

# Existing WebView instrumentation now verifies KEEP captures the exact current picker value without changing it.
path = "app/src/androidTest/java/com/shaterguy/chatgptselfrun/ChatReasoningHierarchicalMenuAndroidTest.java"
replace_once(path,
'''    @Test public void matchingCurrentValueSkipsAllReasoningSelectionUi() throws Exception {''',
'''    @Test public void keepCapturesCurrentPickerValueWithoutChangingIt() throws Exception {\n        try (ActivityScenario<SelfRunNewActivity> scenario = ActivityScenario.launch(SelfRunNewActivity.class)) {\n            AtomicReference<WebView> web = new AtomicReference<>();\n            load(scenario, web, englishFixture());\n            String runId = "SR-CAPTURE-CURRENT";\n            scenario.onActivity(activity -> assertTrue(ChatReasoningPreferenceStore.save(\n                    activity, runId, ChatReasoningPreferenceStore.KEEP)));\n            JSONObject ready = runToReady(scenario, web,\n                    SelfRunDom.prepareInitialContext(PROJECT_URL, SelfRunStore.MODE_CHAT, runId));\n            assertEquals("xhigh", ready.getJSONObject("diagnostics").getString("observed"));\n            assertEquals("capture-current", ready.getJSONObject("diagnostics").getString("action"));\n            assertEquals("0", read(scenario, web, "String(window.optionClicks)"));\n            assertEquals("Extra high", read(scenario, web, "document.getElementById('reasoning-trigger').textContent"));\n        }\n    }\n\n    @Test public void matchingCurrentValueSkipsAllReasoningSelectionUi() throws Exception {''')

# V-03: execute the real SharedPreferences/coordinator state machine under Android instrumentation.
write("app/src/androidTest/java/com/shaterguy/chatgptselfrun/SelfRunRolloverCoordinatorAndroidTest.java", r'''package com.shaterguy.chatgptselfrun;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Collections;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public final class SelfRunRolloverCoordinatorAndroidTest {
    private static final String ACCOUNT = "acct01";
    private static final String BASE = "BaseFolder12345";
    private static final String JOB = "JobFolder12345";
    private static final String TURN = "TurnDocument12345";
    private static final String CONVERSATION = "https://chatgpt.com/c/12345678-1234-1234-1234-123456789abc";
    private Context context;

    @Before public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        clearAll();
    }

    @After public void tearDown() { clearAll(); }

    @Test public void freshClaimStartsOneSuccessorAndPreservesExplicitChatPickerState() throws Exception {
        SelfRunStore store = predecessor();
        String predecessor = store.runId();
        SelfRunRolloverCoordinator coordinator = new SelfRunRolloverCoordinator(context);
        SelfRunRolloverCoordinator.Result first = coordinator.beginOrResume(store, SelfRunRolloverPolicy.ROUTE_MISMATCH);
        assertEquals(SelfRunRolloverCoordinator.RESULT_STARTED, first.status);
        assertFalse(first.successorRunId.isEmpty());
        assertNotEquals(predecessor, first.successorRunId);
        assertEquals(first.successorRunId, store.runId());
        assertEquals("", store.conversationUrl());
        assertEquals(ChatReasoningPreferenceStore.EXTRA_HIGH,
                ChatReasoningPreferenceStore.selectionForRun(context, first.successorRunId));
        JSONObject history = new SelfRunHistoryStore(context).get(predecessor);
        assertNotNull(history);
        assertEquals(SelfRunRolloverCoordinator.PHASE_ROLLED_OVER, history.optString("phase"));
        assertTrue(history.optBoolean("terminal"));
        String once = store.runId();
        coordinator.beginOrResume(store, SelfRunRolloverPolicy.ROUTE_MISMATCH);
        assertEquals(once, store.runId());
        assertFalse(coordinator.hasPendingClaim());
    }

    @Test public void processRecreationUsesReservedSuccessorIdAndClearsClaim() throws Exception {
        SelfRunStore store = predecessor();
        String predecessor = store.runId();
        String successor = SelfRunRunId.create();
        writeClaim(store, successor, false);
        assertTrue(new SelfRunRolloverCoordinator(context).hasPendingClaim());

        store = new SelfRunStore(context);
        SelfRunRolloverCoordinator coordinator = new SelfRunRolloverCoordinator(context);
        SelfRunRolloverCoordinator.Result resumed = coordinator.resumePending(store);
        assertTrue(resumed.started());
        assertEquals(successor, resumed.successorRunId);
        assertEquals(successor, store.runId());
        assertNotEquals(predecessor, store.runId());
        assertFalse(coordinator.hasPendingClaim());
    }

    @Test public void predecessorAlreadyTerminalStillResumesSameReservedSuccessor() throws Exception {
        SelfRunStore store = predecessor();
        String successor = SelfRunRunId.create();
        writeClaim(store, successor, false);
        context.getSharedPreferences("selfrun_drive", Context.MODE_PRIVATE).edit()
                .putBoolean("active", false).putBoolean("paused", false).putBoolean("userStopped", false)
                .putString("phase", SelfRunRolloverCoordinator.PHASE_ROLLED_OVER).commit();

        SelfRunRolloverCoordinator.Result resumed = new SelfRunRolloverCoordinator(context)
                .resumePending(new SelfRunStore(context));
        assertTrue(resumed.started());
        assertEquals(successor, resumed.successorRunId);
        assertEquals(successor, new SelfRunStore(context).runId());
    }

    @Test public void successorAlreadyStartedBeforeClaimCleanupIsAdoptedWithoutAnotherRun() throws Exception {
        SelfRunStore store = predecessor();
        String successor = SelfRunRunId.create();
        writeClaim(store, successor, false);
        String requirement = store.requirement();
        assertTrue(ChatReasoningPreferenceStore.save(context, successor, ChatReasoningPreferenceStore.EXTRA_HIGH));
        assertTrue(SelfRunSignalTransport.mark(context, successor));
        store.start(successor, SelfRunStore.MODE_CHAT, SelfRunScript.GENERAL_CHAT_URL,
                requirement, Collections.emptyList());

        SelfRunRolloverCoordinator coordinator = new SelfRunRolloverCoordinator(context);
        SelfRunRolloverCoordinator.Result resumed = coordinator.resumePending(store);
        assertEquals(SelfRunRolloverCoordinator.RESULT_ALREADY_STARTED, resumed.status);
        assertEquals(successor, store.runId());
        assertFalse(coordinator.hasPendingClaim());
    }

    @Test public void userStopCancelsPendingClaimWithoutStartingSuccessor() throws Exception {
        SelfRunStore store = predecessor();
        String predecessor = store.runId();
        String successor = SelfRunRunId.create();
        writeClaim(store, successor, false);
        store.stopByUser();
        SelfRunRolloverCoordinator coordinator = new SelfRunRolloverCoordinator(context);
        SelfRunRolloverCoordinator.Result resumed = coordinator.resumePending(store);
        assertEquals(SelfRunRolloverCoordinator.RESULT_FAILED, resumed.status);
        assertEquals(predecessor, store.runId());
        assertTrue(store.userStopped());
        assertFalse(coordinator.hasPendingClaim());
    }

    private SelfRunStore predecessor() {
        SelfRunStore store = new SelfRunStore(context);
        store.bindBaseFolder(ACCOUNT, BASE, "Runs", "https://drive.google.com/drive/folders/" + BASE,
                System.currentTimeMillis());
        String runId = SelfRunRunId.create();
        assertTrue(ChatReasoningPreferenceStore.save(context, runId, ChatReasoningPreferenceStore.KEEP));
        assertTrue(ChatPickerStateStore.saveObserved(context, runId, ChatReasoningPreferenceStore.EXTRA_HIGH));
        assertTrue(SelfRunSignalTransport.mark(context, runId));
        store.start(runId, SelfRunStore.MODE_CHAT, SelfRunScript.GENERAL_CHAT_URL,
                "original requirement", Collections.emptyList());
        store.saveJobFolder(JOB);
        store.saveTurnDocument(TURN, "https://docs.google.com/document/d/" + TURN + "/edit");
        store.captureConversationUrl(CONVERSATION);
        assertEquals(CONVERSATION, store.conversationUrl());
        return store;
    }

    private void writeClaim(SelfRunStore store, String successor, boolean priorTerminal) throws Exception {
        JSONObject claim = new JSONObject();
        claim.put("predecessorRunId", store.runId());
        claim.put("successorRunId", successor);
        claim.put("predecessorJobFolderId", store.jobFolderId());
        claim.put("predecessorTurnDocumentId", store.turnDocumentId());
        claim.put("predecessorOriginalRequirementStored", true);
        claim.put("projectUrl", store.projectUrl());
        claim.put("mode", store.mode());
        claim.put("model", "");
        claim.put("reasoning", "");
        claim.put("chatPickerSelection", ChatReasoningPreferenceStore.EXTRA_HIGH);
        claim.put("cause", SelfRunRolloverPolicy.ROUTE_MISMATCH);
        claim.put("priorCauses", "");
        claim.put("claimedAt", System.currentTimeMillis());
        SharedPreferences prefs = context.getSharedPreferences("selfrun_drive_rollover", Context.MODE_PRIVATE);
        assertTrue(prefs.edit().putString("currentClaim", claim.toString()).commit());
        if (priorTerminal) context.getSharedPreferences("selfrun_drive", Context.MODE_PRIVATE).edit()
                .putBoolean("active", false).putString("phase", SelfRunRolloverCoordinator.PHASE_ROLLED_OVER).commit();
    }

    private void clearAll() {
        for (String name : new String[]{"selfrun_drive", "selfrun_drive_rollover", "selfrun_drive_signal_transport",
                "selfrun_drive_chat_reasoning", "selfrun_drive_bootstrap_runs", "selfrun_drive_chat_picker_state",
                "selfrun_drive_history"}) {
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit();
        }
    }
}
''')

# Ensure the actual coordinator state-transition instrumentation is executed by the canonical TEST workflow.
path = ".github/workflows/build-drive-test.yml"
replace_once(path,
'''-Pandroid.testInstrumentationRunnerArguments.class=com.shaterguy.chatgptselfrun.WorkPreferenceDomWebViewTest,com.shaterguy.chatgptselfrun.ChatReasoningDelayedDomWebViewTest,com.shaterguy.chatgptselfrun.ChatReasoningHierarchicalMenuAndroidTest,com.shaterguy.chatgptselfrun.WorkAdvancedMenuAndroidTest;''',
'''-Pandroid.testInstrumentationRunnerArguments.class=com.shaterguy.chatgptselfrun.WorkPreferenceDomWebViewTest,com.shaterguy.chatgptselfrun.ChatReasoningDelayedDomWebViewTest,com.shaterguy.chatgptselfrun.ChatReasoningHierarchicalMenuAndroidTest,com.shaterguy.chatgptselfrun.WorkAdvancedMenuAndroidTest,com.shaterguy.chatgptselfrun.SelfRunRolloverCoordinatorAndroidTest;''')

print("v1.7.0 rollover verification rework applied")
