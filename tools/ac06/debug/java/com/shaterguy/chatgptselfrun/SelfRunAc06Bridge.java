package com.shaterguy.chatgptselfrun;

import android.webkit.WebView;

import java.lang.reflect.Field;

final class SelfRunAc06Bridge {
    static volatile SelfRunService service;

    private SelfRunAc06Bridge() {}

    static WebView webView() {
        SelfRunService current = service;
        if (current == null) return null;
        try {
            Field field = SelfRunService.class.getDeclaredField("webView");
            field.setAccessible(true);
            return (WebView) field.get(current);
        } catch (Throwable ignored) {
            return null;
        }
    }

    static int generation() {
        SelfRunService current = service;
        if (current == null) return -1;
        try {
            Field field = SelfRunService.class.getDeclaredField("generation");
            field.setAccessible(true);
            return field.getInt(current);
        } catch (Throwable ignored) {
            return -1;
        }
    }

    static Object observerPort() {
        SelfRunService current = service;
        if (current == null) return null;
        try {
            Field field = SelfRunService.class.getDeclaredField("observerPort");
            field.setAccessible(true);
            return field.get(current);
        } catch (Throwable ignored) {
            return null;
        }
    }

    static long observerEventCount() {
        SelfRunService current = service;
        if (current == null) return -1L;
        try {
            Field field = SelfRunService.class.getDeclaredField("observerEventCount");
            field.setAccessible(true);
            return field.getLong(current);
        } catch (Throwable ignored) {
            return -1L;
        }
    }

    static SelfRunStore store() {
        SelfRunService current = service;
        if (current == null) return null;
        try {
            Field field = SelfRunService.class.getDeclaredField("store");
            field.setAccessible(true);
            return (SelfRunStore) field.get(current);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
