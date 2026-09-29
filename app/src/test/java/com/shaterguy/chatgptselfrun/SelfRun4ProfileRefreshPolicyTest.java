package com.shaterguy.chatgptselfrun;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

public final class SelfRun4ProfileRefreshPolicyTest {
    @Before public void reset() { ProfileRegistry.resetForTests(); }
    @After public void cleanup() { ProfileRegistry.resetForTests(); }

    @Test public void automaticSuccessorUsesRegistryChangeMadeAfterPreviousTurn() throws Exception {
        ProfileRegistry.Profile target = ProfileRegistry.listWork().get(0);
        JSONArray previousTurn = SelfRun4DriveWebAdapter.profileOperationsForDispatch(
                SelfRunStore.MODE_WORK, target.signalModel, target.signalReasoning);

        JSONObject changed = new JSONObject(ProfileRegistry.exportWorkJson("test"));
        JSONObject item = find(changed.getJSONArray("profiles"), target);
        JSONArray operations = item.getJSONArray("operations");
        boolean updated = false;
        for (int i = 0; i < operations.length(); i++) {
            JSONObject operation = operations.getJSONObject(i);
            if ("service_tier".equals(operation.getString("path"))
                    && "SET".equals(operation.getString("op"))) {
                operation.put("value", "successor-tier");
                item.getJSONObject("request").put("service_tier", "successor-tier");
                updated = true;
            }
        }
        assertTrue(updated);
        JSONObject captured = new JSONObject()
                .put("mode", "work")
                .put("operations", operations);
        item.put("fingerprint", ProfileRegistry.parseCaptured(captured.toString()).fingerprint);

        ProfileRegistry.replaceCanonicalSnapshot(
                ProfileRegistry.Mode.WORK, changed.toString(), "successor-v2");
        JSONArray successorTurn = SelfRun4DriveWebAdapter.profileOperationsForDispatch(
                SelfRunStore.MODE_WORK, target.signalModel, target.signalReasoning);

        assertNotEquals(previousTurn.toString(), successorTurn.toString());
        assertTrue(successorTurn.toString().contains("successor-tier"));
    }

    @Test public void removedCanonicalProfileCannotBeSentByLaterDispatch() throws Exception {
        ProfileRegistry.Profile target = ProfileRegistry.listWork().get(0);
        JSONObject changed = new JSONObject(ProfileRegistry.exportWorkJson("test"));
        JSONArray profiles = changed.getJSONArray("profiles");
        int index = findIndex(profiles, target);
        assertTrue(index >= 0);
        profiles.remove(index);
        assertTrue(profiles.length() > 0);

        ProfileRegistry.replaceCanonicalSnapshot(
                ProfileRegistry.Mode.WORK, changed.toString(), "successor-removed");
        assertThrows(IllegalStateException.class, () ->
                SelfRun4DriveWebAdapter.profileOperationsForDispatch(
                        SelfRunStore.MODE_WORK, target.signalModel, target.signalReasoning));
    }

    private static JSONObject find(JSONArray profiles, ProfileRegistry.Profile target) throws Exception {
        int index = findIndex(profiles, target);
        if (index < 0) throw new AssertionError("target profile not found");
        return profiles.getJSONObject(index);
    }

    private static int findIndex(JSONArray profiles, ProfileRegistry.Profile target) throws Exception {
        for (int i = 0; i < profiles.length(); i++) {
            JSONObject signal = profiles.getJSONObject(i).getJSONObject("signal");
            if (target.signalModel.equals(signal.optString("model"))
                    && target.signalReasoning.equals(signal.getString("reasoning"))) {
                return i;
            }
        }
        return -1;
    }
}
