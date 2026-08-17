package com.shaterguy.chatgptselfrun;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** Durable app-local UI calibration profile and purpose-scoped audit log. */
final class WebUiCalibrationStore {
    static final String PURPOSE_MODE_CHAT = "MODE_CHAT";
    static final String PURPOSE_MODE_WORK = "MODE_WORK";

    static final String PURPOSE_GENERAL_BOOTSTRAP_WORK_MODEL = "GENERAL_BOOTSTRAP_WORK_MODEL";
    static final String PURPOSE_GENERAL_BOOTSTRAP_WORK_REASONING = "GENERAL_BOOTSTRAP_WORK_REASONING";
    static final String PURPOSE_GENERAL_CONTINUATION_WORK_MODEL = "GENERAL_CONTINUATION_WORK_MODEL";
    static final String PURPOSE_GENERAL_CONTINUATION_WORK_REASONING = "GENERAL_CONTINUATION_WORK_REASONING";
    static final String PURPOSE_PROJECT_BOOTSTRAP_WORK_MODEL = "PROJECT_BOOTSTRAP_WORK_MODEL";
    static final String PURPOSE_PROJECT_BOOTSTRAP_WORK_REASONING = "PROJECT_BOOTSTRAP_WORK_REASONING";
    static final String PURPOSE_PROJECT_CONTINUATION_WORK_MODEL = "PROJECT_CONTINUATION_WORK_MODEL";
    static final String PURPOSE_PROJECT_CONTINUATION_WORK_REASONING = "PROJECT_CONTINUATION_WORK_REASONING";

    static final String PURPOSE_PROJECT_NEW_CHAT = "PROJECT_NEW_CHAT";
    static final String PURPOSE_GENERAL_NEW_CHAT = "GENERAL_NEW_CHAT";

    static final String TARGET_PROJECT_COMPOSER = "PROJECT_COMPOSER";
    static final String TARGET_PROJECT_SEND = "PROJECT_SEND";
    static final String TARGET_GENERAL_COMPOSER = "GENERAL_COMPOSER";
    static final String TARGET_GENERAL_SEND = "GENERAL_SEND";
    static final String STORAGE_KEY = "selfrun-drive:ui-profile:v1";

    private static final String PREFS = "selfrun_drive_ui_profile";
    private static final String KEY_PROFILE = "profile";
    private static final String KEY_LOG = "log";
    private static final int MAX_LOG_ITEMS = 120;
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private final SharedPreferences prefs;

    WebUiCalibrationStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    synchronized boolean saveCapture(String purpose, JSONObject capture) {
        if (!knownPurpose(purpose) || capture == null || !capture.optBoolean("ready", false)) return false;
        try {
            JSONObject profile = profile();
            JSONObject targets = profile.optJSONObject("targets");
            if (targets == null) { targets = new JSONObject(); profile.put("targets", targets); }
            if (isSubmitPurpose(purpose)) {
                JSONObject composer = capture.optJSONObject("composer");
                JSONObject send = capture.optJSONObject("send");
                if (!descriptor(composer) || !descriptor(send)) return false;
                JSONObject entry = capture.optJSONObject("entry");
                if (PURPOSE_PROJECT_NEW_CHAT.equals(purpose)) {
                    if (descriptor(entry)) targets.put(PURPOSE_PROJECT_NEW_CHAT, entry);
                    targets.put(TARGET_PROJECT_COMPOSER, composer);
                    targets.put(TARGET_PROJECT_SEND, send);
                } else {
                    if (descriptor(entry)) targets.put(PURPOSE_GENERAL_NEW_CHAT, entry);
                    targets.put(TARGET_GENERAL_COMPOSER, composer);
                    targets.put(TARGET_GENERAL_SEND, send);
                }
            } else {
                JSONObject target = capture.optJSONObject("target");
                if (!descriptor(target)) return false;
                targets.put(purpose, target);
            }
            JSONObject viewport = capture.optJSONObject("viewport");
            if (viewport != null && viewport.optInt("innerWidth", 0) > 0
                    && viewport.optInt("innerHeight", 0) > 0) profile.put("viewport", viewport);
            profile.put("version", 2);
            profile.put("updatedAt", System.currentTimeMillis());
            profile.put("lastPurpose", purpose);
            boolean committed = prefs.edit().putString(KEY_PROFILE, profile.toString()).commit();
            record(purpose, committed ? "CAPTURE_SAVED" : "CAPTURE_SAVE_FAILED", captureSummary(capture));
            return committed;
        } catch (Throwable error) {
            record(purpose, "CAPTURE_SAVE_FAILED", error.getClass().getSimpleName());
            return false;
        }
    }

