package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Prevents producer/consumer request-profile engine version drift. */
public final class RequestProfileVersionContractTest {
    @Test public void producerOwnsOneCanonicalEngineVersionAndKeepsCapturedSchema() throws Exception {
        assertEquals("calibration-v2", RequestProfileScript.ENGINE_VERSION);
        String producer = source("RequestProfileScript.java");
        assertEquals(1, occurrences(producer, "calibration-v2"));
        assertTrue(producer.contains("static final String ENGINE_VERSION"));
        assertTrue(producer.contains("__ENGINE_VERSION__"));
        assertTrue(producer.contains("SelfRunScript.quote(ENGINE_VERSION)"));

        String script = RequestProfileScript.documentStartScript();
        assertEquals(2, occurrences(script, RequestProfileScript.ENGINE_VERSION));
        assertTrue(script.contains("chatgpt-request-snapshot-calibration-v1@2026-08-28"));
        assertTrue(script.contains("p==='/backend-api/conversation'||p==='/backend-api/f/conversation'"));
        assertTrue(script.contains("if(!probe.eligible)return nativeFetch(input,init)"));
    }

    @Test public void restoredTargetSurvivesWebViewRecreationWithoutRelaxingFailClosed() {
        String script = RequestProfileScript.documentStartScript();
        assertTrue(script.contains("selfrun-drive:request-profile-target:v1"));
        assertTrue(script.contains("localStorage.setItem(TARGET_STORE,JSON.stringify(state.target))"));
        assertTrue(script.contains("localStorage.getItem(TARGET_STORE)"));
        assertTrue(script.contains("state.target=restoreTarget();"));
        assertTrue(script.contains("reason:'target_restored'"));
        assertTrue(script.contains("if(!validTarget(t)){localStorage.removeItem(TARGET_STORE);return null;}"));
        assertTrue(script.contains("if(!t||!t.ready)fail('target_not_ready')"));
        assertTrue(script.contains("t.profileVersion!==PROFILE_VERSION"));
    }

    @Test public void bootstrapAndWorkConsumersUseTheSharedExpressionWithoutLiterals() throws Exception {
        String expression = RequestProfileScript.engineAvailableExpression();
        String bootstrapScript = BootstrapModeDom.inline(SelfRunStore.MODE_CHAT, "SR-VERSION");
        String workScript = WorkPreferenceDom.modelForProject(
                "https://chatgpt.com/g/g-p-test", "sol");
        assertTrue(bootstrapScript.contains(expression));
        assertTrue(workScript.contains(expression));

        String bootstrapSource = source("BootstrapModeDom.java");
        String workSource = source("WorkPreferenceDom.java");
        String chatSource = source("ChatReasoningOptionDom.java");
        assertTrue(bootstrapSource.contains("RequestProfileScript.engineAvailableExpression()"));
        assertTrue(workSource.contains("RequestProfileScript.engineAvailableExpression()"));
        assertFalse(bootstrapSource.contains("calibration-v1"));
        assertFalse(bootstrapSource.contains("calibration-v2"));
        assertFalse(workSource.contains("calibration-v1"));
        assertFalse(workSource.contains("calibration-v2"));
        assertFalse(chatSource.contains("calibration-v1"));
        assertFalse(chatSource.contains("calibration-v2"));
        assertFalse(chatSource.contains("__selfRunRequestProfileEngine.version"));
    }

    @Test public void absenceMismatchAndOperationFailuresExposeOnlyFixedSafeDiagnostics() {
        String bootstrap = BootstrapModeDom.inline(SelfRunStore.MODE_CHAT, "SR-SAFE");
        assertTrue(bootstrap.contains("profileStage:'availability',enginePresent,engineVersionMatch"));
        assertTrue(bootstrap.contains("'request profile engine absent',profileAvailability"));
        assertTrue(bootstrap.contains("'request profile engine version mismatch',profileAvailability"));
        assertTrue(bootstrap.contains("'request profile initialization rejected'"));
        assertFalse(bootstrap.contains("document-start injection"));
        assertFalse(bootstrap.contains("String(error"));
        assertFalse(bootstrap.contains("error?.message"));

        String work = WorkPreferenceDom.modelForProject(
                "https://chatgpt.com/g/g-p-test", "terra");
        assertTrue(work.contains("profileStage:'availability',enginePresent,engineVersionMatch"));
        assertTrue(work.contains("'request profile engine absent',profileAvailability"));
        assertTrue(work.contains("'request profile engine version mismatch',profileAvailability"));
        assertTrue(work.contains("'request profile target rejected'"));
        assertFalse(work.contains("String(error"));
        assertFalse(work.contains("error?.message"));

        String chat = ChatReasoningOptionDom.inline(
                ChatReasoningPreferenceStore.MEDIUM, "SR-SAFE");
        assertTrue(chat.contains("'request profile Chat target rejected'"));
        assertTrue(chat.contains("enginePresent:true,engineVersionMatch:true,operationOk:false"));
        assertFalse(chat.contains("String(error"));
        assertFalse(chat.contains("error?.message"));
    }

    private static String source(String file) throws Exception {
        Path path = Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/" + file);
        if (!Files.exists(path)) {
            path = Paths.get("src/main/java/com/shaterguy/chatgptselfrun/" + file);
        }
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }

    private static int occurrences(String value, String needle) {
        int count = 0;
        for (int at = value.indexOf(needle); at >= 0;
             at = value.indexOf(needle, at + needle.length())) {
            count++;
        }
        return count;
    }
}
