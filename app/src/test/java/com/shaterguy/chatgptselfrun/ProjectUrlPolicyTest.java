package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import static org.junit.Assert.*;

public class ProjectUrlPolicyTest {
    @Test public void canonicalizesOnlyExactProjectRoutes() {
        ProjectUrlPolicy.ProjectRef root = ProjectUrlPolicy.parseProject("https://chatgpt.com/g/g-p-Ab_9");
        ProjectUrlPolicy.ProjectRef project = ProjectUrlPolicy.parseProject("https://chatgpt.com/g/g-p-Ab_9/project");
        ProjectUrlPolicy.ProjectRef conversation = ProjectUrlPolicy.parseProject("https://chatgpt.com/g/g-p-Ab_9/c/abc_123");
        assertNotNull(root); assertNotNull(project); assertNotNull(conversation);
        assertEquals("https://chatgpt.com/g/g-p-Ab_9/project", root.canonicalUrl);
        assertEquals("abc_123", conversation.conversationId);
    }

    @Test public void rejectsOriginAndParsingConfusion() {
        String[] hostile = {"http://chatgpt.com/g/g-p-a", "https://www.chatgpt.com/g/g-p-a", "https://evil.chatgpt.com/g/g-p-a",
                "https://chatgpt.com.evil/g/g-p-a", "https://u@chatgpt.com/g/g-p-a", "https://chatgpt.com:443/g/g-p-a",
                "https://chatgpt.com/g/g-p-a?x=1", "https://chatgpt.com/g/g-p-a#x", "https://chatgpt.com/g/g-p-a%2Fproject",
                "https://chatgpt.com/g//g-p-a", "javascript:alert(1)", "file:///g/g-p-a", "https://chatgpt.com/g/not-project"};
        for (String value : hostile) assertNull(value, ProjectUrlPolicy.parseProject(value));
    }

    @Test public void routeComparisonRequiresTrustedSameOriginAndProject() {
        assertTrue(ProjectUrlPolicy.sameProject("https://chatgpt.com/g/g-p-a/project", "https://chatgpt.com/g/g-p-a/c/c1"));
        assertFalse(ProjectUrlPolicy.sameProject("https://chatgpt.com/g/g-p-a/project", "https://evil.example/g/g-p-a/c/c1"));
        assertTrue(ProjectUrlPolicy.sameConversation("https://chatgpt.com/g/g-p-a/c/c1", "https://chatgpt.com/g/g-p-a/c/c1"));
        assertFalse(ProjectUrlPolicy.sameConversation("https://chatgpt.com/g/g-p-a/c/c1", "https://chatgpt.com/g/g-p-b/c/c1"));
    }
}