    synchronized JSONObject profile() {
        try {
            String raw = prefs.getString(KEY_PROFILE, "");
            if (raw != null && !raw.isEmpty()) return new JSONObject(raw);
        } catch (Throwable ignored) {}
        JSONObject fresh = new JSONObject();
        try { fresh.put("version", 2); fresh.put("targets", new JSONObject()); } catch (Throwable ignored) {}
        return fresh;
    }

    String profileJson() { return profile().toString(); }

    String seedScript() {
        return "(()=>{try{localStorage.setItem(" + SelfRunScript.quote(STORAGE_KEY) + ","
                + SelfRunScript.quote(profileJson()) + ");return 'OK';}catch(e){return 'ERROR';}})()";
    }

    boolean hasTarget(String key) {
        JSONObject targets = profile().optJSONObject("targets");
        return targets != null && descriptor(targets.optJSONObject(key));
    }

    String purposeStatus(String purpose) {
        if (PURPOSE_PROJECT_NEW_CHAT.equals(purpose)) {
            return hasTarget(TARGET_PROJECT_COMPOSER) && hasTarget(TARGET_PROJECT_SEND) ? "확보됨" : "미설정";
        }
        if (PURPOSE_GENERAL_NEW_CHAT.equals(purpose)) {
            return hasTarget(TARGET_GENERAL_COMPOSER) && hasTarget(TARGET_GENERAL_SEND) ? "확보됨" : "미설정";
        }
        return hasTarget(purpose) ? "확보됨" : "미설정";
    }

    static String workModelPurpose(boolean general, boolean bootstrap) {
        if (general) return bootstrap ? PURPOSE_GENERAL_BOOTSTRAP_WORK_MODEL : PURPOSE_GENERAL_CONTINUATION_WORK_MODEL;
        return bootstrap ? PURPOSE_PROJECT_BOOTSTRAP_WORK_MODEL : PURPOSE_PROJECT_CONTINUATION_WORK_MODEL;
    }

    static String workReasoningPurpose(boolean general, boolean bootstrap) {
        if (general) return bootstrap ? PURPOSE_GENERAL_BOOTSTRAP_WORK_REASONING : PURPOSE_GENERAL_CONTINUATION_WORK_REASONING;
        return bootstrap ? PURPOSE_PROJECT_BOOTSTRAP_WORK_REASONING : PURPOSE_PROJECT_CONTINUATION_WORK_REASONING;
    }

    synchronized void clearAll() {
        prefs.edit().remove(KEY_PROFILE).remove(KEY_LOG).commit();
    }

    synchronized void record(String purpose, String event, String detail) {
        if (!knownPurpose(purpose) && !"SYSTEM".equals(purpose)) purpose = "SYSTEM";
        try {
            JSONArray old = new JSONArray(prefs.getString(KEY_LOG, "[]"));
            JSONArray next = new JSONArray();
            int start = Math.max(0, old.length() - MAX_LOG_ITEMS + 1);
            for (int i = start; i < old.length(); i++) next.put(old.opt(i));
            JSONObject item = new JSONObject();
            item.put("timestamp_kst", OffsetDateTime.now(KST).format(TIME));
            item.put("purpose", purpose);
            item.put("event", safe(event, 48));
            item.put("detail", safe(detail, 180));
            next.put(item);
            prefs.edit().putString(KEY_LOG, next.toString()).commit();
        } catch (Throwable ignored) {}
    }

    String logText(int maxItems) {
        try {
            JSONArray log = new JSONArray(prefs.getString(KEY_LOG, "[]"));
            StringBuilder out = new StringBuilder();
            int start = Math.max(0, log.length() - Math.max(1, maxItems));
            for (int i = start; i < log.length(); i++) {
                JSONObject item = log.optJSONObject(i);
                if (item == null) continue;
                if (out.length() > 0) out.append('\n');
                out.append(item.optString("timestamp_kst")).append(" · ")
                        .append(item.optString("purpose")).append(" · ")
                        .append(item.optString("event"));
                String detail = item.optString("detail");
                if (!detail.isEmpty()) out.append(" · ").append(detail);
            }
            return out.length() == 0 ? "보정 로그가 없습니다." : out.toString();
        } catch (Throwable ignored) { return "보정 로그를 읽지 못했습니다."; }
    }

