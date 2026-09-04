package dev.local.tailscalenetwatch;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.DateFormat;
import java.util.Date;

public final class MainActivity extends Activity
        implements SharedPreferences.OnSharedPreferenceChangeListener {
    private static final int NOTIFICATION_PERMISSION_REQUEST = 100;

    private SharedPreferences preferences;
    private TextView stateView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        preferences = getSharedPreferences(NetworkWatchService.PREFS_NAME, Context.MODE_PRIVATE);
        setContentView(buildContentView());
        requestNotificationPermissionIfNeeded();
    }

    @Override
    protected void onResume() {
        super.onResume();
        preferences.registerOnSharedPreferenceChangeListener(this);
        refreshState();
    }

    @Override
    protected void onPause() {
        preferences.unregisterOnSharedPreferenceChangeListener(this);
        super.onPause();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        refreshState();
    }

    private View buildContentView() {
        int padding = dp(24);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(padding, padding, padding, padding);

        TextView title = new TextView(this);
        title.setText(R.string.app_name);
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        root.addView(title, matchWrap());

        TextView explanation = new TextView(this);
        explanation.setText(R.string.explanation);
        explanation.setTextSize(16);
        explanation.setPadding(0, dp(12), 0, dp(20));
        root.addView(explanation, matchWrap());

        stateView = new TextView(this);
        stateView.setTextSize(15);
        stateView.setTextIsSelectable(true);
        stateView.setPadding(dp(16), dp(16), dp(16), dp(16));
        root.addView(stateView, matchWrap());

        Button start = new Button(this);
        start.setText(R.string.start_listener);
        start.setOnClickListener(view -> {
            preferences.edit().putBoolean(NetworkWatchService.KEY_ENABLED, true).apply();
            startWatcher(NetworkWatchService.ACTION_START);
            Toast.makeText(this, R.string.listener_started, Toast.LENGTH_SHORT).show();
        });
        root.addView(start, matchWrapWithTopMargin(18));

        Button restart = new Button(this);
        restart.setText(R.string.restart_now);
        restart.setOnClickListener(view -> {
            preferences.edit().putBoolean(NetworkWatchService.KEY_ENABLED, true).apply();
            startWatcher(NetworkWatchService.ACTION_RESTART_NOW);
            Toast.makeText(this, R.string.restart_requested, Toast.LENGTH_SHORT).show();
        });
        root.addView(restart, matchWrapWithTopMargin(8));

        Button permission = new Button(this);
        permission.setText(R.string.grant_shizuku);
        permission.setOnClickListener(view -> {
            preferences.edit().putBoolean(NetworkWatchService.KEY_ENABLED, true).apply();
            startWatcher(NetworkWatchService.ACTION_REQUEST_SHIZUKU_PERMISSION);
        });
        root.addView(permission, matchWrapWithTopMargin(8));

        Button stop = new Button(this);
        stop.setText(R.string.stop_listener);
        stop.setOnClickListener(view -> {
            preferences.edit().putBoolean(NetworkWatchService.KEY_ENABLED, false).apply();
            startWatcher(NetworkWatchService.ACTION_STOP);
            Toast.makeText(this, R.string.stop_requested, Toast.LENGTH_SHORT).show();
        });
        root.addView(stop, matchWrapWithTopMargin(8));

        TextView footnote = new TextView(this);
        footnote.setText(R.string.footnote);
        footnote.setTextSize(13);
        footnote.setGravity(Gravity.START);
        footnote.setPadding(0, dp(20), 0, 0);
        root.addView(footnote, matchWrap());

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        return scroll;
    }

    private void startWatcher(String action) {
        Intent intent = new Intent(this, NetworkWatchService.class).setAction(action);
        startForegroundService(intent);
    }

    private void refreshState() {
        if (stateView == null) {
            return;
        }
        boolean enabled = preferences.getBoolean(NetworkWatchService.KEY_ENABLED, false);
        String listener = preferences.getString(
                NetworkWatchService.KEY_LISTENER_STATE,
                "Not started"
        );
        String physicalLabel = preferences.getString(
                NetworkWatchService.KEY_PHYSICAL_LABEL,
                "No validated baseline"
        );
        String physicalSignature = preferences.getString(
                NetworkWatchService.KEY_PHYSICAL_SIGNATURE,
                "none"
        );
        String shizuku = preferences.getString(
                NetworkWatchService.KEY_SHIZUKU_STATE,
                "Unknown"
        );
        String last = preferences.getString(NetworkWatchService.KEY_LAST_RESULT, "None");
        long pendingExpiry = preferences.getLong(NetworkWatchService.KEY_PENDING_EXPIRY, 0L);
        String pending;
        if (pendingExpiry == 0L) {
            pending = "None";
        } else {
            String time = DateFormat.getDateTimeInstance().format(new Date(pendingExpiry));
            pending = pendingExpiry <= System.currentTimeMillis()
                    ? "Expired at " + time
                    : "Until " + time;
        }
        boolean recovery = preferences.getBoolean(
                NetworkWatchService.KEY_RECOVERY_PENDING,
                false
        );
        long updatedAt = preferences.getLong(NetworkWatchService.KEY_STATUS_TIME, 0L);
        String updated = updatedAt == 0L
                ? "Never"
                : DateFormat.getDateTimeInstance().format(new Date(updatedAt));

        stateView.setText(getString(
                R.string.status_template,
                getString(enabled ? R.string.enabled : R.string.disabled),
                listener,
                physicalLabel,
                physicalSignature,
                shizuku,
                pending,
                getString(recovery ? R.string.pending : R.string.clear),
                last,
                updated
        ));
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    NOTIFICATION_PERMISSION_REQUEST
            );
        }
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
    }

    private LinearLayout.LayoutParams matchWrapWithTopMargin(int marginDp) {
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(marginDp);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
