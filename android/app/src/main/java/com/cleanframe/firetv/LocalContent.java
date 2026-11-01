package com.cleanframe.firetv;

import android.content.Context;
import android.content.res.AssetManager;
import android.net.Uri;
import android.webkit.WebResourceResponse;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Serves https://appassets.androidplatform.net/assets/... from the APK's assets (a copy of app/),
 * /appicon/<package>.png from the installed apps and /userwall/... from own photos. Every other
 * host loads from the network as usual; only the weather request leaves the device.
 */
final class LocalContent {
    private static final Map<String, String> TYPES = new HashMap<String, String>();

    static {
        TYPES.put("html", "text/html");
        TYPES.put("js", "application/javascript");
        TYPES.put("css", "text/css");
        TYPES.put("json", "application/json");
        TYPES.put("svg", "image/svg+xml");
        TYPES.put("png", "image/png");
        TYPES.put("jpg", "image/jpeg");
        TYPES.put("jpeg", "image/jpeg");
        TYPES.put("webp", "image/webp");
        TYPES.put("woff2", "font/woff2");
        TYPES.put("woff", "font/woff");
        TYPES.put("ttf", "font/ttf");
        TYPES.put("otf", "font/otf");
        TYPES.put("txt", "text/plain");
        TYPES.put("md", "text/markdown");
        TYPES.put("xml", "application/xml");
    }

    private final AssetManager assets;
    private final AppCatalog catalog;
    private final PhotoStore photos;

    LocalContent(Context context, AppCatalog catalog, PhotoStore photos) {
        this.assets = context.getAssets();
        this.catalog = catalog;
        this.photos = photos;
    }

    WebResourceResponse open(Uri uri) {
        if (uri == null || !MainActivity.HOST.equals(uri.getHost())) return null;
        String path = uri.getPath();
        if (path == null) return notFound();
        if (path.startsWith("/assets/")) return asset(path.substring("/assets/".length()));
        if (path.startsWith("/appicon/") && path.endsWith(".png")) {
            byte[] png = catalog.iconPng(path.substring("/appicon/".length(), path.length() - ".png".length()));
            return png == null ? notFound() : response("image/png", 200, "OK", new ByteArrayInputStream(png), "max-age=3600");
        }
        if (path.startsWith("/userwall/")) return ownWallpaper(path.substring("/userwall/".length()));
        return notFound();
    }

    /** /userwall/<id>/<name>.jpg for prepared photos, /userwall/incoming/<id>.jpg for one being prepared. */
    private WebResourceResponse ownWallpaper(String rest) {
        int slash = rest.indexOf('/');
        if (slash <= 0 || !rest.endsWith(".jpg")) return notFound();
        String first = rest.substring(0, slash), name = rest.substring(slash + 1, rest.length() - ".jpg".length());
        File file;
        if ("incoming".equals(first) && PhotoStore.validId(name)) file = photos.incomingFile(name);
        else if (PhotoStore.validId(first) && PhotoStore.NAMES.contains(name)) file = photos.file(first, name);
        else return notFound();
        try {
            return response("image/jpeg", 200, "OK", new FileInputStream(file), "max-age=86400");
        } catch (IOException missing) {
            return notFound();
        }
    }

    private WebResourceResponse asset(String name) {
        if (name.isEmpty() || name.startsWith("/") || name.contains("..")) return notFound();
        try {
            InputStream in = assets.open(name);
            return response(type(name), 200, "OK", in, "no-cache");
        } catch (IOException missing) {
            return notFound();
        }
    }

    private static String type(String name) {
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
        String type = TYPES.get(ext);
        return type == null ? "application/octet-stream" : type;
    }

    private static WebResourceResponse response(String type, int status, String reason, InputStream data, String cache) {
        Map<String, String> headers = new HashMap<String, String>();
        headers.put("Cache-Control", cache);
        headers.put("Access-Control-Allow-Origin", "https://" + MainActivity.HOST);
        String encoding = type.startsWith("text/") || type.endsWith("javascript") || type.endsWith("json") || type.endsWith("xml") ? "utf-8" : null;
        return new WebResourceResponse(type, encoding, status, reason, headers, data);
    }

    private static WebResourceResponse notFound() {
        return response("text/plain", 404, "Not Found", new ByteArrayInputStream(new byte[0]), "no-cache");
    }
}
