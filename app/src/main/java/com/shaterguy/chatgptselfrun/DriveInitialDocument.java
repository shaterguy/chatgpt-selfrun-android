package com.shaterguy.chatgptselfrun;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

final class DriveInitialDocument {
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final String OPEN = "[SELF_RUN_DRIVE_JOB_V1]";
    private static final String CLOSE = "[/SELF_RUN_DRIVE_JOB_V1]";
    private static final Set<String> KEYS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "PROTOCOL_VERSION", "CLIENT_ID", "JOB_ID", "STATE", "CURRENT_TURN", "LAST_EVENT_SEQ",
            "CREATED_AT", "DOCUMENT_ID", "JOB_FOLDER_ID", "RUNS_BASE_FOLDER_ID",
            "ANDROID_APPLICATION_ID")));

    private DriveInitialDocument() {}

    static String create(String jobId, String documentId, String jobFolderId, String baseFolderId) {
        if (!validJobId(jobId) || !DriveApiClient.validFileId(documentId)
                || !DriveApiClient.validFileId(jobFolderId) || !DriveApiClient.validFileId(baseFolderId)) {
            throw new IllegalArgumentException("valid Drive initialization identifiers required");
        }
        return OPEN + "\n"
                + "PROTOCOL_VERSION=1\n"
                + "CLIENT_ID=SELFRUN_DRIVE_ANDROID\n"
                + "JOB_ID=" + jobId + "\n"
                + "STATE=APP_CREATED\n"
                + "CURRENT_TURN=1\n"
                + "LAST_EVENT_SEQ=0\n"
                + "CREATED_AT=" + OffsetDateTime.now(KST).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME) + "\n"
                + "DOCUMENT_ID=" + documentId + "\n"
                + "JOB_FOLDER_ID=" + jobFolderId + "\n"
                + "RUNS_BASE_FOLDER_ID=" + baseFolderId + "\n"
                + "ANDROID_APPLICATION_ID=" + BuildConfig.APPLICATION_ID + "\n"
                + CLOSE + "\n";
    }

    static boolean verifies(String text, String jobId, String documentId,
                            String jobFolderId, String baseFolderId) {
        if (text == null || text.length() > DriveCommitParser.MAX_DOCUMENT_CHARS || !validJobId(jobId)) return false;
        int open = text.indexOf(OPEN), close = text.indexOf(CLOSE, open + OPEN.length());
        if (open < 0 || close < 0 || open != text.lastIndexOf(OPEN) || close != text.lastIndexOf(CLOSE)) return false;
        if (!text.substring(0, open).trim().isEmpty()
                || !text.substring(close + CLOSE.length()).trim().isEmpty()) return false;
        Map<String, String> values = parse(text.substring(open + OPEN.length(), close));
        if (!values.keySet().equals(KEYS)) return false;
        try { OffsetDateTime.parse(values.get("CREATED_AT"), DateTimeFormatter.ISO_OFFSET_DATE_TIME); }
        catch (RuntimeException error) { return false; }
        return "1".equals(values.get("PROTOCOL_VERSION"))
                && "SELFRUN_DRIVE_ANDROID".equals(values.get("CLIENT_ID"))
                && jobId.equals(values.get("JOB_ID"))
                && "APP_CREATED".equals(values.get("STATE"))
                && "1".equals(values.get("CURRENT_TURN"))
                && "0".equals(values.get("LAST_EVENT_SEQ"))
                && documentId.equals(values.get("DOCUMENT_ID"))
                && jobFolderId.equals(values.get("JOB_FOLDER_ID"))
                && baseFolderId.equals(values.get("RUNS_BASE_FOLDER_ID"))
                && BuildConfig.APPLICATION_ID.equals(values.get("ANDROID_APPLICATION_ID"));
    }

    private static Map<String, String> parse(String body) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String original : body.split("\\R", -1)) {
            String line = original.trim();
            if (line.isEmpty()) continue;
            int equals = line.indexOf('=');
            if (equals <= 0) return Collections.emptyMap();
            String key = line.substring(0, equals), value = line.substring(equals + 1);
            if (!key.matches("[A-Z][A-Z0-9_]{0,63}") || !KEYS.contains(key)
                    || value.isEmpty() || value.length() > 512
                    || values.putIfAbsent(key, value) != null) return Collections.emptyMap();
        }
        return values;
    }

    private static boolean validJobId(String value) {
        return value != null && value.matches("[A-Za-z0-9._-]{1,80}");
    }
}
