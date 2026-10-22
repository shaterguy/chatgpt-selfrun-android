package com.shaterguy.chatgptselfrun;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public final class SelfRunTerminalControlDeliveryTest {
    private static final SelfRunTerminalControlDelivery.Entry DONE =
            new SelfRunTerminalControlDelivery.Entry("task", "task:turn:1", "request", "folder", "https://chatgpt.com/c/owned", "DONE", "STAGE_DONE");
    private static final class Remote implements SelfRunTerminalControlDelivery.Remote {
        JSONObject control;
        Remote() throws Exception { control = new JSONObject().put("schema", "selfrun-task-control-v1").put("task_id", "task").put("state", "RUNNING").put("control_epoch", 2); }
        int authorizations, writes;
        boolean lostWriteResponse;
        public String authorize() { authorizations++; return "fresh-token-" + authorizations; }
        public JSONObject read(SelfRunTerminalControlDelivery.Entry entry, String token) throws Exception {
            assertEquals("fresh-token-" + authorizations, token);
            return new JSONObject(control.toString());
        }
        public void write(SelfRunTerminalControlDelivery.Entry entry, String token, JSONObject value) throws Exception {
            assertEquals("fresh-token-" + authorizations, token); writes++;
            control = new JSONObject(value.toString());
            if (lostWriteResponse) throw new java.io.IOException("response lost after successful write");
        }
    }
    @Test public void terminalSendUsesFreshAuthorizationAndConfirmsTheActualControl() throws Exception {
        Remote remote = new Remote();
        SelfRunTerminalControlDelivery.deliver(DONE, remote, () -> true);
        assertEquals(1, remote.authorizations); assertEquals(1, remote.writes);
        assertEquals("DONE", remote.control.getString("state"));
        assertEquals(3, remote.control.getLong("control_epoch"));
    }
    @Test public void lostWriteResponseIsReadBackWithoutASecondMutation() throws Exception {
        Remote remote = new Remote(); remote.lostWriteResponse = true;
        SelfRunTerminalControlDelivery.deliver(DONE, remote, () -> true);
        SelfRunTerminalControlDelivery.deliver(DONE, remote, () -> true);
        assertEquals(1, remote.writes); assertEquals(2, remote.authorizations);
    }
    @Test public void cancelledDeliveryCannotOverwriteAnExplicitNewExecution() throws Exception {
        Remote remote = new Remote();
        SelfRunTerminalControlDelivery.deliver(DONE, remote, () -> false);
        assertEquals(0, remote.writes); assertEquals("RUNNING", remote.control.getString("state"));
    }
    @Test public void stopUsesTheSameReliableTerminalPath() throws Exception {
        Remote remote = new Remote();
        var stop = new SelfRunTerminalControlDelivery.Entry(DONE.taskId(), DONE.turnId(), DONE.requestId(), DONE.folderId(), DONE.conversationUrl(), "STOPPED", "USER_STOP");
        SelfRunTerminalControlDelivery.deliver(stop, remote, () -> true);
        assertEquals("STOPPED", remote.control.getString("state")); assertEquals(3, remote.control.getLong("control_epoch"));
    }
    @Test public void interruptedMetadataOnlyCreationIsCompleted() throws Exception {
        Remote remote = new Remote(); remote.control = new JSONObject();
        SelfRunTerminalControlDelivery.deliver(DONE, remote, () -> true);
        assertEquals(1, remote.writes); assertEquals("DONE", remote.control.getString("state"));
        assertEquals(1, remote.control.getLong("control_epoch"));
    }
}
