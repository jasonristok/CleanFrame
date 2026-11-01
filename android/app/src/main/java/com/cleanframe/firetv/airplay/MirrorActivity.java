/*
 * CleanFrame AirPlay receiver. SPDX-License-Identifier: GPL-3.0-or-later
 *
 * The full-screen picture of a mirrored iPhone, iPad or Mac, or the code a sender asks for.
 * Back ends the mirroring; Home leaves it running in the background (sound continues) until the
 * sender stops or the AirPlay panel shows it again.
 */
package com.cleanframe.firetv.airplay;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.cleanframe.firetv.R;

public final class MirrorActivity extends Activity implements SurfaceHolder.Callback {
    private static final long BANNER_MS = 6000;

    private AirPlayHub hub;
    private FrameLayout root;
    private SurfaceView surface;
    private TextView banner, status, codeDigits;
    private LinearLayout codePanel;
    private int laidOutWidth = -1, laidOutHeight = -1;

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            refresh();
        }
    };
    /* A code that was just accepted disappears a moment before the session starts: wait briefly. */
    private final Runnable finishIfIdle = new Runnable() {
        @Override
        public void run() {
            if (!hub.session() && hub.code() == null) finish();
        }
    };
    private final Runnable hideBanner = new Runnable() {
        @Override
        public void run() {
            if (banner != null) banner.animate().alpha(0f).setDuration(400).start();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); // the theme is already full screen
        hub = AirPlayHub.get(this);

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        root.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                if (r - l != ol - or || b - t != ob - ot) layoutVideo();
            }
        });

        surface = new SurfaceView(this);
        surface.getHolder().addCallback(this);
        root.addView(surface, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER));

        status = text(34, 0xccffffff, false);
        status.setGravity(Gravity.CENTER);
        root.addView(status, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        codePanel = new LinearLayout(this);
        codePanel.setOrientation(LinearLayout.VERTICAL);
        codePanel.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView title = text(36, Color.WHITE, true);
        title.setText(R.string.mirror_code_title);
        codeDigits = text(150, Color.WHITE, true);
        codeDigits.setLetterSpacing(0.35f);
        TextView hint = text(30, 0xb3ffffff, false);
        hint.setText(R.string.mirror_code_hint);
        codePanel.addView(title);
        codePanel.addView(codeDigits);
        codePanel.addView(hint);
        root.addView(codePanel, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        banner = text(26, Color.WHITE, false);
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(0xb3000000);
        pill.setCornerRadius(dp(28));
        banner.setBackground(pill);
        banner.setPadding(dp(28), dp(14), dp(28), dp(14));
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        bp.bottomMargin = dp(40);
        root.addView(banner, bp);

        setContentView(root);
        hub.watch(refresh);
        refresh();
        showBanner();
    }

    private TextView text(int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp * getResources().getDisplayMetrics().heightPixels / 1080f);
        t.setTextColor(color);
        t.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private int dp(int px1080) {
        return Math.round(px1080 * getResources().getDisplayMetrics().heightPixels / 1080f);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        refresh();
        showBanner();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hub.inFront(true);
        hub.markMirrorShown();
        refresh();
    }

    @Override
    protected void onPause() {
        hub.inFront(false);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        hub.unwatch(refresh);
        root.removeCallbacks(hideBanner);
        root.removeCallbacks(finishIfIdle);
        super.onDestroy();
    }

    /* ---------- surface ---------- */

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        hub.attachDisplay(holder.getSurface());
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        hub.attachDisplay(holder.getSurface());
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        hub.detachDisplay();
    }

    /** Fits the sender's picture into the screen without stretching. */
    private void layoutVideo() {
        int vw = hub.videoWidth(), vh = hub.videoHeight(), sw = root.getWidth(), sh = root.getHeight();
        if (vw <= 0 || vh <= 0 || sw <= 0 || sh <= 0) return;
        int w = sw, h = Math.round(sw * (float) vh / vw);
        if (h > sh) {
            h = sh;
            w = Math.round(sh * (float) vw / vh);
        }
        if (w == laidOutWidth && h == laidOutHeight) return;
        laidOutWidth = w;
        laidOutHeight = h;
        surface.setLayoutParams(new FrameLayout.LayoutParams(w, h, Gravity.CENTER));
    }

    /* ---------- state ---------- */

    private void refresh() {
        if (isFinishing()) return;
        String code = hub.code();
        if (!hub.session() && code == null) {
            root.removeCallbacks(finishIfIdle);
            root.postDelayed(finishIfIdle, 1500);
            return;
        }
        String who = hub.client().isEmpty() ? getString(R.string.airplay_a_device) : hub.client();
        boolean showCode = code != null && !hub.mirroringNow();
        codePanel.setVisibility(showCode ? View.VISIBLE : View.GONE);
        if (showCode) codeDigits.setText(code);
        String line = null;
        if (!showCode) {
            if (hub.mirroringNow() && hub.pausedNow()) line = getString(R.string.mirror_paused, who);
            else if (hub.mirroringNow() && !hub.hasPicture()) line = getString(R.string.mirror_connecting, who);
            else if (!hub.mirroringNow()) line = getString(R.string.mirror_audio, who);
        }
        status.setVisibility(line == null ? View.GONE : View.VISIBLE);
        if (line != null) status.setText(line);
        banner.setText(getString(R.string.mirror_hint, who));
        layoutVideo();
    }

    private void showBanner() {
        banner.animate().cancel();
        banner.setAlpha(1f);
        root.removeCallbacks(hideBanner);
        root.postDelayed(hideBanner, BANNER_MS);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int key = event.getKeyCode();
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        switch (key) {
            case KeyEvent.KEYCODE_BACK:
            case KeyEvent.KEYCODE_ESCAPE:
            case KeyEvent.KEYCODE_BUTTON_B:
                if (down && event.getRepeatCount() == 0) {
                    if (hub.session()) hub.endSession();
                    else hub.dismissCode();
                    finish();
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
            case KeyEvent.KEYCODE_MENU:
                if (down) showBanner();
                return true;
            default:
                return super.dispatchKeyEvent(event);
        }
    }
}
