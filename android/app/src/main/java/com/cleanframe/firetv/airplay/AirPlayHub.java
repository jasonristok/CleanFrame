/*
 * CleanFrame AirPlay receiver. SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Everything AirPlay on the Fire TV in one place: settings and the receiver's stable identity,
 * the server, its announcement on the network (_airplay._tcp and _raop._tcp through Android's
 * NsdManager), decoders for each session, and the state the CleanFrame page and the mirror
 * screen show. Server callbacks arrive on network threads; state changes happen on the main thread.
 */
package com.cleanframe.firetv.airplay;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.Surface;
import android.widget.Toast;

import com.cleanframe.firetv.R;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

public final class AirPlayHub implements AirPlayServer.Listener, Sinks.Factory, VideoDecoder.Events {
    static final String TAG = "CleanFrameAirPlay";
    private static final String PREFS = "airplay";
    private static final int PORT = 7000;
    private static AirPlayHub instance;

    public static synchronized AirPlayHub get(Context context) {
        if (instance == null) instance = new AirPlayHub(context.getApplicationContext());
        return instance;
    }

    private final Context app;
    private final SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Runnable> watchers = new CopyOnWriteArrayList<Runnable>();
    private Playfair playfair;

    /* guarded by this */
    private AirPlayServer server;
    private NsdManager.RegistrationListener airplayRegistration, raopRegistration;
    private int port;
    private String error = "";
    private boolean starting;

    /* main thread */
    private VideoDecoder video;
    private Surface display;
    private String client = "", clientModel = "", code;
    private boolean session, mirroring, paused, mirrorShown;
    private int videoWidth, videoHeight;
    private int foreground;
    private WifiManager.WifiLock wifiLock;

    private AirPlayHub(Context app) {
        this.app = app;
        this.prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /* ---------- settings ---------- */

    public boolean enabled() {
        return prefs.getBoolean("enabled", true);
    }

    public boolean requireCode() {
        return prefs.getBoolean("code", false);
    }

    /** The Fire TV's own name (Settings > My Fire TV > About), or "Fire TV". */
    public String systemName() {
        String n = null;
        try {
            n = Settings.Global.getString(app.getContentResolver(), "device_name");
        } catch (RuntimeException ignored) {
            // not readable on this build
        }
        n = clean(n);
        return n.isEmpty() ? "Fire TV" : n;
    }

    public String name() {
        String n = clean(prefs.getString("name", ""));
        return n.isEmpty() ? systemName() : n;
    }

    /** Names show in the sender's list; keep them short and printable. */
    static String clean(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length() && b.length() < 48; i++) {
            char c = s.charAt(i);
            if (c >= 0x20 && c != 0x7f && c != '@') b.append(c);
        }
        return b.toString().trim();
    }

    public void setEnabled(boolean on) {
        prefs.edit().putBoolean("enabled", on).apply();
        if (on) ensureRunning();
        else app.stopService(new Intent(app, AirPlayService.class));
        changed();
    }

    public void setName(String name) {
        String n = clean(name);
        prefs.edit().putString("name", n.equals(systemName()) ? "" : n).apply();
        restartIfRunning();
    }

    public void setRequireCode(boolean on) {
        prefs.edit().putBoolean("code", on).apply();
        restartIfRunning();
    }

    /** Starts the background service if AirPlay is switched on. Safe to call often. */
    public void ensureRunning() {
        if (!enabled()) return;
        Intent intent = new Intent(app, AirPlayService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(intent);
            else app.startService(intent);
        } catch (RuntimeException e) {
            Log.w(TAG, "could not start the AirPlay service", e);
        }
    }

    private void restartIfRunning() {
        boolean running;
        synchronized (this) {
            running = server != null || starting;
        }
        if (running) {
            stopServer();
            startServer();
            ensureRunning(); // refreshes the notification text
        }
        changed();
    }

    /* ---------- server life cycle (from AirPlayService) ---------- */

