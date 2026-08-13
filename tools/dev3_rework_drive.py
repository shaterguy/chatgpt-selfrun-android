#!/usr/bin/env python3
from pathlib import Path
R=Path(__file__).resolve().parents[1]
def rep(s,a,b,n):
 c=s.count(a)
 if c!=1: raise SystemExit(f'{n}: {c}')
 return s.replace(a,b,1)

p=R/'app/src/main/java/com/shaterguy/chatgptselfrun/DriveApiClient.java'
s=p.read_text()
s=rep(s,'import java.net.HttpURLConnection;\nimport java.net.URL;','import java.net.HttpURLConnection;\nimport java.net.URL;\nimport java.net.URLEncoder;','import')
anchor='''    /**\n     * Drive supports generated IDs for folders. Persist this ID before files.create so a retry can\n     * use files.get/the same ID and can never create a second folder.\n     */\n'''
method='''    /** Reconciles an outcome-unknown native Docs create without issuing another create. */\n    Metadata findSingleTurnDocument(String accessToken, String jobId, String parentId) throws Exception {\n        requireParent(parentId);\n        String q = "'" + parentId + "' in parents and trashed = false and mimeType = '" + MIME_DOCUMENT + "'";\n        String fields = "files(" + FILE_FIELDS + ")";\n        String endpoint = "https://www.googleapis.com/drive/v3/files?supportsAllDrives=true"\n                + "&q=" + URLEncoder.encode(q, StandardCharsets.UTF_8.name())\n                + "&fields=" + URLEncoder.encode(fields, StandardCharsets.UTF_8.name()) + "&pageSize=10";\n        JSONArray files = request("GET", endpoint, accessToken, null, false).optJSONArray("files");\n        Metadata match = null;\n        if (files == null) return null;\n        for (int i = 0; i < files.length(); i++) {\n            JSONObject raw = files.optJSONObject(i);\n            if (raw == null) continue;\n            Metadata candidate = new Metadata(raw);\n            if (!jobId.equals(candidate.name) || !MIME_DOCUMENT.equals(candidate.mimeType)\n                    || !parentId.equals(candidate.parentId) || candidate.trashed\n                    || !jobId.equals(candidate.appProperties.optString("job_id"))\n                    || !"turn_document".equals(candidate.appProperties.optString("selfrun_kind"))\n                    || !"selfrun_drive_android".equals(candidate.appProperties.optString("client_id"))) continue;\n            if (match != null) throw new IllegalStateException("multiple turn documents found for one SelfRun job");\n            match = candidate;\n        }\n        return match;\n    }\n\n'''
s=rep(s,anchor,method+anchor,'reconcile')
p.write_text(s)

p=R/'app/src/main/java/com/shaterguy/chatgptselfrun/SelfRunStore.java'
s=p.read_text()
anchor='    void markGuarding() { commitOrThrow(prefs.edit().putString("submissionState", EVENT_GUARDING)); }\n\n'
method='''    void resetPendingForDriveReplay(String recoveryStatus) {\n        commitOrThrow(prefs.edit()\n                .putLong("pendingEventSeq", 0L).putInt("pendingTurn", 0).putString("pendingSignalRaw", "")\n                .putString("pendingCommitId", "").putLong("commitDetectedAt", 0L).putLong("guardDueAt", 0L)\n                .putString("submissionState", EVENT_CONSUMED).putLong("submissionStartedAt", 0L)\n                .putInt("submissionBaselineCount", -1)\n                .putString("submissionRetryKind", "").putString("submissionRetryReason", "")\n                .putLong("submissionRetryDueAt", 0L).putBoolean("submissionRetryReady", false)\n                .putInt("submissionRetryAttempt", 0)\n                .putString("lastSeenDriveVersion", "").putString("lastSeenModifiedTime", "")\n                .putString("phase", PHASE_WAIT_DRIVE_COMMIT).putString("status", safe(recoveryStatus))\n                .putLong("phaseStartedAt", System.currentTimeMillis()));\n        syncHistory();\n    }\n\n'''
s=rep(s,anchor,anchor+method,'replay')
p.write_text(s)
