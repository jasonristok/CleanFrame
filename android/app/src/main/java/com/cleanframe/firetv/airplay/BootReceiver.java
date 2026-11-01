/*
 * CleanFrame AirPlay receiver. SPDX-License-Identifier: GPL-3.0-or-later
 */
package com.cleanframe.firetv.airplay;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Makes the Fire TV available for AirPlay right after it starts, before CleanFrame is opened. */
public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            AirPlayHub.get(context).ensureRunning();
        }
    }
}
