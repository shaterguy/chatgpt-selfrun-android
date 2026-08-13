package com.shaterguy.chatgptselfrun;

import android.content.Context;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

final class SelfRunRunLog {
    private static final String DIR = "selfrun-logs";
    private static final String PREFIX = "run-";
    private static final String SUFFIX = ".jsonl";
    private static final long MAX_BYTES = 1024L * 1024L;
    private static final int MAX_FILES = 100;
    private static final long WRITE_BATCH_MS = 250L;
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    static final class Metrics {
        final long recordCalls;
        final long repeatableCalls;
        final long aggregatedRepeats;
        final long emittedLines;
        final long summaryEmissions;
        final long writeBatches;
        final long linesWritten;

        Metrics(long recordCalls, long repeatableCalls, long aggregatedRepeats, long emittedLines,
                long summaryEmissions, long writeBatches, long linesWritten) {
            this.recordCalls = recordCalls;
            this.repeatableCalls = repeatableCalls;
            this.aggregatedRepeats = aggregatedRepeats;
            this.emittedLines = emittedLines;
            this.summaryEmissions = summaryEmissions;
            this.writeBatches = writeBatches;
            this.linesWritten = linesWritten;
        }
    }

    private static final class PendingLine {
        final String runId;
        final String line;

        PendingLine(String runId, String line) {
            this.runId = runId;
            this.line = line;
        }
    }

