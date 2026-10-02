package dev.local.tailscalenetwatch;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;

import org.json.JSONObject;

/** Shell-only provisioning contract used by scrcpy Manager over ADB. */
public final class ProvisioningReceiver extends BroadcastReceiver {
    public static final int PROTOCOL_VERSION = 1;
    public static final String ACTION_STATUS =
            "dev.local.tailscalenetwatch.action.STATUS";
    public static final String ACTION_ENABLE =
            "dev.local.tailscalenetwatch.action.ENABLE";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (!ACTION_STATUS.equals(action) && !ACTION_ENABLE.equals(action)) {
            setResultCode(Activity.RESULT_CANCELED);
            setResultData("{\"protocolVersion\":1,\"error\":\"unsupported-action\"}");
            return;
        }

        SharedPreferences preferences = context.getSharedPreferences(
                NetworkWatchService.PREFS_NAME, Context.MODE_PRIVATE);
        String error = null;
        if (ACTION_ENABLE.equals(action)) {
            preferences.edit().putBoolean(NetworkWatchService.KEY_ENABLED, true).apply();
            try {
                Intent service = new Intent(context, NetworkWatchService.class)
                        .setAction(NetworkWatchService.ACTION_START);
                context.startForegroundService(service);
            } catch (Throwable failure) {
                error = failure.getClass().getSimpleName() + ": " + failure.getMessage();
            }
        }

        try {
            PackageInfo packageInfo = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);
            JSONObject result = new JSONObject();
            boolean enabled = preferences.getBoolean(NetworkWatchService.KEY_ENABLED, false);
            String shizuku = preferences.getString(
                    NetworkWatchService.KEY_SHIZUKU_STATE, "unknown");
            String pending = preferences.getString(
                    NetworkWatchService.KEY_PENDING_REASON, null);
            boolean recoveryPending = preferences.getBoolean(
                    NetworkWatchService.KEY_RECOVERY_PENDING, false);
            boolean tailscaleVpnPresent = hasTailscaleVpn(context);

            result.put("protocolVersion", PROTOCOL_VERSION);
            result.put("version", packageInfo.versionName);
            result.put("enabled", enabled);
            result.put("shizukuState", shizuku);
            result.put("physicalSignature", preferences.getString(
                    NetworkWatchService.KEY_PHYSICAL_SIGNATURE, null));
            result.put("physicalLabel", preferences.getString(
                    NetworkWatchService.KEY_PHYSICAL_LABEL, null));
            result.put("transitioning", pending != null || recoveryPending);
            result.put("pendingReason", pending);
            result.put("recoveryPending", recoveryPending);
            result.put("tailscaleVpnPresent", tailscaleVpnPresent);
            result.put("ready", enabled && !recoveryPending && pending == null
                    && tailscaleVpnPresent);
            result.put("lastResult", preferences.getString(
                    NetworkWatchService.KEY_LAST_RESULT, null));
            result.put("statusTime", preferences.getLong(
                    NetworkWatchService.KEY_STATUS_TIME, 0L));
            if (error != null) result.put("error", error);
            setResultCode(error == null ? Activity.RESULT_OK : Activity.RESULT_CANCELED);
            setResultData(result.toString());
        } catch (Throwable failure) {
            setResultCode(Activity.RESULT_CANCELED);
            setResultData("{\"protocolVersion\":1,\"error\":\"status-encoding-failed\"}");
        }
    }

    private static boolean hasTailscaleVpn(Context context) {
        ConnectivityManager connectivity = context.getSystemService(ConnectivityManager.class);
        if (connectivity == null) return false;
        final int tailscaleUid;
        try {
            tailscaleUid = context.getPackageManager().getApplicationInfo(
                    "com.tailscale.ipn", 0).uid;
        } catch (PackageManager.NameNotFoundException missing) {
            return false;
        }
        for (Network network : connectivity.getAllNetworks()) {
            NetworkCapabilities capabilities = connectivity.getNetworkCapabilities(network);
            if (capabilities != null
                    && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                    && (Build.VERSION.SDK_INT < Build.VERSION_CODES.R
                    || capabilities.getOwnerUid() == tailscaleUid)) return true;
        }
        return false;
    }
}
