package com.shaterguy.chatgptselfrun;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Collections;

import static org.junit.Assert.*;

public class DriveInitializationPolicyTest {
    @Test public void initialDocumentRoundTripsAllBoundIds() {
        String text = DriveInitialDocument.create("SR-20260813-ABC123", "document_12345678",
                "jobFolder_12345678", "runsFolder_12345678");
        assertTrue(DriveInitialDocument.verifies(text, "SR-20260813-ABC123", "document_12345678",
                "jobFolder_12345678", "runsFolder_12345678"));
        assertTrue(text.contains("ANDROID_APPLICATION_ID=com.shaterguy.chatgptselfrun.drive"));
        assertTrue(text.matches("(?s).*CREATED_AT=.*(?:Z|[+-][0-9]{2}:[0-9]{2}).*"));
    }

    @Test public void initialDocumentRejectsMismatchedAndDuplicateFields() {
        String text = DriveInitialDocument.create("SR-20260813-ABC123", "document_12345678",
                "jobFolder_12345678", "runsFolder_12345678");
        assertFalse(DriveInitialDocument.verifies(text, "OTHER", "document_12345678",
                "jobFolder_12345678", "runsFolder_12345678"));
        assertFalse(DriveInitialDocument.verifies(text.replace("STATE=APP_CREATED",
                        "STATE=APP_CREATED\nSTATE=APP_CREATED"), "SR-20260813-ABC123", "document_12345678",
                "jobFolder_12345678", "runsFolder_12345678"));
        assertFalse(DriveInitialDocument.verifies("prefix\n" + text, "SR-20260813-ABC123",
                "document_12345678", "jobFolder_12345678", "runsFolder_12345678"));
        assertFalse(DriveInitialDocument.verifies(text + "suffix", "SR-20260813-ABC123",
                "document_12345678", "jobFolder_12345678", "runsFolder_12345678"));
    }

    @Test public void explicitParentRejectsMissingAndRootFallback() {
        for (String invalid : new String[]{null, "", "root", "short", "../root"}) {
            try {
                DriveApiClient.requireParent(invalid);
                fail("expected explicit parent rejection");
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().contains("root fallback is forbidden"));
            }
        }
        DriveApiClient.requireParent("runsFolder_12345678");
    }

    @Test public void pickerNormalizationRequiresExactlyOneOpaqueId() {
        assertEquals(Collections.singletonList("runsFolder_12345678"),
                DriveAuthorization.normalizePickedIds("runsFolder_12345678"));
        assertEquals(Collections.singletonList("runsFolder_12345678"),
                DriveAuthorization.normalizePickedIds(new String[]{"runsFolder_12345678"}));
        assertEquals(Collections.singletonList("runsFolder_12345678"),
                DriveAuthorization.normalizePickedIds(new ArrayList<>(
                        Collections.singletonList("runsFolder_12345678"))));
        assertTrue(DriveAuthorization.normalizePickedIds("id_one_123456,id_two_123456").isEmpty());
        assertEquals(2, DriveAuthorization.normalizePickedIds(
                new String[]{"id_one_123456", "id_two_123456"}).size());
    }
}
