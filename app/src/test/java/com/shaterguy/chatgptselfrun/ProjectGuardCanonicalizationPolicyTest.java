package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ProjectGuardCanonicalizationPolicyTest {
    private static final String ID = "g-p-6a582c824ba08191ac7e74e9bad721fc";
    private static final String PROJECT = "https://chatgpt.com/g/" + ID + "/project";

    @Test public void producerAndAllProjectGuardsShareOneCanonicalPredicate() throws Exception {
        String prelude = ProjectUrlPolicy.webProjectIdentityPrelude();
        assertTrue(prelude.contains("__srProjectPrefix=\"g-p-\""));
        assertTrue(prelude.contains("__srProjectTokenLength=32"));
        assertTrue(prelude.contains("__srProjectMaxIdLength=160"));
        assertTrue(prelude.contains("value.length<=canonicalEnd+1"));
        assertTrue(prelude.contains("__srProjectOpaque(slug)"));
        assertTrue(prelude.contains("return __srProjectOpaque(slug)?value.substring(0,canonicalEnd):value"));

        String bootstrap = SelfRunDom.prepareInitialContext(
                PROJECT, SelfRunStore.MODE_CHAT, "SR-GUARD-CONTRACT");
        String submission = SelfRunContinuationDom.prepareBootstrap(
                PROJECT, "SELF_RUN_GUARD_CONTRACT", "guard-marker");
        String work = WorkPreferenceDom.modelForProject(PROJECT, "sol");
        assertTrue(bootstrap.contains(prelude));
        assertTrue(submission.contains(prelude));
        assertTrue(work.contains(prelude));
        assertTrue(bootstrap.contains("__srCanonicalProjectId(afterProject('g'))"));
        assertTrue(submission.contains("__srCanonicalProjectId(after('g'))"));
        assertTrue(work.contains("__srCanonicalProjectId(__wpRaw)"));

        String producer = source("ProjectUrlPolicy.java");
        String initialConsumer = source("SelfRunDom.java");
        String continuationConsumer = source("SelfRunContinuationDom.java");
        String workConsumer = source("WorkPreferenceDom.java");
        assertTrue(producer.contains("static String webProjectIdentityPrelude()"));
        assertTrue(initialConsumer.contains("ProjectUrlPolicy.webProjectIdentityPrelude()"));
        assertTrue(continuationConsumer.contains("ProjectUrlPolicy.webProjectIdentityPrelude()"));
        assertTrue(workConsumer.contains("ProjectUrlPolicy.webProjectIdentityPrelude()"));
        assertFalse(initialConsumer.contains("const actualProject=afterProject('g')"));
        assertFalse(continuationConsumer.contains("const actualProject=after('g')"));
        assertFalse(workConsumer.contains("__wpI=__wpParts.indexOf('g'),__wpActual=__wpI>=0"));
    }

    private static String source(String name) throws Exception {
        Path path = Paths.get("app/src/main/java/com/shaterguy/chatgptselfrun/" + name);
        if (!Files.exists(path)) path = Paths.get("src/main/java/com/shaterguy/chatgptselfrun/" + name);
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
