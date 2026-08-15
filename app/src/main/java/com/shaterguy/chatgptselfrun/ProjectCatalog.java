package com.shaterguy.chatgptselfrun;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Minimal local catalog. It stores only canonical project URLs, never a browsing history. */
final class ProjectCatalog {
    private static final String PREFS = "selfrun_project_catalog_v4";
    private static final String KEY_SCHEMA = "schema";
    private static final String KEY_URLS = "urls";
    private static final int SCHEMA = 4;
    private static final int MAX_ENTRIES = 50;
    private final SharedPreferences prefs;

    ProjectCatalog(Context context) { prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    List<ProjectUrlPolicy.ProjectRef> entries() {
        if (prefs.getInt(KEY_SCHEMA, 0) != SCHEMA) return Collections.emptyList();
        Set<String> raw = prefs.getStringSet(KEY_URLS, Collections.emptySet());
        ArrayList<ProjectUrlPolicy.ProjectRef> out = new ArrayList<>();
        if (raw != null) for (String value : raw) {
            ProjectUrlPolicy.ProjectRef ref = ProjectUrlPolicy.parseProject(value);
            if (ref != null && !contains(out, ref.projectId)) out.add(ref);
        }
        return out;
    }

    boolean addVisitedProject(String rawUrl) {
        ProjectUrlPolicy.ProjectRef ref = ProjectUrlPolicy.parseProject(rawUrl);
        if (ref == null) return false;
        LinkedHashSet<String> urls = new LinkedHashSet<>();
        boolean alreadyPresent = false;
        for (ProjectUrlPolicy.ProjectRef prior : entries()) {
            if (prior.projectId.equals(ref.projectId)) alreadyPresent = true;
            else urls.add(prior.canonicalUrl);
        }
        if (alreadyPresent) return false;
        urls.add(ref.canonicalUrl);
        while (urls.size() > MAX_ENTRIES) urls.remove(urls.iterator().next());
        return prefs.edit().putInt(KEY_SCHEMA, SCHEMA).putStringSet(KEY_URLS, urls).commit();
    }

    void clear() { if (!prefs.edit().clear().commit()) throw new IllegalStateException("catalog clear failed"); }

    static String displayName(ProjectUrlPolicy.ProjectRef ref) {
        String id = ref == null ? "" : ref.projectId;
        return "프로젝트 " + (id.length() > 16 ? id.substring(0, 16) : id);
    }

    private static boolean contains(List<ProjectUrlPolicy.ProjectRef> entries, String id) {
        for (ProjectUrlPolicy.ProjectRef entry : entries) if (entry.projectId.equals(id)) return true;
        return false;
    }
}