    void startServer() {
        synchronized (this) {
            if (server != null || starting) return;
            starting = true;
            error = "";
        }
        final String name = name();
        final boolean code = requireCode();
        new Thread(new Runnable() {
            @Override
            public void run() {
                AirPlayServer s = null;
                int p = 0;
                String failure = "";
                try {
                    if (playfair == null) {
                        InputStream in = app.getAssets().open("airplay/playfair.bin");
                        try {
                            playfair = Playfair.load(in);
                        } finally {
                            in.close();
                        }
                    }
                    s = new AirPlayServer(name, identity("device", 6), identity("key", 32), playfair, AirPlayHub.this, AirPlayHub.this);
                    s.setRequireCode(code);
                    p = s.start(PORT);
                } catch (IOException | RuntimeException e) {
                    Log.e(TAG, "AirPlay could not start", e);
                    failure = "start";
                    if (s != null) s.stop();
                    s = null;
                }
                boolean keep;
                synchronized (AirPlayHub.this) {
                    starting = false;
                    keep = s != null;
                    if (keep) {
                        server = s;
                        port = p;
                    } else {
                        error = failure;
                    }
                }
                if (keep) announce(s, p);
                changed();
            }
        }, "AirPlay-start").start();
    }

    void stopServer() {
        AirPlayServer s;
        synchronized (this) {
            s = server;
            server = null;
            port = 0;
        }
        unannounce();
        if (s != null) s.stop();
        main.post(new Runnable() {
            @Override
            public void run() {
                endState();
            }
        });
    }

