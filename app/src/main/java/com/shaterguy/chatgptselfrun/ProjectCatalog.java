package com.shaterguy.chatgptselfrun;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Minimal local catalog. It stores canonical project URLs plus user-visible project names. */
final class ProjectCatalog {
    private static final String PREFS = "selfrun_project_catalog_v4";
    private static final String KEY_SCHEMA = "schema";
    private static final String KEY_URLS = "urls";
    private static final String KEY_NAME_PREFIX = "project_name:";
    private static final int LEGACY_SCHEMA = 4;
    private static final int SCHEMA = 5;
    private static final int MAX_ENTRIES = 50;
    private static final int MAX_DISPLAY_NAME_LENGTH = 120;
    private final SharedPreferences prefs;

    ProjectCatalog(Context context) { prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    List<ProjectUrlPolicy.ProjectRef> entries() {
        int schema = prefs.getInt(KEY_SCHEMA, 0);
        if (schema != LEGACY_SCHEMA && schema != SCHEMA) return Collections.emptyList();
        Set<String> raw = prefs.getStringSet(KEY_URLS, Collections.emptySet());
        LinkedHashSet<String> urls = canonicalizeStoredUrls(raw);
        ArrayList<ProjectUrlPolicy.ProjectRef> out = new ArrayList<>();
        for (String value : urls) {
            ProjectUrlPolicy.ProjectRef ref = ProjectUrlPolicy.parseProject(value);
            if (ref != null && !contains(out, ref.projectId)) out.add(ref);
        }

        SharedPreferences.Editor migration = null;
        if (raw == null || !urls.equals(raw)) migration = prefs.edit().putStringSet(KEY_URLS, urls);
        Map<String, ?> values = prefs.getAll();
        for (ProjectUrlPolicy.ProjectRef ref : out) {
            String exact = normalizeDisplayName(stringValue(values.get(nameKey(ref.projectId))));
            if (!exact.isEmpty()) continue;
            String recovered = legacyDisplayName(values, ref.projectId);
            if (recovered.isEmpty()) continue;
            if (migration == null) migration = prefs.edit();
            migration.putString(nameKey(ref.projectId), recovered);
        }
        if (migration != null) migration.commit();
        return out;
    }

    boolean addVisitedProject(String rawUrl) { return addVisitedProject(rawUrl, ""); }

    boolean addVisitedProject(String rawUrl, String displayName) {
        ProjectUrlPolicy.ProjectRef ref = ProjectUrlPolicy.parseProject(rawUrl);
        if (ref == null) return false;

        LinkedHashSet<String> urls = new LinkedHashSet<>();
        boolean alreadyPresent = false;
        for (ProjectUrlPolicy.ProjectRef prior : entries()) {
            if (prior.projectId.equals(ref.projectId)) {
                alreadyPresent = true;
                urls.add(prior.canonicalUrl);
            } else urls.add(prior.canonicalUrl);
        }
        if (!alreadyPresent) urls.add(ref.canonicalUrl);
        while (urls.size() > MAX_ENTRIES) urls.remove(urls.iterator().next());

        String cleanedName = normalizeDisplayName(displayName);
        String priorName = normalizeDisplayName(prefs.getString(nameKey(ref.projectId), ""));
        boolean nameChanged = !cleanedName.isEmpty() && !cleanedName.equals(priorName);
        if (alreadyPresent && !nameChanged && prefs.getInt(KEY_SCHEMA, 0) == SCHEMA) return false;

        SharedPreferences.Editor editor = prefs.edit()
                .putInt(KEY_SCHEMA, SCHEMA)
                .putStringSet(KEY_URLS, urls);
        if (nameChanged) editor.putString(nameKey(ref.projectId), cleanedName);
        return editor.commit();
    }

    void clear() { if (!prefs.edit().clear().commit()) throw new IllegalStateException("catalog clear failed"); }

    String displayName(ProjectUrlPolicy.ProjectRef ref) {
        if (ref == null) return fallbackDisplayName(null);
        String stored = normalizeDisplayName(prefs.getString(nameKey(ref.projectId), ""));
        if (!stored.isEmpty()) return stored;
        String recovered = legacyDisplayName(prefs.getAll(), ref.projectId);
        if (!recovered.isEmpty()) {
            prefs.edit().putString(nameKey(ref.projectId), recovered).commit();
            return recovered;
        }
        return fallbackDisplayName(ref);
    }

    static LinkedHashSet<String> canonicalizeStoredUrls(Set<String> raw) {
        LinkedHashSet<String> urls = new LinkedHashSet<>();
        if (raw != null) for (String value : raw) {
            ProjectUrlPolicy.ProjectRef ref = ProjectUrlPolicy.parseProject(value);
            if (ref != null) urls.add(ref.canonicalUrl);
        }
        while (urls.size() > MAX_ENTRIES) urls.remove(urls.iterator().next());
        return urls;
    }

    static String legacyDisplayName(Map<String, ?> values, String projectId) {
        if (values == null || projectId == null || projectId.isEmpty()) return "";
        String exactKey = nameKey(projectId);
        String exact = normalizeDisplayName(stringValue(values.get(exactKey)));
        if (!exact.isEmpty()) return exact;

        ArrayList<String> aliases = new ArrayList<>();
        for (String key : values.keySet()) {
            if (key != null && key.startsWith(KEY_NAME_PREFIX) && !key.equals(exactKey)) aliases.add(key);
        }
        Collections.sort(aliases);
        for (String key : aliases) {
            String sourceId = key.substring(KEY_NAME_PREFIX.length());
            if (!projectId.equals(ProjectUrlPolicy.canonicalProjectId(sourceId))) continue;
            String candidate = normalizeDisplayName(stringValue(values.get(key)));
            if (!candidate.isEmpty()) return candidate;
        }
        return "";
    }

    static String normalizeDisplayName(String value) {
        if (value == null || value.isEmpty()) return "";
        StringBuilder out = new StringBuilder(Math.min(value.length(), MAX_DISPLAY_NAME_LENGTH));
        boolean pendingSpace = false;
        for (int i = 0; i < value.length() && out.length() < MAX_DISPLAY_NAME_LENGTH; i++) {
            char c = value.charAt(i);
            if (Character.isISOControl(c) || Character.isWhitespace(c)) {
                pendingSpace = out.length() > 0;
                continue;
            }
            if (pendingSpace && out.length() < MAX_DISPLAY_NAME_LENGTH) out.append(' ');
            pendingSpace = false;
            if (out.length() < MAX_DISPLAY_NAME_LENGTH) out.append(c);
        }
        return out.toString().trim();
    }

    static String fallbackDisplayName(ProjectUrlPolicy.ProjectRef ref) {
        String id = ref == null ? "" : ref.projectId;
        return "프로젝트 " + (id.length() > 16 ? id.substring(0, 16) : id);
    }

    private static String nameKey(String projectId) { return KEY_NAME_PREFIX + projectId; }

    private static String stringValue(Object value) { return value instanceof String ? (String) value : ""; }

    private static boolean contains(List<ProjectUrlPolicy.ProjectRef> entries, String id) {
        for (ProjectUrlPolicy.ProjectRef entry : entries) if (entry.projectId.equals(id)) return true;
        return false;
    }
}
