package com.cleanframe.firetv;

import android.content.Context;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;

/** Connects the photo store and server to the activity: the phone page, the TV's address and page events. */
final class Photos {
    interface Events {
        void photoEvent(String json);
    }

    private static final String TAG = "CleanFrame";
    private final Context context;
    private final Events events;
    final PhotoStore store;
    private PhotoServer server;

    Photos(Context context, Events events) {
        this.context = context;
        this.events = events;
        this.store = new PhotoStore(context.getFilesDir());
    }

    /** {"url": "http://192.168.1.20:8765/?pin=1234", "pin": "1234"} or {"error": "network"|"failed"}. */
    synchronized String start() {
        String address = localAddress();
        if (address == null) return "{\"error\":\"network\"}";
        try {
            if (server == null || !server.running()) server = new PhotoServer(store, new PhotoServer.Page() {
                @Override
                public byte[] html() throws IOException {
                    return asset("phone/index.html");
                }
            }, new PhotoServer.Listener() {
                @Override
                public void visited() {
                    emit("visited", null);
                }

                @Override
                public void received(String id) {
                    emit("received", id);
                }

                @Override
                public void removed(String id) {
                    emit("removed", id);
                }

                @Override
                public void stopped(String reason) {
                    emit("stopped", reason);
                }
            });
            int port = server.start();
            JSONObject out = new JSONObject();
            String base = "http://" + address + (port == 80 ? "" : ":" + port);
            out.put("url", base + "/?pin=" + server.pin());
            out.put("address", base);
            out.put("pin", server.pin());
            return out.toString();
        } catch (IOException | JSONException error) {
            Log.w(TAG, "Photo server did not start", error);
            return "{\"error\":\"failed\"}";
        }
    }

    synchronized void stop() {
        if (server != null) server.stop();
        server = null;
    }

    private void emit(String type, String value) {
        try {
            JSONObject event = new JSONObject();
            event.put("type", type);
            if (value != null) event.put(type.equals("stopped") ? "reason" : "id", value);
            events.photoEvent(event.toString());
        } catch (JSONException ignored) {
            // Constant keys; cannot fail.
        }
    }

    private byte[] asset(String name) throws IOException {
        InputStream in = context.getAssets().open(name);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    /** The TV's address in the home network (Wi-Fi or Ethernet). */
    static String localAddress() {
        String fallback = null;
        try {
            for (NetworkInterface face : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!face.isUp() || face.isLoopback() || face.isVirtual()) continue;
                for (InetAddress address : Collections.list(face.getInetAddresses())) {
                    if (!(address instanceof Inet4Address) || address.isLoopbackAddress()) continue;
                    if (address.isSiteLocalAddress()) return address.getHostAddress();
                    if (fallback == null) fallback = address.getHostAddress();
                }
            }
        } catch (Exception error) {
            Log.w(TAG, "No network address", error);
        }
        return fallback;
    }
}
