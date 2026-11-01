package com.cleanframe.firetv;

import android.app.Activity;
import android.content.Intent;
import android.provider.Settings;
import android.util.Base64;
import android.util.Log;
import android.webkit.JavascriptInterface;

import com.cleanframe.firetv.airplay.AirPlayHub;

import java.io.IOException;
import java.util.Locale;

/**
 * window.CleanFrameAndroid, used by app/device-firetv.js and app/photos.js. Every method returns a
 * string so the page can treat the bridge synchronously: "" means success, "missing" means the
 * target is not installed, "failed" means Fire OS refused it. Methods run on WebView's bridge thread.
 */
final class Bridge {
    private static final String TAG = "CleanFrame";
    private final Activity activity;
    private final AppCatalog catalog;
    private final SmartHome smartHome;
    private final AirPlayHub airPlay;
    private final Photos photos;

    Bridge(Activity activity, AppCatalog catalog, Photos photos) {
        this.activity = activity;
        this.catalog = catalog;
        this.photos = photos;
        this.smartHome = new SmartHome(activity.getPackageManager());
        this.airPlay = AirPlayHub.get(activity);
    }

    /** JSON array of {appId, name, icon} for every app with a TV or phone launcher entry. */
    @JavascriptInterface
    public String apps() {
        return catalog.listJson();
    }

    @JavascriptInterface
    public String selfId() {
        return activity.getPackageName();
    }

    /** System language, for example "de-DE". The page uses it when Language is Automatic. */
    @JavascriptInterface
    public String locale() {
        return Locale.getDefault().toLanguageTag();
    }

    @JavascriptInterface
    public String launch(String packageName) {
        Intent intent = catalog.launchIntent(packageName);
        return intent == null ? "missing" : start(intent);
    }

    @JavascriptInterface
    public String settings() {
        return start(new Intent(Settings.ACTION_SETTINGS));
    }

    /** Amazon's own Home screen. CleanFrame does not declare itself a Home app, so this resolves to Fire OS. */
    @JavascriptInterface
    public String systemHome() {
        return start(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME));
    }

    /** Fire TV's Smart Home dashboard, if this device has a launchable one. */
    @JavascriptInterface
    public String smartHome() {
        Intent intent = smartHome.intent();
        return intent == null ? "missing" : start(intent);
    }

    /* ---------- AirPlay receiver (built in, see the airplay package) ---------- */

    /** {enabled, running, starting, error, name, systemName, requireCode, code, session} */
    @JavascriptInterface
    public String airplayState() {
        return airPlay.stateJson();
    }

    @JavascriptInterface
    public String airplaySetEnabled(String on) {
        airPlay.setEnabled("true".equals(on));
        return "";
    }

    /** The name iPhones show; "" goes back to the Fire TV's own name. */
    @JavascriptInterface
    public String airplaySetName(String name) {
        airPlay.setName(name == null ? "" : name);
        return "";
    }

    @JavascriptInterface
    public String airplaySetCode(String on) {
        airPlay.setRequireCode("true".equals(on));
        return "";
    }

    /** Brings the mirrored picture (or a code the sender waits for) to the front; "missing" without one. */
    @JavascriptInterface
    public String airplayShow() {
        final boolean[] shown = new boolean[1];
        runSync(new Runnable() {
            @Override
            public void run() {
                shown[0] = airPlay.show();
            }
        });
        return shown[0] ? "" : "missing";
    }

    @JavascriptInterface
    public String airplayStop() {
        airPlay.endSession();
        return "";
    }

    /** Runs on the UI thread and waits briefly, so the bridge can answer synchronously. */
    private void runSync(final Runnable r) {
        final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    r.run();
                } finally {
                    done.countDown();
                }
            }
        });
        try {
            done.await(2, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /* ---------- Own wallpapers ---------- */

    /** [{"id", "state": "ready"|"pending", "meta": "<json>"}] */
    @JavascriptInterface
    public String wallpapers() {
        return photos.store.listJson();
    }

    @JavascriptInterface
    public String photoServerStart() {
        return photos.start();
    }

    @JavascriptInterface
    public String photoServerStop() {
        photos.stop();
        return "";
    }

    /** One prepared image, as base64 JPEG without the data: prefix. */
    @JavascriptInterface
    public String saveWallpaperFile(String id, String name, String base64) {
        try {
            byte[] jpeg = Base64.decode(base64, Base64.DEFAULT);
            return photos.store.save(id, name, jpeg) ? "" : "failed";
        } catch (IllegalArgumentException | IOException error) {
            Log.w(TAG, "Could not save " + name + " for " + id, error);
            return "failed";
        }
    }

    @JavascriptInterface
    public String commitWallpaper(String id, String meta) {
        try {
            return photos.store.commit(id, meta) ? "" : "failed";
        } catch (IOException error) {
            Log.w(TAG, "Could not finish " + id, error);
            return "failed";
        }
    }

    @JavascriptInterface
    public String removeWallpaper(String id) {
        return photos.store.remove(id) ? "" : "missing";
    }

    @JavascriptInterface
    public String discardIncoming(String id) {
        photos.store.discard(id);
        return "";
    }

    private String start(final Intent intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (intent.resolveActivity(activity.getPackageManager()) == null) return "failed";
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    activity.startActivity(intent);
                } catch (RuntimeException error) {
                    Log.w(TAG, "Could not start " + intent, error);
                }
            }
        });
        return "";
    }
}
