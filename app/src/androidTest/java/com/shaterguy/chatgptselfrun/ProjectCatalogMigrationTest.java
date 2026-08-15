package com.shaterguy.chatgptselfrun;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class ProjectCatalogMigrationTest {
    private static final String PREFS = "selfrun_project_catalog_v4";
    private static final String URL = "https://chatgpt.com/g/g-p-AbCdEfGhIjKlMnOpQr/project";
    private Context context;
    private SharedPreferences prefs;

    @Before public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        assertTrue(prefs.edit().clear().commit());
    }

    @After public void tearDown() { assertTrue(prefs.edit().clear().commit()); }

    @Test public void legacyUrlsRemainUsableAndLearnAName() {
        assertTrue(prefs.edit().putInt("schema", 4).putStringSet("urls", Collections.singleton(URL)).commit());
        ProjectCatalog catalog = new ProjectCatalog(context);
        assertEquals(1, catalog.entries().size());
        assertEquals("프로젝트 g-p-AbCdEfGhIjKl", catalog.displayName(catalog.entries().get(0)));

        assertTrue(catalog.addVisitedProject(URL, "Vibe Coding"));
        assertEquals(5, prefs.getInt("schema", 0));
        assertEquals(1, catalog.entries().size());
        assertEquals("Vibe Coding", catalog.displayName(catalog.entries().get(0)));
        assertFalse(catalog.addVisitedProject(URL, "Vibe Coding"));
    }

    @Test public void renamedProjectUpdatesWithoutDuplicatingTheUrl() {
        ProjectCatalog catalog = new ProjectCatalog(context);
        assertTrue(catalog.addVisitedProject(URL, "Old name"));
        assertTrue(catalog.addVisitedProject(URL, "New name"));
        assertEquals(1, catalog.entries().size());
        assertEquals("New name", catalog.displayName(catalog.entries().get(0)));
    }
}
