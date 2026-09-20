package com.shaterguy.chatgptselfrun;

import org.json.JSONObject;

/** Read-only recovery plan. No state is released until every outstanding result was read. */
final class SelfRun3StoppedRecovery {
    interface Reader { String read(SelfRun3Engine.State execution) throws Exception; }

    static boolean needsRead(SelfRun3Engine.State s) {
        return !s.flag("superseded") && (!s.flag("committed")
                || "WAITING_USER_INTERVENTION".equals(s.text("stage"))
                || "DONE".equals(s.text("stage")));
    }

    static JSONObject plan(SelfRun3Engine.State stopped, Reader reader) throws Exception {
        if (!stopped.flag("taskStopped")) throw new IllegalStateException("STOPPED_STATE_REQUIRED");
        JSONObject results = new JSONObject();
        for (SelfRun3Engine.State execution : stopped.executions()) {
            if (!needsRead(execution)) continue;
            if (execution.resource("resultDocumentId").isEmpty()) {
                if (!"SETUP".equals(execution.text("stage")) && !"PREPARING".equals(execution.text("stage")))
                    throw new IllegalStateException("STOPPED_RESULT_ID_MISSING");
                continue; // No dispatch occurred; normal preparation resolves any create intent.
            }
            String body = reader.read(execution);
            validate(body, execution);
            SelfRun3Engine.put(results, execution.turnId(), body);
        }
        JSONObject payload = new JSONObject();
        SelfRun3Engine.put(payload, "expectedSnapshot", stopped.json());
        SelfRun3Engine.put(payload, "results", results);
        return payload;
    }

    static JSONObject validate(String body, SelfRun3Engine.State execution) {
        if (body == null || body.indexOf('\0') >= 0) throw new IllegalStateException("STOPPED_RESULT_INVALID");
        JSONObject result = SelfRun3StrictJson.parseObject(body.trim());
        if (!SelfRun3Engine.exactResultIdentity(result, execution)
                || !(result.opt("committed") instanceof Boolean))
            throw new IllegalStateException("STOPPED_RESULT_IDENTITY_MISMATCH");
        return result;
    }

    private SelfRun3StoppedRecovery() { }
}
