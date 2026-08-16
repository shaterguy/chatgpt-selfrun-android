package com.shaterguy.chatgptselfrun;

import android.content.Context;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

final class SelfRunRunLog {
    private static final String DIR = "selfrun-drive-logs";
    private static final String PREFIX = "selfrun-drive-run-";
    private static final String SUFFIX = ".jsonl";
    private static final long MAX_BYTES = 1024L * 1024L;
    private static final int MAX_FILES = 100;
    private static final long NOISY_HEARTBEAT_MS = 30_000L;
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
    private final File directory;
    private String lastEvaluatePhase = "";
    private long lastEvaluateAt;
    private String lastResultDetail = "";
    private long lastResultAt;
    private long lastBaselineWaitAt;

    SelfRunRunLog(Context context) {
        directory = new File(context.getNoBackupFilesDir(), DIR);
    }

    synchronized void record(SelfRunStore store, String event, String detail) {
        if (store == null || store.runId().isEmpty()) return;
        try {
            if (!directory.exists() && !directory.mkdirs()) return;
            String safeEvent = safeEvent(event);
            String safeDetail = sanitize(detail);
            if (suppressNoisyDuplicate(store, safeEvent, safeDetail)) return;
            JSONObject item = new JSONObject();
            item.put("timestamp_kst", OffsetDateTime.now(KST).format(TIME));
            item.put("client", "selfrun-drive");
            item.put("run_id", safeToken(store.runId()));
            item.put("event", safeEvent);
            item.put("phase", safeToken(store.phase()));
            item.put("turn", store.turn());
            item.put("status", bounded(store.status(), 180));
            item.put("detail", safeDetail);
            append(store.runId(), item.toString());
        } catch (Throwable ignored) {
        }
    }

    private boolean suppressNoisyDuplicate(SelfRunStore store, String event, String detail) {
        long now = System.currentTimeMillis();
        if ("DOM_EVALUATE".equals(event)) {
            String phase = store.phase();
            if (phase.equals(lastEvaluatePhase) && now - lastEvaluateAt < NOISY_HEARTBEAT_MS) return true;
            lastEvaluatePhase = phase;
            lastEvaluateAt = now;
            return false;
        }
        if ("DOM_RESULT".equals(event)) {
            if (detail.equals(lastResultDetail) && now - lastResultAt < NOISY_HEARTBEAT_MS) return true;
            lastResultDetail = detail;
            lastResultAt = now;
            return false;
        }
        if ("ASSISTANT_BASELINE_WAIT".equals(event)) {
            if (now - lastBaselineWaitAt < NOISY_HEARTBEAT_MS) return true;
            lastBaselineWaitAt = now;
        }
        return false;
    }

