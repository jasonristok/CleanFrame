package com.cleanframe.firetv;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.cleanframe.firetv.airplay.AirPlayHub;

import org.json.JSONObject;

/**
 * Full-screen host for the CleanFrame web interface in app/.
 *
 * The page is served from https://appassets.androidplatform.net/assets/ so it has a normal web
 * origin (localStorage, fonts, CORS for the weather request). The D-pad reaches the page as arrow
 * keys and OK as Enter; Back and Menu never do, so this activity forwards them to
 * window.CleanFrameRemote, which main.js defines.
 */
public final class MainActivity extends Activity implements Photos.Events {
    static final String HOST = "appassets.androidplatform.net";
    private static final String START = "https://" + HOST + "/assets/index.html";

    private WebView web;
    private boolean loaded;
    private Photos photos;
    private AirPlayHub airPlay;
    /* AirPlay state changes (a phone connects, the name changes): the page redraws its panel. */
    private final Runnable airPlayChanged = new Runnable() {
        @Override
        public void run() {
            if (loaded) send("airplayEvent", airPlay.stateJson());
        }
    };

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            // Debug builds can be inspected from chrome://inspect over ADB.
            WebView.setWebContentsDebuggingEnabled(true);
        }

        final AppCatalog catalog = new AppCatalog(this);
        photos = new Photos(this, this);
        final LocalContent content = new LocalContent(this, catalog, photos.store);

        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        web.setVerticalScrollBarEnabled(false);
        web.setHorizontalScrollBarEnabled(false);
        web.setOverScrollMode(View.OVER_SCROLL_NEVER);
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);

        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setTextZoom(100); // The layout is a fixed 1920 x 1080 stage; ignore system font scaling.
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setMediaPlaybackRequiresUserGesture(true);
        if (Build.VERSION.SDK_INT >= 26) settings.setSafeBrowsingEnabled(false); // Only bundled pages load here.

        web.addJavascriptInterface(new Bridge(this, catalog, photos), "CleanFrameAndroid");
        web.setWebChromeClient(new WebChromeClient());
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return content.open(request.getUrl());
            }

            @SuppressWarnings("deprecation")
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, String url) {
                return content.open(Uri.parse(url));
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return !HOST.equals(request.getUrl().getHost());
            }

            @SuppressWarnings("deprecation")
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return !HOST.equals(Uri.parse(url).getHost());
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                loaded = true;
            }
        });

        setContentView(web);
        web.loadUrl(START);
        web.requestFocus();

        airPlay = AirPlayHub.get(this);
        airPlay.watch(airPlayChanged);
        airPlay.ensureRunning();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        String key = remoteKey(event.getKeyCode());
        if (key == null) return super.dispatchKeyEvent(event);
        // Consume both halves of the press; act once per physical press, like main.js does for OK.
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) send("press", key);
        return true;
    }

    private static String remoteKey(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
            case KeyEvent.KEYCODE_BUTTON_B:
                return "back";
            case KeyEvent.KEYCODE_MENU:
                return "menu";
            default:
                return null;
        }
    }

    /** Calls window.CleanFrameRemote.<method>(arg) if the page has loaded main.js. */
    private void send(String method, String arg) {
        if (web == null) return;
        String js = "window.CleanFrameRemote&&window.CleanFrameRemote." + method + "(" + (arg == null ? "" : JSONObject.quote(arg)) + ")";
        web.evaluateJavascript(js, null);
    }

    /** From the photo server's threads: tell the page that a photo arrived, was removed, etc. */
    @Override
    public void photoEvent(final String json) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (loaded) send("photoEvent", json);
            }
        });
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // Opened again from the Home button (via a redirect app) or the app list: show Home itself.
        if (loaded) send("home", null);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (airPlay != null) {
            airPlay.inFront(true);
            airPlay.ensureRunning();
            airPlay.onHomeShown(); // a phone started mirroring while another app was in front
        }
        if (web == null) return;
        web.onResume();
        web.resumeTimers();
        web.requestFocus();
        if (loaded) send("resume", null);
    }

    @Override
    protected void onPause() {
        if (airPlay != null) airPlay.inFront(false);
        if (web != null) {
            web.onPause();
            web.pauseTimers(); // No clock, weather or rotation work while another app is in front.
        }
        super.onPause();
    }

    @Override
    protected void onStop() {
        // The phone upload page only works while the Own photos screen is in front.
        if (photos != null) photos.stop();
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (airPlay != null) airPlay.unwatch(airPlayChanged);
        if (photos != null) photos.stop();
        if (web != null) {
            web.removeJavascriptInterface("CleanFrameAndroid");
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
