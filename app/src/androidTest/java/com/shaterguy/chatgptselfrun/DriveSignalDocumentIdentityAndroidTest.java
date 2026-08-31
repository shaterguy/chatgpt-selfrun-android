package com.shaterguy.chatgptselfrun;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

/** Verifies that modern Drive signal consumption is keyed by Google Drive file ID, not cursor. */
@RunWith(AndroidJUnit4.class)
public final class DriveSignalDocumentIdentityAndroidTest {
    private static final String A = "signal_A000000000001";
    private static final String B = "signal_B000000000001";
    private static final String C = "signal_C000000000001";
    private static final String D = "signal_D000000000001";

    @Test public void persistentCursorCannotHideNewDocumentIds() {
        String run = "SR-20260831-100001-IDTEST";
        Context context = ApplicationProvider.getApplicationContext();
        setLastSeen(context, A);
        activate(context, run,
                A, title(run, "10:00:00"), "2026-08-31T01:00:00Z",
                B, title(run, "10:01:00"), "2026-08-31T01:01:00Z",
                C, title(run, "10:02:00"), "2026-08-31T01:02:00Z");
        DriveSignalParser.Scan scan = DriveSignalParser.scan(
                title(run, "10:00:00") + "\n" + title(run, "10:01:00") + "\n"
                        + title(run, "10:02:00") + "\n",
                run, 9999, SelfRunStore.MODE_CHAT);
        assertFalse(scan.cursorRebased);
        assertEquals(2, scan.unseen.size());
        assertEquals(B, scan.unseen.get(0).documentId);
        assertEquals(C, scan.unseen.get(1).documentId);
    }

    @Test public void noNewDocumentIdMeansNoUnseenSignalOnResumeBaseline() {
        String run = "SR-20260831-100002-IDTEST";
        Context context = ApplicationProvider.getApplicationContext();
        setLastSeen(context, C);
        activate(context, run,
                A, title(run, "10:00:00"), "2026-08-31T01:00:00Z",
                B, title(run, "10:01:00"), "2026-08-31T01:01:00Z",
                C, title(run, "10:02:00"), "2026-08-31T01:02:00Z");
        DriveSignalParser.Scan scan = DriveSignalParser.scan(
                title(run, "10:00:00") + "\n" + title(run, "10:01:00") + "\n"
                        + title(run, "10:02:00") + "\n",
                run, 0, SelfRunStore.MODE_CHAT);
        assertEquals(0, scan.unseen.size());
    }

    @Test public void newlyObservedIdRemainsNewEvenWhenItsSortPositionMovesEarlier() {
        String run = "SR-20260831-100003-IDTEST";
        Context context = ApplicationProvider.getApplicationContext();
        setLastSeen(context, C);

        activate(context, run,
                A, title(run, "10:00:00"), "2026-08-31T01:00:00Z",
                B, title(run, "10:01:00"), "2026-08-31T01:01:00Z",
                C, title(run, "10:02:00"), "2026-08-31T01:02:00Z");
        DriveSignalParser.Scan baseline = DriveSignalParser.scan(
                title(run, "10:00:00") + "\n" + title(run, "10:01:00") + "\n"
                        + title(run, "10:02:00") + "\n",
                run, 3, SelfRunStore.MODE_CHAT);
        assertEquals(0, baseline.unseen.size());

        activate(context, run,
                D, title(run, "09:59:00"), "2026-08-31T00:59:00Z",
                A, title(run, "10:00:00"), "2026-08-31T01:00:00Z",
                B, title(run, "10:01:00"), "2026-08-31T01:01:00Z",
                C, title(run, "10:02:00"), "2026-08-31T01:02:00Z");
        DriveSignalParser.Scan scan = DriveSignalParser.scan(
                title(run, "09:59:00") + "\n" + title(run, "10:00:00") + "\n"
                        + title(run, "10:01:00") + "\n" + title(run, "10:02:00") + "\n",
                run, 3, SelfRunStore.MODE_CHAT);
        assertEquals(1, scan.unseen.size());
        assertEquals(D, scan.unseen.get(0).documentId);
    }

    private static void setLastSeen(Context context, String id) {
        context.getSharedPreferences("selfrun_drive", Context.MODE_PRIVATE).edit()
                .putString("lastSeenDriveVersion", "signal:" + id + ":2026-08-31T01:02:00Z").commit();
    }

    private static void activate(Context context, String run, String... fields) {
        DriveSignalDocumentIdentity.activate(context, run);
        for (int i = 0; i < fields.length; i += 3) {
            DriveSignalDocumentIdentity.observeCandidate(fields[i], fields[i + 1], fields[i + 2], run);
        }
        DriveSignalDocumentIdentity.seal(run);
    }

    private static String title(String run, String time) {
        return "[2026.08.31 | " + time + "] [SELF_RUN_TURN_COMPLETED " + run + "]";
    }
}
