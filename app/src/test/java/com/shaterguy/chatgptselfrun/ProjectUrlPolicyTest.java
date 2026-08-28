package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import static org.junit.Assert.*;

public class ProjectUrlPolicyTest {
    @Test public void canonicalizesOnlyExactProjectRoutes() {
        ProjectUrlPolicy.ProjectRef root = ProjectUrlPolicy.parseProject("https://chatgpt.com/g/g-p-Ab_9");
        ProjectUrlPolicy.ProjectRef project = ProjectUrlPolicy.parseProject("https://chatgpt.com/g/g-p-Ab_9/project");
        ProjectUrlPolicy.ProjectRef conversation = ProjectUrlPolicy.parseProject("https://chatgpt.com/g/g-p-Ab_9/c/abc_123");
        assertNotNull(root); assertNotNull(project); assertNotNull(conversation);
        assertEquals("g-p-Ab_9", root.projectId);
        assertEquals("https://chatgpt.com/g/g-p-Ab_9/project", root.canonicalUrl);
        assertEquals("abc_123", conversation.conversationId);
    }

    @Test public void sluggedAndUnsluggedModernProjectRoutesShareIdentity() {
        String id = "g-p-6a582c824ba08191ac7e74e9bad721fc";
        String plain = "https://chatgpt.com/g/" + id + "/project";
        String slugged = "https://chatgpt.com/g/" + id + "-vibe-coding/project";
        String sluggedConversation = "https://chatgpt.com/g/" + id + "-vibe-coding/c/abc_123";
        ProjectUrlPolicy.ProjectRef ref = ProjectUrlPolicy.parseProject(slugged);
        ProjectUrlPolicy.ProjectRef conversation = ProjectUrlPolicy.parseProject(sluggedConversation);
        assertNotNull(ref); assertNotNull(conversation);
        assertEquals(id, ProjectUrlPolicy.canonicalProjectId(id));
        assertEquals(id, ProjectUrlPolicy.canonicalProjectId(id + "-vibe-coding"));
        assertEquals(id, ref.projectId);
        assertEquals(id, conversation.projectId);
        assertEquals("https://chatgpt.com/g/" + id + "/project", ref.canonicalUrl);
        assertEquals("abc_123", conversation.conversationId);
        assertTrue(ProjectUrlPolicy.sameProject(plain, slugged));
        assertTrue(ProjectUrlPolicy.sameConversation(
                "https://chatgpt.com/g/" + id + "/c/abc_123", sluggedConversation));
    }

    @Test public void doesNotStripSuffixFromLegacyOpaqueProjectIds() {
        ProjectUrlPolicy.ProjectRef ref = ProjectUrlPolicy.parseProject("https://chatgpt.com/g/g-p-AbCd-legacy/project");
        assertNotNull(ref);
        assertEquals("g-p-AbCd-legacy", ProjectUrlPolicy.canonicalProjectId("g-p-AbCd-legacy"));
        assertEquals("g-p-AbCd-legacy", ref.projectId);
        assertFalse(ProjectUrlPolicy.sameProject(
                "https://chatgpt.com/g/g-p-AbCd/project",
                "https://chatgpt.com/g/g-p-AbCd-legacy/project"));
    }

    @Test public void malformedOrNonModernSuffixesNeverCollapseIntoCanonicalIdentity() {
        String id = "g-p-6a582c824ba08191ac7e74e9bad721fc";
        String nonHex = "g-p-z" + id.substring(5);
        assertEquals(id + "-", ProjectUrlPolicy.canonicalProjectId(id + "-"));
        assertEquals(nonHex + "-vibe-coding",
                ProjectUrlPolicy.canonicalProjectId(nonHex + "-vibe-coding"));
        assertEquals("", ProjectUrlPolicy.canonicalProjectId(id + "-bad!"));
        assertFalse(ProjectUrlPolicy.sameProject(
                "https://chatgpt.com/g/" + id + "/project",
                "https://chatgpt.com/g/" + id + "-/project"));
        assertFalse(ProjectUrlPolicy.sameProject(
                "https://chatgpt.com/g/" + id + "/project",
                "https://chatgpt.com/g/" + nonHex + "-vibe-coding/project"));
        assertNull(ProjectUrlPolicy.parseProject(
                "https://chatgpt.com/g/" + id + "-bad%2Fslug/project"));
        assertNull(ProjectUrlPolicy.parseProject(
                "https://chatgpt.com/g/" + id + "-vibe-coding/project?project=" + id));
        assertNull(ProjectUrlPolicy.parseProject(
                "https://chatgpt.com.evil/g/" + id + "-vibe-coding/project"));
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
