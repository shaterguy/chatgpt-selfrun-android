package com.shaterguy.chatgptselfrun;

import java.net.URI;

/** Strict, shared policy for every persisted or executed ChatGPT project target. */
final class ProjectUrlPolicy {
    private static final String HOST = "chatgpt.com";
    private static final String PROJECT_PREFIX = "g-p-";
    private static final int MODERN_PROJECT_TOKEN_LENGTH = 32;
    private static final int MAX_URL_LENGTH = 2048;
    private static final int MAX_ID_LENGTH = 160;

    static final class ProjectRef {
        final String projectId;
        final String conversationId;
        final String canonicalUrl;

        ProjectRef(String projectId, String conversationId) {
            this.projectId = projectId;
            this.conversationId = conversationId;
            this.canonicalUrl = "https://" + HOST + "/g/" + projectId + "/project";
        }
    }

    private ProjectUrlPolicy() { }

    static ProjectRef parseProject(String raw) {
        if (raw == null || raw.length() == 0 || raw.length() > MAX_URL_LENGTH || containsControl(raw)) return null;
        try {
            URI uri = new URI(raw);
            if (!"https".equals(uri.getScheme()) || !HOST.equals(uri.getHost()) || uri.getRawUserInfo() != null
                    || uri.getPort() != -1 || uri.getRawQuery() != null || uri.getRawFragment() != null) return null;
            String rawPath = uri.getRawPath();
            String path = uri.getPath();
            if (rawPath == null || !rawPath.equals(path) || rawPath.contains("//") || rawPath.contains("\\")
                    || rawPath.contains("..") || rawPath.contains("%")) return null;
            String[] parts = path.split("/", -1);
            if (parts.length < 3 || !"".equals(parts[0]) || !"g".equals(parts[1]) || !validProjectId(parts[2])) return null;
            String projectId = canonicalProjectId(parts[2]);
            if (projectId.isEmpty()) return null;
            if (parts.length == 3 || (parts.length == 4 && parts[3].isEmpty())) return new ProjectRef(projectId, "");
            if (parts.length == 4 && "project".equals(parts[3])) return new ProjectRef(projectId, "");
            if (parts.length == 5 && "c".equals(parts[3]) && validOpaqueId(parts[4])) return new ProjectRef(projectId, parts[4]);
        } catch (Exception ignored) { }
        return null;
    }

    static String canonicalProjectId(String value) {
        if (!validProjectId(value)) return "";
        int tokenStart = PROJECT_PREFIX.length();
        int canonicalEnd = tokenStart + MODERN_PROJECT_TOKEN_LENGTH;
        if (value.length() <= canonicalEnd + 1 || value.charAt(canonicalEnd) != '-') return value;
        for (int i = tokenStart; i < canonicalEnd; i++) if (!isHex(value.charAt(i))) return value;
        String slug = value.substring(canonicalEnd + 1);
        return validOpaqueId(slug) ? value.substring(0, canonicalEnd) : value;
    }

    /** JavaScript equivalent of canonicalProjectId(), derived from the same policy constants. */
    static String webProjectIdentityPrelude() {
        return "const __srProjectPrefix=" + SelfRunScript.quote(PROJECT_PREFIX)
                + ",__srProjectTokenLength=" + MODERN_PROJECT_TOKEN_LENGTH
                + ",__srProjectMaxIdLength=" + MAX_ID_LENGTH + ";"
                + "const __srProjectOpaque=value=>{if(typeof value!=='string'||value.length===0||value.length>__srProjectMaxIdLength)return false;for(let i=0;i<value.length;i++){const c=value.charAt(i);if(!((c>='a'&&c<='z')||(c>='A'&&c<='Z')||(c>='0'&&c<='9')||c==='-'||c==='_'))return false;}return true;};"
                + "const __srProjectHex=c=>(c>='0'&&c<='9')||(c>='a'&&c<='f')||(c>='A'&&c<='F');"
                + "const __srCanonicalProjectId=value=>{if(typeof value!=='string'||!value.startsWith(__srProjectPrefix)||!__srProjectOpaque(value))return '';const tokenStart=__srProjectPrefix.length,canonicalEnd=tokenStart+__srProjectTokenLength;if(value.length<=canonicalEnd+1||value.charAt(canonicalEnd)!=='-')return value;for(let i=tokenStart;i<canonicalEnd;i++)if(!__srProjectHex(value.charAt(i)))return value;const slug=value.substring(canonicalEnd+1);return __srProjectOpaque(slug)?value.substring(0,canonicalEnd):value;};";
    }

    static boolean isTrustedChatgptPage(String raw) {
        if (raw == null || raw.length() == 0 || raw.length() > MAX_URL_LENGTH || containsControl(raw)) return false;
        try {
            URI uri = new URI(raw);
            return "https".equals(uri.getScheme()) && HOST.equals(uri.getHost())
                    && uri.getRawUserInfo() == null && uri.getPort() == -1;
        } catch (Exception ignored) { return false; }
    }

    static boolean sameProject(String a, String b) {
        ProjectRef left = parseProject(a), right = parseProject(b);
        return left != null && right != null && left.projectId.equals(right.projectId);
    }

    static boolean sameConversation(String expected, String actual) {
        boolean expectedGeneral = SelfRunScript.isGeneralChatUrl(expected);
        boolean actualGeneral = SelfRunScript.isGeneralChatUrl(actual);
        if (expectedGeneral || actualGeneral) {
            if (!expectedGeneral || !actualGeneral) return false;
            String left = SelfRunScript.conversationId(expected);
            String right = SelfRunScript.conversationId(actual);
            return !left.isEmpty() && left.equals(right);
        }
        ProjectRef left = parseProject(expected), right = parseProject(actual);
        return left != null && right != null && !left.conversationId.isEmpty()
                && left.projectId.equals(right.projectId) && left.conversationId.equals(right.conversationId);
    }

    private static boolean validProjectId(String value) {
        return value != null && value.startsWith(PROJECT_PREFIX) && validOpaqueId(value);
    }

    private static boolean validOpaqueId(String value) {
        if (value == null || value.isEmpty() || value.length() > MAX_ID_LENGTH) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-' || c == '_')) return false;
        }
        return true;
    }

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private static boolean containsControl(String value) {
        for (int i = 0; i < value.length(); i++) if (Character.isISOControl(value.charAt(i))) return true;
        return false;
    }
}