    Viewport viewport() {
        JSONObject value = profile().optJSONObject("viewport");
        if (value == null) return null;
        int cssWidth = value.optInt("screenWidth", value.optInt("innerWidth", 0));
        int cssHeight = value.optInt("screenHeight", value.optInt("innerHeight", 0));
        double dpr = value.optDouble("devicePixelRatio", 0d);
        if (cssWidth < 240 || cssWidth > 1200 || cssHeight < 320 || cssHeight > 2600
                || dpr < 0.75d || dpr > 5d) return null;
        return new Viewport(cssWidth, cssHeight, dpr);
    }

    static final class Viewport {
        final int cssWidth;
        final int cssHeight;
        final double dpr;
        Viewport(int cssWidth, int cssHeight, double dpr) {
            this.cssWidth = cssWidth; this.cssHeight = cssHeight; this.dpr = dpr;
        }
        int pixelWidth() { return clamp((int) Math.round(cssWidth * dpr), 320, 2160); }
        int pixelHeight() { return clamp((int) Math.round(cssHeight * dpr), 480, 3840); }
        int densityDpi() { return clamp((int) Math.round(160d * dpr), 120, 640); }
        private static int clamp(int value, int min, int max) { return Math.max(min, Math.min(max, value)); }
    }

    private static boolean knownPurpose(String purpose) {
        return PURPOSE_MODE_CHAT.equals(purpose) || PURPOSE_MODE_WORK.equals(purpose)
                || PURPOSE_GENERAL_BOOTSTRAP_WORK_MODEL.equals(purpose)
                || PURPOSE_GENERAL_BOOTSTRAP_WORK_REASONING.equals(purpose)
                || PURPOSE_GENERAL_CONTINUATION_WORK_MODEL.equals(purpose)
                || PURPOSE_GENERAL_CONTINUATION_WORK_REASONING.equals(purpose)
                || PURPOSE_PROJECT_BOOTSTRAP_WORK_MODEL.equals(purpose)
                || PURPOSE_PROJECT_BOOTSTRAP_WORK_REASONING.equals(purpose)
                || PURPOSE_PROJECT_CONTINUATION_WORK_MODEL.equals(purpose)
                || PURPOSE_PROJECT_CONTINUATION_WORK_REASONING.equals(purpose)
                || PURPOSE_PROJECT_NEW_CHAT.equals(purpose) || PURPOSE_GENERAL_NEW_CHAT.equals(purpose);
    }

    private static boolean isSubmitPurpose(String purpose) {
        return PURPOSE_PROJECT_NEW_CHAT.equals(purpose) || PURPOSE_GENERAL_NEW_CHAT.equals(purpose);
    }

    private static boolean descriptor(JSONObject value) {
        if (value == null) return false;
        return !value.optString("id").isEmpty() || !value.optString("testid").isEmpty()
                || !value.optString("aria").isEmpty() || !value.optString("text").isEmpty()
                || !value.optString("role").isEmpty() || !value.optString("tag").isEmpty();
    }

    private static String captureSummary(JSONObject capture) {
        StringBuilder out = new StringBuilder("ready=").append(capture.optBoolean("ready", false) ? 1 : 0);
        JSONObject viewport = capture.optJSONObject("viewport");
        if (viewport != null) out.append(";viewport=").append(viewport.optInt("screenWidth", viewport.optInt("innerWidth")))
                .append('x').append(viewport.optInt("screenHeight", viewport.optInt("innerHeight")));
        if (capture.optJSONObject("entry") != null) out.append(";entry=1");
        if (capture.optJSONObject("composer") != null) out.append(";composer=1");
        if (capture.optJSONObject("send") != null) out.append(";send=1");
        if (capture.optJSONObject("target") != null) out.append(";target=1");
        return out.toString();
    }

    private static String safe(String value, int max) {
        String v = value == null ? "" : value.replace('\n', ' ').replace('\r', ' ').trim();
        return v.length() <= max ? v : v.substring(0, max);
    }
}
