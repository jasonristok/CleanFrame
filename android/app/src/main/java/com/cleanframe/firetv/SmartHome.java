package com.cleanframe.firetv;

import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.util.Log;

import java.util.List;
import java.util.Locale;

/**
 * Finds Fire TV's own Smart Home dashboard. Amazon documents no intent for it, so this looks for
 * the dashboard package seen on Fire OS 7 ("Alexa Smart Home", com.amazon.smarthomemapviewapp),
 * then for any other Amazon package whose name says smart home. Returns null when nothing
 * launchable is found; the page then explains the Alexa voice command instead.
 */
final class SmartHome {
    private static final String TAG = "CleanFrame";
    static final String DASHBOARD = "com.amazon.smarthomemapviewapp";

    private final PackageManager packages;
    private Intent found;
    private boolean searched;

    SmartHome(PackageManager packages) {
        this.packages = packages;
    }

    synchronized Intent intent() {
        if (!searched) {
            searched = true;
            found = fromPackage(DASHBOARD);
            if (found == null) found = search();
            if (found != null) Log.i(TAG, "Smart Home dashboard: " + found.getComponent() + " " + found);
            else Log.i(TAG, "No Smart Home dashboard activity found");
        }
        return found == null ? null : new Intent(found);
    }

    private Intent search() {
        List<PackageInfo> installed;
        try {
            installed = packages.getInstalledPackages(0);
        } catch (RuntimeException error) {
            return null;
        }
        for (PackageInfo info : installed) {
            String name = info.packageName.toLowerCase(Locale.ROOT);
            if (!name.startsWith("com.amazon.") || DASHBOARD.equals(info.packageName)) continue;
            if (!name.contains("smarthome") && !name.contains("smart.home")) continue;
            Intent intent = fromPackage(info.packageName);
            if (intent != null) return intent;
        }
        return null;
    }

    private Intent fromPackage(String id) {
        Intent intent = null;
        try {
            intent = packages.getLeanbackLaunchIntentForPackage(id);
            if (intent == null) intent = packages.getLaunchIntentForPackage(id);
        } catch (RuntimeException ignored) {
            // Fall through to the activity list.
        }
        if (intent != null) return intent;
        ActivityInfo best = null;
        int bestScore = -1;
        try {
            PackageInfo info = packages.getPackageInfo(id, PackageManager.GET_ACTIVITIES);
            if (info.activities == null) return null;
            for (ActivityInfo activity : info.activities) {
                if (!activity.exported || !activity.enabled || activity.permission != null) continue;
                String name = activity.name.toLowerCase(Locale.ROOT);
                int score = (name.contains("dashboard") ? 4 : 0) + (name.contains("smarthome") ? 2 : 0) + (name.contains("main") || name.contains("launch") ? 1 : 0);
                if (score > bestScore) {
                    best = activity;
                    bestScore = score;
                }
            }
        } catch (PackageManager.NameNotFoundException missing) {
            return null;
        } catch (RuntimeException error) {
            Log.w(TAG, "Could not inspect " + id, error);
            return null;
        }
        return best == null ? null : new Intent(Intent.ACTION_MAIN).setClassName(best.packageName, best.name);
    }
}
