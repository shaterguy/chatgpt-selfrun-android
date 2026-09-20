package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.*;

public final class SelfRunDeliverableLinksWiringTest {
    @Test public void doneProjectionPersistsOptionalLinksWithoutSchemaMigration() throws Exception {
        String engine = src("SelfRun3Engine.java");
        String ledger = src("SelfRun3Ledger.java");
        String coordinator = src("SelfRun3Coordinator.java");
        String store = src("SelfRunStore.java");
        String historyStore = src("SelfRunHistoryStore.java");

        assertTrue(engine.contains("SelfRunDeliverableLinks.fromResult(result)"));
        assertTrue(engine.contains("put(h,\"deliverableLinks\",deliverableLinks)"));
        assertTrue(coordinator.contains("store.setDeliverableLinks(SelfRunDeliverableLinks.fromResult(state.text(\"result\")))"));
        assertTrue(store.contains("KEY_DELIVERABLE_LINKS"));
        assertTrue(store.contains("JSONArray deliverableLinks()"));
        assertTrue(historyStore.contains("item.put(\"deliverableLinks\", store.deliverableLinks())"));
        assertTrue(ledger.contains("CREATE TABLE turns"));
        assertFalse(ledger.contains("ALTER TABLE"));
    }

    @Test public void allRequestedUserSurfacesRenderTheSameValidatedLinks() throws Exception {
        String main = src("MainActivity.java");
        String history = src("SelfRunHistoryActivity.java");
        String detail = src("SelfRunDetailActivity.java");

        assertTrue(main.contains("SelfRunDeliverableLinks.render(this, deliverablePanel, store.deliverableLinks())"));
        assertTrue(history.contains("SelfRunDeliverableLinks.render(this, deliverablePanel, item.optJSONArray(\"deliverableLinks\"))"));
        assertTrue(detail.contains("item.optJSONArray(\"deliverableLinks\")"));
        assertTrue(detail.contains("entry.optJSONArray(\"deliverableLinks\")"));
    }

    private static String src(String file) throws Exception {
        return read("app/src/main/java/com/shaterguy/chatgptselfrun/" + file,
                "src/main/java/com/shaterguy/chatgptselfrun/" + file);
    }

    private static String read(String first, String fallback) throws Exception {
        Path path = Paths.get(first);
        if (!Files.exists(path)) path = Paths.get(fallback);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
