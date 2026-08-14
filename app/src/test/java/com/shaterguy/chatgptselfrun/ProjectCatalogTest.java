package com.shaterguy.chatgptselfrun;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

public class ProjectCatalogTest {
    @Test public void canonicalProjectUrlRejectsOriginConfusionAndNormalizesPath() {
        assertEquals("https://chatgpt.com/g/g-p-ABC_123-project/project",
                ProjectCatalog.canonicalProjectUrl("https://www.chatgpt.com/g/g-p-ABC_123-project?x=1#y"));
        assertEquals("https://chatgpt.com/g/g-p-ABC_123-project/project",
                ProjectCatalog.canonicalProjectUrl("https://chatgpt.com/g/g-p-ABC_123-project/project/"));
        assertEquals("",ProjectCatalog.canonicalProjectUrl("http://chatgpt.com/g/g-p-ABC/project"));
        assertEquals("",ProjectCatalog.canonicalProjectUrl("https://chatgpt.com.evil.example/g/g-p-ABC/project"));
        assertEquals("",ProjectCatalog.canonicalProjectUrl("https://chatgpt.com@evil.example/g/g-p-ABC/project"));
        assertEquals("",ProjectCatalog.canonicalProjectUrl("https://chatgpt.com:443/g/g-p-ABC/project"));
        assertEquals("",ProjectCatalog.canonicalProjectUrl("https://chatgpt.com/g/g-regular-gpt/project"));
        assertEquals("",ProjectCatalog.canonicalProjectUrl("https://chatgpt.com/g/g-p-ABC/project/extra"));
    }

    @Test public void evaluateJavascriptResultIsValidatedDeduplicatedAndNamed() throws Exception {
        String inner="{\"state\":\"FOUND\",\"marker\":true,\"entries\":["
                +"{\"name\":\"  Alpha   Project  \",\"url\":\"https://chatgpt.com/g/g-p-alpha/project?junk=1\"},"
                +"{\"name\":\"Duplicate\",\"url\":\"https://www.chatgpt.com/g/g-p-alpha\"},"
                +"{\"name\":\"Bad\",\"url\":\"https://evil.example/g/g-p-bad/project\"},"
                +"{\"name\":\"\",\"url\":\"https://chatgpt.com/g/g-p-beta/project\"}]}";
        ProjectCatalog.Probe probe=ProjectCatalog.parseProbe(JSONObject.quote(inner));
        assertEquals("FOUND",probe.state);
        assertTrue(probe.markerSeen);
        assertEquals(2,probe.entries.size());
        assertEquals("Alpha Project",probe.entries.get(0).name);
        assertEquals("https://chatgpt.com/g/g-p-alpha/project",probe.entries.get(0).url);
        assertEquals("프로젝트",probe.entries.get(1).name);
    }

    @Test public void storedCatalogRoundTripsOnlyValidatedEntries() {
        List<ProjectCatalog.Entry> input=Arrays.asList(
                new ProjectCatalog.Entry("A","https://chatgpt.com/g/g-p-a/project"),
                new ProjectCatalog.Entry("A","https://chatgpt.com/g/g-p-b/project"),
                new ProjectCatalog.Entry("Bad","https://example.com/g/g-p-c/project"));
        String json=ProjectCatalog.toStoredJson(input);
        List<ProjectCatalog.Entry> output=ProjectCatalog.fromStoredJson(json);
        assertEquals(2,output.size());
        assertEquals(Arrays.asList("A","A (2)"),ProjectCatalog.displayLabels(output));
        assertEquals(1,ProjectCatalog.indexOfUrl(output,"https://www.chatgpt.com/g/g-p-b"));
    }

    @Test public void trustedPageRequiresExactHttpsChatgptOriginWithoutPortOrUserInfo() {
        assertTrue(ProjectCatalog.isTrustedChatgptPage("https://chatgpt.com/"));
        assertTrue(ProjectCatalog.isTrustedChatgptPage("https://www.chatgpt.com/g/g-p-a/project"));
        assertFalse(ProjectCatalog.isTrustedChatgptPage("http://chatgpt.com/"));
        assertFalse(ProjectCatalog.isTrustedChatgptPage("https://chatgpt.com.evil.example/"));
        assertFalse(ProjectCatalog.isTrustedChatgptPage("https://user@chatgpt.com/"));
        assertFalse(ProjectCatalog.isTrustedChatgptPage("https://chatgpt.com:443/"));
    }
}
