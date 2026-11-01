package com.cleanframe.firetv;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Installed launchable apps and their Fire TV banners or icons. */
final class AppCatalog {
    private static final String TAG = "CleanFrame";
    private static final Pattern PACKAGE = Pattern.compile("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+");
    private static final int ICON_CACHE = 160;

    private final PackageManager packages;
    private final Map<String, byte[]> icons = new LinkedHashMap<String, byte[]>(32, .75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
            return size() > ICON_CACHE;
        }
    };

    AppCatalog(Context context) {
        packages = context.getPackageManager();
    }

    String listJson() {
        JSONArray out = new JSONArray();
        Set<String> seen = new HashSet<String>();
        // TV (leanback) entries first: they carry the banner Fire TV itself shows.
        add(out, seen, query(Intent.CATEGORY_LEANBACK_LAUNCHER));
        add(out, seen, query(Intent.CATEGORY_LAUNCHER));
        return out.toString();
    }

    private List<ResolveInfo> query(String category) {
        try {
            return packages.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(category), 0);
        } catch (RuntimeException error) {
            Log.w(TAG, "Could not list " + category, error);
            return Collections.emptyList();
        }
    }

    private void add(JSONArray out, Set<String> seen, List<ResolveInfo> found) {
        for (ResolveInfo info : found) {
            if (info.activityInfo == null) continue;
            String id = info.activityInfo.packageName;
            if (id == null || !seen.add(id)) continue;
            try {
                JSONObject app = new JSONObject();
                app.put("appId", id);
                app.put("name", String.valueOf(info.loadLabel(packages)));
                app.put("icon", "/appicon/" + Uri.encode(id) + ".png");
                out.put(app);
            } catch (JSONException error) {
                Log.w(TAG, "Skipped " + id, error);
            }
        }
    }

    Intent launchIntent(String id) {
        if (!valid(id)) return null;
        Intent intent = packages.getLeanbackLaunchIntentForPackage(id);
        if (intent == null) intent = packages.getLaunchIntentForPackage(id);
        if (intent != null) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        return intent;
    }

    /** PNG for /appicon/<package>.png: the TV banner (16:9) when the app has one, otherwise its icon. */
    byte[] iconPng(String id) {
        if (!valid(id)) return null;
        synchronized (icons) {
            if (icons.containsKey(id)) return icons.get(id);
        }
        byte[] png = render(id);
        synchronized (icons) {
            icons.put(id, png);
        }
        return png;
    }

    private byte[] render(String id) {
        Drawable drawable = null;
        boolean banner = false;
        try {
            Intent tv = packages.getLeanbackLaunchIntentForPackage(id);
            ComponentName component = tv == null ? null : tv.getComponent();
            if (component != null) {
                ActivityInfo activity = packages.getActivityInfo(component, 0);
                drawable = activity.loadBanner(packages);
            }
            if (drawable == null) drawable = packages.getApplicationBanner(id);
            banner = drawable != null;
            if (drawable == null) drawable = packages.getApplicationIcon(id);
        } catch (PackageManager.NameNotFoundException error) {
            return null;
        } catch (RuntimeException error) {
            Log.w(TAG, "No icon for " + id, error);
            return null;
        }
        int width = banner ? 320 : 192, height = banner ? 180 : 192;
        int intrinsicW = drawable.getIntrinsicWidth(), intrinsicH = drawable.getIntrinsicHeight();
        if (!banner && intrinsicW > 0 && intrinsicH > 0 && intrinsicW != intrinsicH) {
            // Keep a non-square icon's proportions inside the 192px box.
            float scale = 192f / Math.max(intrinsicW, intrinsicH);
            width = Math.max(1, Math.round(intrinsicW * scale));
            height = Math.max(1, Math.round(intrinsicH * scale));
        }
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        try {
            Canvas canvas = new Canvas(bitmap);
            drawable.setBounds(0, 0, width, height);
            drawable.draw(canvas);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            return out.toByteArray();
        } catch (RuntimeException error) {
            Log.w(TAG, "Could not draw icon for " + id, error);
            return null;
        } finally {
            bitmap.recycle();
        }
    }

    private static boolean valid(String id) {
        return id != null && id.length() <= 160 && PACKAGE.matcher(id).matches();
    }
}