    /** A stable random value, created once: the receiver's network identity and pairing key. */
    private byte[] identity(String key, int length) {
        String hex = prefs.getString(key, "");
        if (hex.length() == length * 2) {
            byte[] b = new byte[length];
            try {
                for (int i = 0; i < length; i++) b[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
                return b;
            } catch (NumberFormatException ignored) {
                // make a new one
            }
        }
        byte[] b = new byte[length];
        new SecureRandom().nextBytes(b);
        if ("device".equals(key)) {
            b[0] = (byte) ((b[0] & 0xfc) | 0x02); // a locally administered, unicast address
        }
        prefs.edit().putString(key, AirPlayServer.hex(b, false)).commit();
        return b;
    }

    /* ---------- network announcement ---------- */

    private void announce(AirPlayServer s, int p) {
        NsdManager nsd = (NsdManager) app.getSystemService(Context.NSD_SERVICE);
        if (nsd == null) {
            synchronized (this) {
                error = "network";
            }
            return;
        }
        NsdManager.RegistrationListener a = registration("_airplay._tcp"), r = registration("_raop._tcp");
        try {
            nsd.registerService(info(s.name(), "_airplay._tcp", p, s.airplayTxt()), NsdManager.PROTOCOL_DNS_SD, a);
            nsd.registerService(info(s.raopName(), "_raop._tcp", p, s.raopTxt()), NsdManager.PROTOCOL_DNS_SD, r);
        } catch (RuntimeException e) {
            Log.e(TAG, "could not announce AirPlay", e);
            synchronized (this) {
                error = "network";
            }
        }
        synchronized (this) {
            airplayRegistration = a;
            raopRegistration = r;
        }
    }

    private static NsdServiceInfo info(String name, String type, int port, Map<String, String> txt) {
        NsdServiceInfo i = new NsdServiceInfo();
        i.setServiceName(name);
        i.setServiceType(type);
        i.setPort(port);
        for (Map.Entry<String, String> e : txt.entrySet()) i.setAttribute(e.getKey(), e.getValue());
        return i;
    }

    private NsdManager.RegistrationListener registration(final String type) {
        return new NsdManager.RegistrationListener() {
            @Override
            public void onServiceRegistered(NsdServiceInfo info) {
                Log.i(TAG, "announced " + type + " as " + info.getServiceName());
            }

            @Override
            public void onRegistrationFailed(NsdServiceInfo info, int code) {
                Log.e(TAG, "announcing " + type + " failed: " + code);
                synchronized (AirPlayHub.this) {
                    error = "network";
                }
                changed();
            }

            @Override
            public void onServiceUnregistered(NsdServiceInfo info) {
                // done
            }

            @Override
            public void onUnregistrationFailed(NsdServiceInfo info, int code) {
                Log.w(TAG, "removing " + type + " failed: " + code);
            }
        };
    }

    private void unannounce() {
        NsdManager.RegistrationListener a, r;
        synchronized (this) {
            a = airplayRegistration;
            r = raopRegistration;
            airplayRegistration = null;
            raopRegistration = null;
        }
        NsdManager nsd = (NsdManager) app.getSystemService(Context.NSD_SERVICE);
        if (nsd == null) return;
        for (NsdManager.RegistrationListener l : new NsdManager.RegistrationListener[] {a, r}) {
            if (l == null) continue;
            try {
                nsd.unregisterService(l);
            } catch (RuntimeException ignored) {
                // never registered
            }
        }
    }

    /* ---------- sessions (AirPlayServer.Listener, network threads) ---------- */

    @Override
    public void code(final String c) {
        main.post(new Runnable() {
            @Override
            public void run() {
                code = c;
                if (c != null) {
                    openMirror();
                    if (!inFront()) toast(app.getString(R.string.airplay_code_toast, c));
                }
                changed();
            }
        });
    }

    @Override
    public void connected(final String name, final String model) {
        main.post(new Runnable() {
            @Override
            public void run() {
                session = true;
                client = name == null ? "" : name;
                clientModel = model == null ? "" : model;
                mirroring = false;
                paused = false;
                mirrorShown = false;
                holdWifi(true);
                changed();
            }
        });
    }

    @Override
    public void mirroring(final boolean active) {
        main.post(new Runnable() {
            @Override
            public void run() {
                mirroring = active;
                if (active) {
                    paused = false;
                    openMirror();
                    if (!inFront() && !canOpenFromBackground()) toast(app.getString(R.string.airplay_mirror_toast, client.isEmpty() ? app.getString(R.string.airplay_a_device) : client));
                }
                changed();
            }
        });
    }

    @Override
    public void disconnected() {
        main.post(new Runnable() {
            @Override
            public void run() {
                endState();
            }
        });
    }

    private void endState() {
        session = false;
        mirroring = false;
        paused = false;
        client = "";
        clientModel = "";
        video = null;
        holdWifi(false);
        changed();
    }

    @Override
    public void log(String message) {
        Log.i(TAG, message);
    }

    /* ---------- decoders (Sinks.Factory) ---------- */

    @Override
    public Sinks.Video video() {
        final VideoDecoder v = new VideoDecoder(this);
        main.post(new Runnable() {
            @Override
            public void run() {
                video = v;
                videoWidth = 0;
                videoHeight = 0;
                if (display != null) v.setDisplay(display);
            }
        });
        return v;
    }

    @Override
    public Sinks.Audio audio() {
        return new AudioPlayer();
    }

    @Override
    public void videoSize(final int width, final int height) {
        main.post(new Runnable() {
            @Override
            public void run() {
                videoWidth = width;
                videoHeight = height;
                changed();
            }
        });
    }

    @Override
    public void videoPaused(final boolean p) {
        main.post(new Runnable() {
            @Override
            public void run() {
                paused = p;
                changed();
            }
        });
    }

    /* ---------- the mirror screen ---------- */

    /** From MirrorActivity's surface callbacks (main thread). */
    void attachDisplay(Surface surface) {
        display = surface;
        if (video != null) video.setDisplay(surface);
    }

    void detachDisplay() {
        display = null;
        if (video != null) video.setDisplay(null);
    }

    void markMirrorShown() {
        mirrorShown = true;
    }

    /** CleanFrame or the mirror screen is in front: activities report it so toasts are only shown elsewhere. */
    public void inFront(boolean visible) {
        foreground = Math.max(0, foreground + (visible ? 1 : -1));
    }

    private boolean inFront() {
        return foreground > 0;
    }

    /**
     * Whether the mirror screen can come to the front while another app is open. Android 10 and later
     * (Fire OS 8) allow that only with the "display over other apps" permission, which Fire OS grants
     * only over ADB: adb shell appops set com.cleanframe.firetv SYSTEM_ALERT_WINDOW allow
     */
    boolean canOpenFromBackground() {
        if (Build.VERSION.SDK_INT < 29) return true;
        try {
            return Settings.canDrawOverlays(app);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Opens the mirror screen. From another app this needs Android's permission to start activities from the background. */
    private void openMirror() {
        Intent i = new Intent(app, MirrorActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        try {
            app.startActivity(i);
        } catch (RuntimeException e) {
            Log.w(TAG, "could not open the mirror screen", e);
        }
    }

    /** CleanFrame came to the front: show a mirror session the sender started while another app was open. */
    public void onHomeShown() {
        if ((mirroring && !mirrorShown) || code != null) openMirror();
    }

    /** "Show" in the AirPlay panel. */
    public boolean show() {
        if (!mirroring && code == null) return false;
        openMirror();
        return true;
    }

    /** Back on the code screen: stop showing the code (the sender may still type it). */
    void dismissCode() {
        if (code == null) return;
        code = null;
        changed();
    }

    public void endSession() {
        AirPlayServer s;
        synchronized (this) {
            s = server;
        }
        if (s != null) s.endSession();
    }

    String client() {
        return client;
    }

    String code() {
        return code;
    }

    boolean session() {
        return session;
    }

    boolean mirroringNow() {
        return mirroring;
    }

    boolean pausedNow() {
        return paused;
    }

    int videoWidth() {
        return videoWidth;
    }

    int videoHeight() {
        return videoHeight;
    }

    boolean hasPicture() {
        return video != null && video.hasPicture();
    }

    /* ---------- state for the page ---------- */

    public void watch(Runnable r) {
        watchers.add(r);
    }

    public void unwatch(Runnable r) {
        watchers.remove(r);
    }

    private void changed() {
        main.post(new Runnable() {
            @Override
            public void run() {
                for (Runnable r : watchers) r.run();
            }
        });
    }

    /** {enabled, running, starting, error, name, systemName, code, requireCode, session:{client, model, mirroring, paused}} */
    public String stateJson() {
        JSONObject o = new JSONObject();
        try {
            boolean running, isStarting;
            String err;
            synchronized (this) {
                running = server != null;
                isStarting = starting;
                err = error;
            }
            o.put("enabled", enabled());
            o.put("running", running);
            o.put("starting", isStarting);
            o.put("error", err);
            o.put("name", name());
            o.put("systemName", systemName());
            o.put("requireCode", requireCode());
            o.put("autoOpen", canOpenFromBackground());
            o.put("code", code == null ? JSONObject.NULL : code);
            if (session) {
                JSONObject s = new JSONObject();
                s.put("client", client);
                s.put("model", clientModel);
                s.put("mirroring", mirroring);
                s.put("paused", paused);
                o.put("session", s);
            } else {
                o.put("session", JSONObject.NULL);
            }
        } catch (JSONException e) {
            return "{}";
        }
        return o.toString();
    }

    /* ---------- helpers ---------- */

    private void holdWifi(boolean on) {
        try {
            if (on && wifiLock == null) {
                WifiManager wifi = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
                if (wifi == null) return;
                @SuppressWarnings("deprecation")
                int mode = Build.VERSION.SDK_INT >= 29 ? WifiManager.WIFI_MODE_FULL_LOW_LATENCY : WifiManager.WIFI_MODE_FULL_HIGH_PERF;
                wifiLock = wifi.createWifiLock(mode, "CleanFrame AirPlay");
                wifiLock.setReferenceCounted(false);
                wifiLock.acquire();
            } else if (!on && wifiLock != null) {
                wifiLock.release();
                wifiLock = null;
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "wifi lock", e);
        }
    }

    private void toast(String text) {
        try {
            Toast.makeText(app, text, Toast.LENGTH_LONG).show();
        } catch (RuntimeException ignored) {
            // no toast from here on this build
        }
    }
}
