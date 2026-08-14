package com.shaterguy.chatgptselfrun;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.List;

/** App-private cache of the last successfully discovered ChatGPT project catalog. */
final class ProjectCatalogStore {
    private static final String PREFS = "selfrun_project_catalog";
    private static final String KEY_ENTRIES = "entries";
    private static final String KEY_REFRESHED_AT = "refreshedAt";

    private final SharedPreferences prefs;

    ProjectCatalogStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    List<ProjectCatalog.Entry> load() {
        return ProjectCatalog.fromStoredJson(prefs.getString(KEY_ENTRIES, ""));
    }

    long refreshedAt() { return prefs.getLong(KEY_REFRESHED_AT, 0L); }

    void save(List<ProjectCatalog.Entry> entries, long refreshedAt) {
        String json = ProjectCatalog.toStoredJson(entries);
        if (!prefs.edit().putString(KEY_ENTRIES, json).putLong(KEY_REFRESHED_AT, refreshedAt).commit())
            throw new IllegalStateException("project catalog write failed");
    }
}
