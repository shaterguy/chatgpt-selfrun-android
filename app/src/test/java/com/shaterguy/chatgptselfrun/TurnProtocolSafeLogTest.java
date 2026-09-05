package com.shaterguy.chatgptselfrun;

import org.junit.Test;
import java.lang.reflect.Method;
import static org.junit.Assert.*;

public final class TurnProtocolSafeLogTest {
    @Test public void stateDetailsSurviveExistingPrivacyFilter() throws Exception {
        String[][] cases={{"turn_request","canonical_post","THINKING"},
                {"stream_handoff","stream_handoff","THINKING"},
                {"completion_ignored","message_stream_complete","THINKING"},
                {"answering_started","assistant_final_text","ANSWERING"},
                {"complete","message_stream_complete","COMPLETE"}};
        Method sanitize=SelfRunRunLog.class.getDeclaredMethod("sanitize",String.class);
        sanitize.setAccessible(true);
        for(String[] item:cases){
            String detail=TurnProtocolLogBridge.protocolDetails(item[0],item[1],item[2]);
            assertFalse(detail.isEmpty());
            assertEquals(detail,sanitize.invoke(null,detail));
            assertTrue(detail.contains("binding=current"));
        }
        assertEquals("redacted",sanitize.invoke(null,"authorization=fixture-secret"));
        assertEquals("redacted",sanitize.invoke(null,"token=fixture-secret"));
        assertEquals("",TurnProtocolLogBridge.protocolDetails("complete","untrusted","COMPLETE"));
    }
}
