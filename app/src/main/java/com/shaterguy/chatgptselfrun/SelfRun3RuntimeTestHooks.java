package com.shaterguy.chatgptselfrun;

import android.content.Context;
import android.webkit.WebView;

import java.util.function.BooleanSupplier;

/** QA-only seams for deterministic installed-runtime coverage. Inert in the formal app. */
final class SelfRun3RuntimeTestHooks {
    interface DriveFactory {
        SelfRun3DrivePort create(Context context, SelfRunStore store, SelfRun3Ledger ledger,
                                 BooleanSupplier permitted);
    }

    interface BrowserInstaller {
        void beforeAutomation(WebView web);
    }

    interface PageLoader {
        void load(WebView web, String target);
    }

    private static volatile DriveFactory driveFactory;
    private static volatile BrowserInstaller browserInstaller;
    private static volatile PageLoader pageLoader;
    private static volatile boolean dropNextAcceptedResultProgression;

    private SelfRun3RuntimeTestHooks() { }

    static synchronized void install(DriveFactory drive, BrowserInstaller browser,
                                     PageLoader loader, boolean dropProgression) {
        requireQa();
        driveFactory = drive;
        browserInstaller = browser;
        pageLoader = loader;
        dropNextAcceptedResultProgression = dropProgression;
    }

    static synchronized void clear() {
        driveFactory = null;
        browserInstaller = null;
        pageLoader = null;
        dropNextAcceptedResultProgression = false;
    }

    static SelfRun3DrivePort createDrive(Context context, SelfRunStore store, SelfRun3Ledger ledger,
                                         BooleanSupplier permitted) {
        DriveFactory factory = enabled() ? driveFactory : null;
        return factory == null ? null : factory.create(context, store, ledger, permitted);
    }

    static boolean bypassDriveAuthorization() {
        return enabled() && driveFactory != null;
    }

    static void beforeAutomation(WebView web) {
        BrowserInstaller installer = enabled() ? browserInstaller : null;
        if (installer != null) installer.beforeAutomation(web);
    }

    static boolean loadPage(WebView web, String target) {
        PageLoader loader = enabled() ? pageLoader : null;
        if (loader == null) return false;
        loader.load(web, target);
        return true;
    }

    static synchronized boolean consumeDropAfterAcceptedResult() {
        if (!enabled() || !dropNextAcceptedResultProgression) return false;
        dropNextAcceptedResultProgression = false;
        return true;
    }

    private static boolean enabled() {
        return BuildConfig.APPLICATION_ID.endsWith(".test");
    }

    private static void requireQa() {
        if (!enabled()) throw new IllegalStateException("runtime test hooks require TEST applicationId");
    }
}
