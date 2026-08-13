package com.shaterguy.chatgptselfrun;

import android.webkit.WebView;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

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

    static boolean observerInstallInFlight() {
        return booleanField("observerInstallInFlight");
    }

    static boolean recoveryInProgress() {
        return booleanField("recoveryInProgress");
    }

    static String observerLease() {
        SelfRunService current = service;
        if (current == null) return "";
        try {
            Field field = SelfRunService.class.getDeclaredField("observerLease");
            field.setAccessible(true);
            Object value = field.get(current);
            return value == null ? "" : String.valueOf(value);
        } catch (Throwable ignored) {
            return "";
        }
    }

    static void ensureObserverNow() {
        SelfRunService current = service;
        if (current == null) return;
        try {
            Method method = SelfRunService.class.getDeclaredMethod("ensureDomObserver");
            method.setAccessible(true);
            method.invoke(current);
        } catch (Throwable failure) {
            throw new IllegalStateException("ensure_observer_failed:" + failure.getClass().getSimpleName(), failure);
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

    private static boolean booleanField(String name) {
        SelfRunService current = service;
        if (current == null) return false;
        try {
            Field field = SelfRunService.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.getBoolean(current);
        } catch (Throwable ignored) {
            return false;
        }
    }
}