    private final File directory;
    private final Object stateLock = new Object();
    private final Object ioLock = new Object();
    private final Object fileLock = new Object();
    private final SelfRunLogSampler sampler = new SelfRunLogSampler();
    private final Deque<PendingLine> pendingLines = new ArrayDeque<>();
    private final ScheduledExecutorService writer = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "SelfRunLogWriter");
        thread.setDaemon(true);
        return thread;
    });
    private ScheduledFuture<?> scheduledDrain;
    private SelfRunLogSampler.Context executionContext =
            new SelfRunLogSampler.Context(0, 0, "none", "UNKNOWN");
    private long recordCalls;
    private long emittedLines;
    private long writeBatches;
    private long linesWritten;

    SelfRunRunLog(Context context) {
        directory = new File(context.getNoBackupFilesDir(), DIR);
    }

    void updateExecutionContext(int generation, int observerEpoch, String webViewId, String wakeLockState) {
        synchronized (stateLock) {
            executionContext = new SelfRunLogSampler.Context(generation, observerEpoch, webViewId, wakeLockState);
        }
    }

    void record(SelfRunStore store, String event, String detail) {
        if (store == null || store.runId().isEmpty()) return;
        try {
            String safeEvent = safeEvent(event);
            String safeDetail = sanitize(detail);
            long now = System.currentTimeMillis();
            List<SelfRunLogSampler.Emission> emissions = new ArrayList<>();
            synchronized (stateLock) {
                recordCalls = increment(recordCalls);
                if (!SelfRunLogSampler.isRepeatable(safeEvent)) {
                    emissions.addAll(sampler.flush("before_" + safeEvent.toLowerCase(Locale.ROOT), now));
                }
                emissions.addAll(sampler.accept(safeEvent, safeDetail, store.phase(), executionContext, now));
            }
            boolean urgent = !SelfRunLogSampler.isRepeatable(safeEvent);
            for (SelfRunLogSampler.Emission emission : emissions) {
                enqueue(store.runId(), encode(store, emission), urgent || emission.summary);
            }
        } catch (Throwable ignored) {
        }
    }

    Metrics metrics() {
        synchronized (stateLock) {
            SelfRunLogSampler.Metrics sampled = sampler.metrics();
            return new Metrics(recordCalls, sampled.repeatableCalls, sampled.aggregatedRepeats,
                    emittedLines, sampled.summaryEmissions, writeBatches, linesWritten);
        }
    }

    List<String> readDebug(String runId, int maxLines) {
        flushForRead();
        synchronized (fileLock) {
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
    }

    List<String> readExecution(String runId, int maxLines) {
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

    private String encode(SelfRunStore store, SelfRunLogSampler.Emission emission) throws Exception {
        JSONObject item = new JSONObject();
        item.put("timestamp_kst", format(System.currentTimeMillis()));
        item.put("run_id", safeToken(store.runId()));
        item.put("event", safeEvent(emission.event));
        item.put("phase", safeToken(store.phase()));
        item.put("role", safeToken(store.role()));
        item.put("turn", store.turn());
        item.put("status", bounded(store.status(), 180));
        item.put("detail", sanitize(emission.detail));
        item.put("generation", emission.context.generation);
        item.put("observer_epoch", emission.context.observerEpoch);
        item.put("webview", safeToken(emission.context.webViewId));
        item.put("wakelock_state", safeToken(emission.context.wakeLockState));
        item.put("first_occurrence_kst", format(emission.firstAtMs));
        item.put("last_occurrence_kst", format(emission.lastAtMs));
        item.put("repeat_count", emission.repeatCount);
        item.put("sampled_summary", emission.summary);
        item.put("abnormal_burst", emission.abnormalBurst);
        if (emission.summary) item.put("summary_cause", bounded(emission.summaryCause, 80));
        return item.toString();
    }

    private void enqueue(String runId, String line, boolean urgent) {
        synchronized (ioLock) {
            pendingLines.addLast(new PendingLine(runId, line));
            synchronized (stateLock) {
                emittedLines = increment(emittedLines);
            }
            if (urgent && scheduledDrain != null && !scheduledDrain.isDone()) {
                scheduledDrain.cancel(false);
                scheduledDrain = null;
            }
            if (scheduledDrain == null || scheduledDrain.isDone()) {
                scheduledDrain = writer.schedule(this::drainPending, urgent ? 0L : WRITE_BATCH_MS,
                        TimeUnit.MILLISECONDS);
            }
        }
    }

    private void drainPending() {
        List<PendingLine> batch = new ArrayList<>();
        synchronized (ioLock) {
            while (!pendingLines.isEmpty()) batch.add(pendingLines.removeFirst());
            scheduledDrain = null;
        }
        if (batch.isEmpty()) return;

        Map<String, List<String>> byRun = new LinkedHashMap<>();
        for (PendingLine pending : batch) {
            byRun.computeIfAbsent(pending.runId, ignored -> new ArrayList<>()).add(pending.line);
        }
        synchronized (fileLock) {
            for (Map.Entry<String, List<String>> entry : byRun.entrySet()) {
                try {
                    appendBatch(entry.getKey(), entry.getValue());
                    synchronized (stateLock) {
                        writeBatches = increment(writeBatches);
                        linesWritten = safeAdd(linesWritten, entry.getValue().size());
                    }
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private void flushForRead() {
        try {
            Future<?> future = writer.submit(this::drainPending);
            future.get(1L, TimeUnit.SECONDS);
        } catch (Throwable ignored) {
        }
    }

    private void appendBatch(String runId, List<String> lines) throws Exception {
        if (lines == null || lines.isEmpty()) return;
        if (!directory.exists() && !directory.mkdirs()) return;
        File file = file(runId);
        if (file == null) return;
        if (file.exists() && file.length() > MAX_BYTES) trim(file);
        try (FileOutputStream output = new FileOutputStream(file, true)) {
            for (String line : lines) {
                output.write((line + "\n").getBytes(StandardCharsets.UTF_8));
            }
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
                || event.startsWith("BOOTSTRAP_") || event.startsWith("DOM_OBSERVER_")
                || event.equals("PAUSED") || event.equals("DONE") || event.equals("REPEAT_SUMMARY")
                || event.equals("STATE_TRANSITION") || event.equals("PREFERENCE_VERIFIED")
                || event.equals("TARGET_DRIFT") || event.equals("TARGET_RESTORE")
                || event.equals("RENDERER_GONE") || event.equals("WEBVIEW_INIT_FAILED")
                || event.equals("STALE_CALLBACK") || event.equals("IO_EFFICIENCY");
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
            case "DOM_OBSERVER_ATTACHED" -> "DOM 이벤트 감시 연결";
            case "DOM_OBSERVER_DETACHED" -> "DOM 이벤트 감시 해제";
            case "DOM_OBSERVER_FAILED" -> "DOM 이벤트 감시 오류";
            case "REPEAT_SUMMARY" -> "반복 이벤트 요약";
            case "STALE_CALLBACK" -> "stale callback";
            case "IO_EFFICIENCY" -> "로그/영속 쓰기 효율";
            case "PAUSED" -> "일시중지";
            case "DONE" -> "완료";
            default -> event.replace('_', ' ');
        };
    }

    private static String sanitize(String detail) {
        if (detail == null || detail.isBlank()) return "";
        String value = detail.replace('\n', ' ').replace('\r', ' ').trim();
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.contains("cookie") || lower.contains("authorization") || lower.contains("password")
                || lower.contains("token") || lower.contains("prompt") || lower.contains("chatgpt.com")) {
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

    private static String format(long epochMs) {
        return OffsetDateTime.ofInstant(Instant.ofEpochMilli(Math.max(0L, epochMs)), KST).format(TIME);
    }

    private static long increment(long value) {
        return value == Long.MAX_VALUE ? Long.MAX_VALUE : value + 1L;
    }

    private static long safeAdd(long left, long right) {
        if (right <= 0L) return left;
        if (Long.MAX_VALUE - left < right) return Long.MAX_VALUE;
        return left + right;
    }
}
