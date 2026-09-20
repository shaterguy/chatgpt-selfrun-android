package com.shaterguy.chatgptselfrun;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Optional DONE-only links for user-accessible final artifacts. Never participates in Result routing. */
final class SelfRunDeliverableLinks {
    private static final int MAX_LINKS = 20;
    private static final int MAX_NAME_CHARS = 160;
    private static final int MAX_URL_CHARS = 4096;

    static JSONArray fromResult(String raw) {
        if (raw == null || raw.trim().isEmpty()) return new JSONArray();
        try { return fromResult(new JSONObject(raw)); }
        catch (Exception ignored) { return new JSONArray(); }
    }

    static JSONArray fromResult(JSONObject result) {
        if (result == null
                || !Boolean.TRUE.equals(result.opt("committed"))
                || !"DONE".equals(result.optString("status"))) {
            return new JSONArray();
        }
        return normalize(result.optJSONArray("deliverable_links"));
    }

    static JSONArray normalize(JSONArray input) {
        JSONArray output = new JSONArray();
        if (input == null) return output;
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < input.length() && output.length() < MAX_LINKS; i++) {
            JSONObject candidate = input.optJSONObject(i);
            if (candidate == null) continue;
            String name = candidate.optString("name", "").trim();
            String directAccess = candidate.optString("direct_access", "").trim();
            if (!validLabel(name) || !safeDirectAccess(directAccess) || !seen.add(directAccess)) continue;
            JSONObject accepted = new JSONObject();
            SelfRun3Engine.put(accepted, "name", name);
            SelfRun3Engine.put(accepted, "direct_access", directAccess);
            output.put(accepted);
        }
        return output;
    }

    static boolean safeDirectAccess(String raw) {
        if (raw == null) return false;
        String value = raw.trim();
        if (value.isEmpty() || value.length() > MAX_URL_CHARS || hasControl(value)) return false;
        try {
            URI uri = new URI(value);
            String scheme = uri.getScheme();
            if (scheme == null) return false;
            scheme = scheme.toLowerCase(Locale.ROOT);
            return ("https".equals(scheme) || "http".equals(scheme))
                    && !uri.isOpaque()
                    && uri.getHost() != null
                    && !uri.getHost().isEmpty()
                    && uri.getUserInfo() == null;
        } catch (Exception invalid) {
            return false;
        }
    }

    static void render(Activity activity, LinearLayout container, JSONArray input) {
        if (activity == null || container == null) return;
        JSONArray links = normalize(input);
        String fingerprint = links.toString();
        if (fingerprint.equals(container.getTag())) {
            container.setVisibility(links.length() == 0 ? View.GONE : View.VISIBLE);
            return;
        }
        container.setTag(fingerprint);
        container.removeAllViews();
        if (links.length() == 0) {
            container.setVisibility(View.GONE);
            return;
        }
        container.setVisibility(View.VISIBLE);
        container.addView(Ui.section(activity, "최종 산출물"));
        for (int i = 0; i < links.length(); i++) {
            JSONObject link = links.optJSONObject(i);
            if (link == null) continue;
            String name = link.optString("name");
            String directAccess = link.optString("direct_access");
            android.widget.Button button = Ui.outlinedButton(
                    activity, "다운로드 · " + name, v -> open(activity, directAccess));
            container.addView(button, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
    }

    private static void open(Activity activity, String directAccess) {
        if (!safeDirectAccess(directAccess)) return;
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(directAccess)));
        } catch (ActivityNotFoundException error) {
            Toast.makeText(activity, "산출물을 열 수 있는 앱이나 브라우저가 없습니다.", Toast.LENGTH_LONG).show();
        }
    }

    private static boolean validLabel(String value) {
        return value != null && !value.isEmpty() && value.length() <= MAX_NAME_CHARS && !hasControl(value);
    }

    private static boolean hasControl(String value) {
        for (int i = 0; i < value.length(); i++) if (Character.isISOControl(value.charAt(i))) return true;
        return false;
    }

    private SelfRunDeliverableLinks() {}
}
