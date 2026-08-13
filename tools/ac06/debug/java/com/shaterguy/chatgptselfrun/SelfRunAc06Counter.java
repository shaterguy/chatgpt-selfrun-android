package com.shaterguy.chatgptselfrun;

import org.json.JSONObject;

import java.util.concurrent.atomic.AtomicLong;

final class SelfRunAc06Counter {
    private static final AtomicLong LOG_CALLS = new AtomicLong();
    private static final AtomicLong LOG_WRITE_BATCHES = new AtomicLong();
    private static final AtomicLong LOG_LINES_WRITTEN = new AtomicLong();
    private static final AtomicLong STATE_WRITES = new AtomicLong();
    private static final AtomicLong HISTORY_WRITES = new AtomicLong();

    private SelfRunAc06Counter() {}

    static void reset() {
        LOG_CALLS.set(0L);
        LOG_WRITE_BATCHES.set(0L);
        LOG_LINES_WRITTEN.set(0L);
        STATE_WRITES.set(0L);
        HISTORY_WRITES.set(0L);
    }

    static void logCall() {
        LOG_CALLS.incrementAndGet();
    }

    static void logPhysicalWrite(int lines) {
        LOG_WRITE_BATCHES.incrementAndGet();
        LOG_LINES_WRITTEN.addAndGet(Math.max(0, lines));
    }

    static void stateWrite() {
        STATE_WRITES.incrementAndGet();
    }

    static void historyWrite() {
        HISTORY_WRITES.incrementAndGet();
    }

    static JSONObject snapshot() throws Exception {
        JSONObject out = new JSONObject();
        out.put("log_calls", LOG_CALLS.get());
        out.put("log_write_batches", LOG_WRITE_BATCHES.get());
        out.put("log_lines_written", LOG_LINES_WRITTEN.get());
        out.put("state_write_transactions", STATE_WRITES.get());
        out.put("history_write_transactions", HISTORY_WRITES.get());
        return out;
    }
}
