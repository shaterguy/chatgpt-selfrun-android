package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import static org.junit.Assert.*;

public class InstrumentationFetchPrivacyPolicyTest {
    @Test public void fetchObservationNeverReadsResponseBodyOrExportsUrl() {
        String js = ConversationSyncInstrumentation.documentStartScript();
        assertFalse(js.contains("response.text()"));
        assertFalse(js.contains("clone().text()"));
        assertFalse(js.contains("response.json()"));
        assertFalse(js.contains("requestUrl"));
        assertTrue(js.contains("u.origin===location.origin"));
        assertTrue(js.contains("PAGE_FETCH_START"));
        assertTrue(js.contains("PAGE_FETCH_COMPLETE"));
    }
}
