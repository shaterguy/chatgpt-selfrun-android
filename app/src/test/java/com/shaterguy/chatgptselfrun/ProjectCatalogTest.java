package com.shaterguy.chatgptselfrun;

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

    @Test public void duplicateDisplayNamesAreDisambiguatedWithoutChangingUrls() {
        List<ProjectCatalog.Entry> entries=Arrays.asList(
                new ProjectCatalog.Entry("Alpha","https://chatgpt.com/g/g-p-a/project"),
                new ProjectCatalog.Entry("Alpha","https://chatgpt.com/g/g-p-b/project"),
                new ProjectCatalog.Entry("Beta","https://chatgpt.com/g/g-p-c/project"));
        assertEquals(Arrays.asList("Alpha","Alpha (2)","Beta"),ProjectCatalog.displayLabels(entries));
        assertEquals(1,ProjectCatalog.indexOfUrl(entries,"https://www.chatgpt.com/g/g-p-b?ignored=1"));
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