    synchronized List<String> readDebug(String runId, int maxLines) {
        Deque<String> lines = new ArrayDeque<>();
        File file = file(runId);
        if (file == null || !file.exists()) return new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (lines.size() >= Math.max(1, maxLines)) lines.removeFirst();
                lines.addLast(line);
            }
        } catch (Exception ignored) {
        }
        return new ArrayList<>(lines);
    }

    synchronized List<String> readExecution(String runId, int maxLines) {
        Deque<String> lines = new ArrayDeque<>();
        for (String raw : readDebug(runId, Integer.MAX_VALUE)) {
            try {
                JSONObject item = new JSONObject(raw);
                String event = item.optString("event");
                if (!isExecutionEvent(event)) continue;
                String detail = item.optString("detail");
                String suffix = detail.isEmpty() ? "" : " · " + detail;
                String line = item.optString("timestamp_kst") + " · " + label(event)
                        + " · " + item.optString("phase") + suffix;
                if (lines.size() >= Math.max(1, maxLines)) lines.removeFirst();
                lines.addLast(line);
            } catch (Exception ignored) {
            }
        }
        return new ArrayList<>(lines);
    }

    private void append(String runId, String line) throws Exception {
        File file = file(runId);
        if (file == null) return;
        if (file.exists() && file.length() > MAX_BYTES) trim(file);
        try (FileOutputStream output = new FileOutputStream(file, true)) {
            output.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            output.flush();
        }
        trimFiles();
    }

    private void trim(File file) throws Exception {
        Deque<String> lines = new ArrayDeque<>();
        long bytes = 0L;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                long size = line.getBytes(StandardCharsets.UTF_8).length + 1L;
                lines.addLast(line);
                bytes += size;
                while (bytes > MAX_BYTES * 3L / 4L && !lines.isEmpty()) {
                    String removed = lines.removeFirst();
                    bytes -= removed.getBytes(StandardCharsets.UTF_8).length + 1L;
                }
            }
        }
        try (FileOutputStream output = new FileOutputStream(file, false)) {
            for (String line : lines) output.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        }
    }

    private void trimFiles() {
        File[] files = directory.listFiles((dir, name) -> name.startsWith(PREFIX) && name.endsWith(SUFFIX));
        if (files == null || files.length <= MAX_FILES) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
        for (int i = MAX_FILES; i < files.length; i++) files[i].delete();
    }

    private File file(String runId) {
        String safe = safeToken(runId);
        if (safe.isEmpty() || "redacted".equals(safe)) return null;
        return new File(directory, PREFIX + safe + SUFFIX);
    }

    private static boolean isExecutionEvent(String event) {
        return event.startsWith("UI_") || event.startsWith("SERVICE_") || event.startsWith("SIGNAL_")
                || event.startsWith("RATE_LIMIT") || event.startsWith("WEBVIEW_")
                || event.startsWith("BOOTSTRAP_") || event.startsWith("DRIVE_")
                || event.equals("PAUSED") || event.equals("DONE")
                || event.equals("STATE_TRANSITION") || event.equals("PREFERENCE_VERIFIED")
                || event.equals("TARGET_DRIFT") || event.equals("TARGET_RESTORE")
                || event.equals("RENDERER_GONE") || event.equals("WEBVIEW_INIT_FAILED");
    }

    private static String label(String event) {
        return switch (event) {
            case "UI_START" -> "셀프런 시작";
            case "UI_RESUME" -> "사용자 재개";
            case "UI_STOP" -> "사용자 중지";
            case "SERVICE_START" -> "백그라운드 실행 시작";
            case "WEBVIEW_LAUNCH" -> "자동화 WebView 시작";
            case "WEBVIEW_PAGE_START" -> "ChatGPT 화면 로딩 시작";
            case "WEBVIEW_PAGE_FINISH" -> "ChatGPT 화면 로딩 완료";
            case "WEBVIEW_NAVIGATION" -> "ChatGPT 화면 이동";
            case "WEBVIEW_ERROR" -> "WebView 오류";
            case "BOOTSTRAP_CONTEXT_READY" -> "프로젝트 새 대화 준비 완료";
            case "BOOTSTRAP_SUBMITTED" -> "첫 요청 제출";
            case "BOOTSTRAP_CONFIRMED" -> "새 conversation 확인";
            case "SIGNAL_ACCEPTED" -> "제어 신호 수신";
            case "PREFERENCE_VERIFIED" -> "모델/추론 적용 확인";
            case "STATE_TRANSITION" -> "상태 전이";
            case "TARGET_DRIFT" -> "대상 화면 이탈 감지";
            case "TARGET_RESTORE" -> "대상 화면 복구";
            case "RATE_LIMIT" -> "요청 제한 대기";
            case "PAUSED" -> "일시중지";
            case "DONE" -> "완료";
            default -> event.replace('_', ' ');
        };
    }

    private static String sanitize(String detail) {
        if (detail == null || detail.trim().isEmpty()) return "";
        String value = detail.replace('\n', ' ').replace('\r', ' ').trim();
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.contains("cookie") || lower.contains("authorization") || lower.contains("password")
                || lower.contains("token") || lower.contains("prompt") || lower.contains("chatgpt.com")
                || lower.contains("drive.google.com") || lower.contains("docs.google.com")
                || lower.contains("access_token") || lower.contains("refresh_token")
                || lower.contains("serverauthcode") || lower.contains("bearer ")
                || lower.contains("oauth")) {
            return "redacted";
        }
        return bounded(value, 240);
    }

    private static String safeToken(String value) {
        if (value == null || value.isEmpty()) return "";
        return value.matches("[A-Za-z0-9._-]{1,80}") ? value : "redacted";
    }

    private static String safeEvent(String value) {
        return value != null && value.matches("[A-Z0-9_]{1,64}") ? value : "UNKNOWN";
    }

    private static String bounded(String value, int max) {
        String safe = value == null ? "" : value;
        return safe.length() <= max ? safe : safe.substring(0, max);
    }
}
