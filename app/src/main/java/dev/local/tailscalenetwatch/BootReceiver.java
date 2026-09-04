package dev.local.tailscalenetwatch;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

public final class BootReceiver extends BroadcastReceiver {
    @SuppressLint("ApplySharedPref") // Baseline must be cleared before the boot service starts.
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) {
            return;
        }
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }

        SharedPreferences preferences = context.getSharedPreferences(
                NetworkWatchService.PREFS_NAME,
                Context.MODE_PRIVATE
        );
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            preferences.edit()
                    .remove(NetworkWatchService.KEY_PHYSICAL_SIGNATURE)
                    .remove(NetworkWatchService.KEY_PHYSICAL_LABEL)
                    .remove(NetworkWatchService.KEY_PENDING_SIGNATURE)
                    .remove(NetworkWatchService.KEY_PENDING_LABEL)
                    .remove(NetworkWatchService.KEY_PENDING_REASON)
                    .remove(NetworkWatchService.KEY_PENDING_REQUESTED_AT)
                    .remove(NetworkWatchService.KEY_PENDING_EXPIRY)
                    .remove(NetworkWatchService.KEY_PENDING_FOLLOW_UP)
                    .remove(NetworkWatchService.KEY_PENDING_MANUAL)
                    .commit();
        }

        if (!preferences.getBoolean(NetworkWatchService.KEY_ENABLED, false)) {
            return;
        }
        Intent serviceIntent = new Intent(context, NetworkWatchService.class)
                .setAction(NetworkWatchService.ACTION_START);
        try {
            context.startForegroundService(serviceIntent);
        } catch (RuntimeException exception) {
            Log.e(NetworkWatchService.TAG, "Boot listener start failed", exception);
        }
    }
}
