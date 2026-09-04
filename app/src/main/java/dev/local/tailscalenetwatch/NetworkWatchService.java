package dev.local.tailscalenetwatch;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import rikka.shizuku.Shizuku;

/** Foreground lifetime holder for two sleeping ConnectivityManager callbacks. */
public final class NetworkWatchService extends Service {
    static final String TAG = "TailscaleNetWatch";

    public static final String PREFS_NAME = "watcher_state";
    public static final String KEY_ENABLED = "enabled";
    public static final String KEY_LISTENER_STATE = "listener_state";
    public static final String KEY_PHYSICAL_SIGNATURE = "physical_signature";
    public static final String KEY_PHYSICAL_LABEL = "physical_label";
    public static final String KEY_SHIZUKU_STATE = "shizuku_state";
    public static final String KEY_PENDING_SIGNATURE = "pending_signature";
    public static final String KEY_PENDING_LABEL = "pending_label";
    public static final String KEY_PENDING_REASON = "pending_reason";
    public static final String KEY_PENDING_REQUESTED_AT = "pending_requested_at";
    public static final String KEY_PENDING_EXPIRY = "pending_expiry";
    public static final String KEY_PENDING_FOLLOW_UP = "pending_follow_up";
    public static final String KEY_PENDING_MANUAL = "pending_manual";
    public static final String KEY_LAST_RESULT = "last_result";
    public static final String KEY_STATUS_TIME = "status_time";

    static final String KEY_RECOVERY_PENDING = "recovery_pending";
    static final String KEY_RECOVERY_SIGNATURE = "recovery_signature";
    static final String KEY_RECOVERY_LABEL = "recovery_label";
    static final String KEY_RECOVERY_REASON = "recovery_reason";
    static final String KEY_RECOVERY_STARTED_AT = "recovery_started_at";
    static final String KEY_RECOVERY_RETRY_SENT_AT = "recovery_retry_sent_at";
    static final String KEY_RECOVERY_IMMEDIATE_SENT = "recovery_immediate_sent";
    static final String KEY_RECOVERY_OLD_VPNS = "recovery_old_vpns";

    public static final String ACTION_START = "dev.local.tailscalenetwatch.action.START";
    public static final String ACTION_RESTART_NOW =
            "dev.local.tailscalenetwatch.action.RESTART_NOW";
    public static final String ACTION_REQUEST_SHIZUKU_PERMISSION =
            "dev.local.tailscalenetwatch.action.REQUEST_SHIZUKU_PERMISSION";
    public static final String ACTION_STOP = "dev.local.tailscalenetwatch.action.STOP";

    private static final String CHANNEL_ID = "network_watch";
    private static final int NOTIFICATION_ID = 7001;
    private static final int SHIZUKU_PERMISSION_REQUEST = 7002;
    private static final String TAILSCALE_PACKAGE = "com.tailscale.ipn";
    private static final String TAILSCALE_RECEIVER = "com.tailscale.ipn.IPNReceiver";
    private static final String TAILSCALE_CONNECT = "com.tailscale.ipn.CONNECT_VPN";

    private final Set<Long> vpnHandles = new HashSet<>();
    private final Map<Long, String> physicalSignaturesByHandle = new HashMap<>();

    private ConnectivityManager connectivityManager;
    private NotificationManager notificationManager;
    private SharedPreferences preferences;
    private HandlerThread callbackThread;
    private Handler handler;
    private HandoverStateMachine handover;
    private RecoveryCoordinator recovery;

    private ConnectivityManager.NetworkCallback physicalCallback;
    private ConnectivityManager.NetworkCallback vpnCallback;
    private boolean physicalCallbackRegistered;
    private boolean vpnCallbackRegistered;
    private boolean destroyed;
    private boolean stopAfterTransaction;
    private boolean shizukuReady;
    private boolean permissionRequestOutstanding;

    private Shizuku.UserServiceArgs userServiceArgs;
    private ServiceConnection userServiceConnection;
    private IBinder userServiceBinder;
    private boolean userServiceBinding;
    private boolean privilegedCallFinished;
    private boolean replacementObserved;
    private long replacementHandle = -1L;
    private String activeRestartSignature;
    private String activeRestartLabel;
    private String activeRestartReason;

    private final Runnable debounceRunnable = new Runnable() {
        @Override
        public void run() {
            applyDecision(handover.onDebounce(System.currentTimeMillis()));
        }
    };

    private final Runnable recoveryDeadlineRunnable = new Runnable() {
        @Override
        public void run() {
            handleRecoveryDeadline();
        }
    };

    private final IBinder.DeathRecipient userServiceDeathRecipient = new IBinder.DeathRecipient() {
        @Override
        public void binderDied() {
            postToHandler(() -> handlePrivilegedChannelDied("UserService binder died"));
        }
    };

    private final Shizuku.OnBinderReceivedListener shizukuBinderReceivedListener =
            new Shizuku.OnBinderReceivedListener() {
                @Override
                public void onBinderReceived() {
                    handleShizukuBinderReceived();
                }
            };

    private final Shizuku.OnBinderDeadListener shizukuBinderDeadListener =
            new Shizuku.OnBinderDeadListener() {
                @Override
                public void onBinderDead() {
                    handleShizukuBinderDead();
                }
            };

    private final Shizuku.OnRequestPermissionResultListener permissionResultListener =
            new Shizuku.OnRequestPermissionResultListener() {
                @Override
                public void onRequestPermissionResult(int requestCode, int grantResult) {
                    if (requestCode != SHIZUKU_PERMISSION_REQUEST) {
                        return;
                    }
                    permissionRequestOutstanding = false;
                    boolean granted = grantResult == PackageManager.PERMISSION_GRANTED;
                    setShizukuReady(granted, granted
                            ? describeReadyShizuku()
                            : "Permission denied");
                }
            };

    @Override
    public void onCreate() {
        super.onCreate();
        preferences = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        connectivityManager = getSystemService(ConnectivityManager.class);
        notificationManager = getSystemService(NotificationManager.class);

        createNotificationChannel();
        startForegroundCompat(buildNotification());

        callbackThread = new HandlerThread("tailscale-network-events");
        callbackThread.start();
        handler = new Handler(callbackThread.getLooper());

        handover = new HandoverStateMachine(
                preferences.getString(KEY_PHYSICAL_SIGNATURE, null),
                preferences.getString(KEY_PHYSICAL_LABEL, null),
                preferences.getString(KEY_PENDING_SIGNATURE, null),
                preferences.getString(KEY_PENDING_LABEL, null),
                preferences.getString(KEY_PENDING_REASON, null),
                preferences.getLong(KEY_PENDING_REQUESTED_AT, 0L),
                preferences.getLong(KEY_PENDING_EXPIRY, 0L),
                preferences.getBoolean(KEY_PENDING_FOLLOW_UP, false),
                preferences.getBoolean(KEY_PENDING_MANUAL, false)
        );

        restoreRecoveryMarker();
        registerNetworkCallbacks();
        registerShizukuListeners();
        if (recovery != null && recovery.isPending()) {
            setListenerState("Recovering an interrupted Tailscale restart");
            RecoveryCoordinator.Action action = recovery.onRestored();
            if (action == RecoveryCoordinator.Action.SEND_IMMEDIATE_CONNECT) {
                persistRecoveryMarker();
                sendDirectConnect("Recovering an interrupted restart");
            }
            scheduleRecoveryDeadline();
        } else {
            setListenerState("Listening for a validated physical network");
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            preferences.edit().putBoolean(KEY_ENABLED, false).apply();
            postToHandler(this::handleStopRequest);
            return START_NOT_STICKY;
        }

        if (intent == null && !preferences.getBoolean(KEY_ENABLED, false)) {
            stopSelf();
            return START_NOT_STICKY;
        }

        if (intent != null) {
            preferences.edit().putBoolean(KEY_ENABLED, true).apply();
        }
        if (ACTION_RESTART_NOW.equals(action)) {
            postToHandler(() -> applyDecision(
                    handover.onManualRestart(System.currentTimeMillis())
            ));
        } else if (ACTION_REQUEST_SHIZUKU_PERMISSION.equals(action)) {
            postToHandler(this::requestShizukuPermission);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        unregisterNetworkCallbacks();
        unregisterShizukuListeners();
        if (preferences.getBoolean(KEY_RECOVERY_PENDING, false)) {
            sendTailscaleBroadcast();
        }
        closeUserService();
        if (handler != null) {
            handler.removeCallbacksAndMessages(null);
        }
        if (callbackThread != null) {
            callbackThread.quitSafely();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void registerNetworkCallbacks() {
        physicalCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
                long handle = network.getNetworkHandle();
                String signature = physicalSignature(network, capabilities);
                String previousSignature = physicalSignaturesByHandle.put(handle, signature);
                if (!isValidatedPhysical(capabilities)) {
                    applyDecision(handover.onInvalidatedOrLost(
                            previousSignature == null ? signature : previousSignature
                    ));
                    return;
                }
                applyDecision(handover.onValidated(
                        signature,
                        physicalLabel(capabilities),
                        System.currentTimeMillis()
                ));
            }

            @Override
            public void onLost(Network network) {
                String signature = physicalSignaturesByHandle.remove(network.getNetworkHandle());
                if (signature != null) {
                    applyDecision(handover.onInvalidatedOrLost(signature));
                }
            }
        };

        vpnCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                long handle = network.getNetworkHandle();
                vpnHandles.add(handle);
                if (recovery == null) {
                    return;
                }
                if (!recovery.getOldVpnHandles().contains(handle)) {
                    replacementObserved = true;
                    replacementHandle = handle;
                    if (privilegedCallFinished || !handover.isRestartActive()) {
                        confirmReplacementAndFinish();
                    } else {
                        setListenerState("VPN replacement observed; finishing transaction");
                    }
                }
            }

            @Override
            public void onLost(Network network) {
                vpnHandles.remove(network.getNetworkHandle());
            }
        };

        NetworkRequest physicalRequest = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build();
        NetworkRequest vpnRequest = new NetworkRequest.Builder()
                .clearCapabilities()
                .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                .build();

        try {
            connectivityManager.registerBestMatchingNetworkCallback(
                    physicalRequest,
                    physicalCallback,
                    handler
            );
            physicalCallbackRegistered = true;
            connectivityManager.registerNetworkCallback(vpnRequest, vpnCallback, handler);
            vpnCallbackRegistered = true;
        } catch (RuntimeException exception) {
            Log.e(TAG, "Network callback registration failed", exception);
            setListenerState("Error: network callback registration failed");
            unregisterNetworkCallbacks();
        }
    }

    private void unregisterNetworkCallbacks() {
        if (connectivityManager == null) {
            return;
        }
        if (physicalCallbackRegistered) {
            try {
                connectivityManager.unregisterNetworkCallback(physicalCallback);
            } catch (RuntimeException ignored) {
                // Already removed by the framework.
            }
            physicalCallbackRegistered = false;
        }
        if (vpnCallbackRegistered) {
            try {
                connectivityManager.unregisterNetworkCallback(vpnCallback);
            } catch (RuntimeException ignored) {
                // Already removed by the framework.
            }
            vpnCallbackRegistered = false;
        }
    }

    private void registerShizukuListeners() {
        Shizuku.addBinderReceivedListenerSticky(shizukuBinderReceivedListener, handler);
        Shizuku.addBinderDeadListener(shizukuBinderDeadListener, handler);
        Shizuku.addRequestPermissionResultListener(permissionResultListener, handler);
        if (!Shizuku.pingBinder()) {
            setShizukuReady(false, "Unavailable");
        }
    }

    private void unregisterShizukuListeners() {
        Shizuku.removeBinderReceivedListener(shizukuBinderReceivedListener);
        Shizuku.removeBinderDeadListener(shizukuBinderDeadListener);
        Shizuku.removeRequestPermissionResultListener(permissionResultListener);
    }

    private void handleShizukuBinderReceived() {
        try {
            if (Shizuku.isPreV11()) {
                setShizukuReady(false, "Unsupported Shizuku version");
                return;
            }
            boolean granted = Shizuku.checkSelfPermission()
                    == PackageManager.PERMISSION_GRANTED;
            setShizukuReady(granted, granted
                    ? describeReadyShizuku()
                    : "Permission required");
            if (!granted && handover.getPendingSignature() != null) {
                requestShizukuPermission();
            }
        } catch (RuntimeException exception) {
            Log.e(TAG, "Could not inspect Shizuku binder", exception);
            setShizukuReady(false, "Unavailable");
        }
    }

    private void handleShizukuBinderDead() {
        setShizukuReady(false, "Unavailable (binder died)");
        handlePrivilegedChannelDied("Shizuku binder died");
    }

    private void setShizukuReady(boolean ready, String description) {
        shizukuReady = ready;
        setTransitionPreference(KEY_SHIZUKU_STATE, description);
        applyDecision(handover.onShizukuReadinessChanged(ready, System.currentTimeMillis()));
    }

    private String describeReadyShizuku() {
        try {
            int uid = Shizuku.getUid();
            return uid == 0 ? "Ready (root)" : "Ready (shell)";
        } catch (RuntimeException ignored) {
            return "Ready";
        }
    }

    private void requestShizukuPermission() {
        if (permissionRequestOutstanding) {
            return;
        }
        try {
            if (!Shizuku.pingBinder()) {
                setShizukuReady(false, "Unavailable");
                return;
            }
            if (Shizuku.isPreV11()) {
                setShizukuReady(false, "Unsupported Shizuku version");
                return;
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                setShizukuReady(true, describeReadyShizuku());
                return;
            }
            if (Shizuku.shouldShowRequestPermissionRationale()) {
                setShizukuReady(false, "Permission denied; grant it in Shizuku");
                return;
            }
            permissionRequestOutstanding = true;
            setTransitionPreference(KEY_SHIZUKU_STATE, "Requesting permission");
            Shizuku.requestPermission(SHIZUKU_PERMISSION_REQUEST);
        } catch (RuntimeException exception) {
            permissionRequestOutstanding = false;
            Log.e(TAG, "Shizuku permission request failed", exception);
            setShizukuReady(false, "Permission request failed");
        }
    }

    private void applyDecision(HandoverStateMachine.Decision decision) {
        if (destroyed || decision == null) {
            return;
        }

        switch (decision.kind) {
            case NONE -> {
                // Capability callbacks with no state transition must remain side-effect free.
            }
            case SCHEDULE_DEBOUNCE -> {
                handler.removeCallbacks(debounceRunnable);
                long delay = Math.max(0L, decision.atMillis - System.currentTimeMillis());
                handler.postDelayed(debounceRunnable, delay);
            }
            case CANCEL_DEBOUNCE -> handler.removeCallbacks(debounceRunnable);
            case BASELINE_ESTABLISHED -> {
                persistBaseline();
                syncHandoverPreferences();
                setListenerState("Watching " + decision.label + " (initial baseline)");
            }
            case NETWORK_COMMITTED_DURING_RESTART -> {
                persistBaseline();
                syncHandoverPreferences();
                setListenerState("Restart active; final network change coalesced");
            }
            case RESTART_PENDING -> {
                persistBaseline();
                syncHandoverPreferences();
                setListenerState("Restart pending until Shizuku is ready");
                if (Shizuku.pingBinder()) {
                    requestShizukuPermission();
                }
            }
            case PENDING_EXPIRED -> {
                syncHandoverPreferences();
                setLastResult("Warning: pending restart expired after five minutes");
                setListenerState("Listening; expired handover was not restarted");
            }
            case RESTART_NOW -> {
                persistBaseline();
                syncHandoverPreferences();
                beginPrivilegedRestart(decision);
            }
        }
    }

    private void beginPrivilegedRestart(HandoverStateMachine.Decision decision) {
        if (recovery != null && recovery.isPending()) {
            setLastResult("Warning: recovery is already pending; restart not repeated");
            applyDecision(handover.onRestartTerminal(false, System.currentTimeMillis()));
            return;
        }
        if (!verifyTailscaleConnectReceiver()) {
            setLastResult("CONNECT_FAILED: exported Tailscale connect receiver is unavailable");
            setListenerState("Listening; Tailscale receiver check failed safely");
            applyDecision(handover.onRestartTerminal(false, System.currentTimeMillis()));
            return;
        }
        if (!shizukuReady || !Shizuku.pingBinder()) {
            if (shizukuReady) {
                setShizukuReady(false, "Unavailable during restart");
            }
            applyDecision(handover.onRestartCouldNotStart(System.currentTimeMillis()));
            return;
        }

        activeRestartSignature = decision.signature;
        activeRestartLabel = decision.label;
        activeRestartReason = decision.reason;
        privilegedCallFinished = false;
        replacementObserved = false;
        replacementHandle = -1L;
        userServiceBinding = true;
        userServiceArgs = new Shizuku.UserServiceArgs(
                new ComponentName(this, TailscaleCycleUserService.class)
        )
                .daemon(false)
                .tag("tailscale-cycle-v4")
                .version(4)
                .debuggable(BuildConfig.DEBUG)
                .processNameSuffix("cycle");
        userServiceConnection = createUserServiceConnection();

        setListenerState("Binding temporary Shizuku service");
        try {
            Shizuku.bindUserService(userServiceArgs, userServiceConnection);
        } catch (RuntimeException exception) {
            Log.e(TAG, "Could not bind Shizuku UserService", exception);
            userServiceBinding = false;
            setShizukuReady(false, "Unavailable during bind");
            applyDecision(handover.onRestartCouldNotStart(System.currentTimeMillis()));
        }
    }

    private void handleUserServiceConnected(IBinder binder) {
        if (destroyed || !userServiceBinding || !handover.isRestartActive()) {
            return;
        }
        userServiceBinder = binder;
        try {
            binder.linkToDeath(userServiceDeathRecipient, 0);
        } catch (RemoteException exception) {
            handlePrivilegedChannelDied("UserService died before transaction");
            return;
        }

        long now = System.currentTimeMillis();
        refreshVpnSnapshot();
        recovery = RecoveryCoordinator.start(vpnHandles, now);
        if (!persistRecoveryMarker()) {
            Log.e(TAG, "Recovery marker could not be persisted; restart aborted");
            clearRecoveryMarker();
            closeUserService();
            finishRestart(
                    false,
                    "INTERRUPTED: recovery marker could not be persisted; restart aborted"
            );
            return;
        }
        setListenerState("Restarting Tailscale through temporary Shizuku service");
        scheduleRecoveryDeadline();

        ITailscaleCycleService cycleService = ITailscaleCycleService.Stub.asInterface(binder);
        Thread transactionThread = new Thread(() -> {
            CycleResult result;
            try {
                result = cycleService.cycleTailscale();
                if (result == null) {
                    result = interruptedResult("UserService returned no result");
                }
            } catch (RemoteException | RuntimeException exception) {
                result = interruptedResult(
                        "UserService call failed: " + exception.getClass().getSimpleName()
                );
            }
            CycleResult finalResult = result;
            postToHandler(() -> handlePrivilegedResult(finalResult));
        }, "tailscale-cycle-call");
        transactionThread.start();
    }

    private void handlePrivilegedResult(CycleResult result) {
        if (destroyed || !handover.isRestartActive()) {
            closeUserService();
            return;
        }
        privilegedCallFinished = true;
        setLastResult(result.getCodeName() + ": " + result.getDetail());
        closeUserService();

        if (!result.wasForceStopAttempted()) {
            clearRecoveryMarker();
            finishRestart(
                    result.getCode() == CycleResult.SUCCESS,
                    result.getCodeName() + ": " + result.getDetail()
            );
            return;
        }

        if (replacementObserved) {
            confirmReplacementAndFinish();
            return;
        }

        if (result.getCode() != CycleResult.SUCCESS) {
            RecoveryCoordinator.Action action = recovery.onPrivilegedChannelDied();
            if (action == RecoveryCoordinator.Action.SEND_IMMEDIATE_CONNECT) {
                persistRecoveryMarker();
                sendDirectConnect("Privileged result was " + result.getCodeName());
            }
        }
        setListenerState("Waiting for a replacement Tailscale VPN");
        scheduleRecoveryDeadline();
    }

    private void handlePrivilegedChannelDied(String reason) {
        if (destroyed) {
            return;
        }
        userServiceBinding = false;
        closeUserService();
        if (recovery != null && recovery.isPending()) {
            setLastResult("INTERRUPTED: " + reason);
            RecoveryCoordinator.Action action = recovery.onPrivilegedChannelDied();
            if (action == RecoveryCoordinator.Action.SEND_IMMEDIATE_CONNECT) {
                persistRecoveryMarker();
                sendDirectConnect(reason);
            }
            privilegedCallFinished = true;
            scheduleRecoveryDeadline();
            return;
        }
        if (handover.isRestartActive()) {
            if (shizukuReady) {
                setLastResult("INTERRUPTED: " + reason);
                applyDecision(handover.onRestartTerminal(false, System.currentTimeMillis()));
            } else {
                applyDecision(handover.onRestartCouldNotStart(System.currentTimeMillis()));
            }
        }
    }

    private void handleRecoveryDeadline() {
        if (recovery == null || !recovery.isPending()) {
            return;
        }
        RecoveryCoordinator.Action action = recovery.onDeadline(System.currentTimeMillis());
        if (action == RecoveryCoordinator.Action.NONE) {
            scheduleRecoveryDeadline();
        } else if (action == RecoveryCoordinator.Action.SEND_RETRY) {
            if (!persistRecoveryMarker()) {
                Log.e(TAG, "Could not persist reconnect retry state");
            }
            sendDirectConnect("One reconnect retry after 15 seconds");
            setListenerState("Reconnect retry sent; waiting once more");
            scheduleRecoveryDeadline();
        } else if (action == RecoveryCoordinator.Action.REPORT_WARNING) {
            setLastResult("Warning: no replacement VPN after reconnect retry");
            setListenerState("Warning: Tailscale VPN recovery remains pending");
            finishRestart(false, "No replacement VPN appeared within two 15-second windows");
        }
    }

    private void finishRestart(boolean success, String detail) {
        if (!handover.isRestartActive()) {
            if (success) {
                setLastResult("SUCCESS: " + detail);
                setListenerState("Watching " + safePhysicalLabel());
                if (stopAfterTransaction) {
                    stopAfterTransaction = false;
                    stopSelf();
                }
            }
            return;
        }
        handler.removeCallbacks(recoveryDeadlineRunnable);
        closeUserService();
        privilegedCallFinished = false;
        replacementObserved = false;
        replacementHandle = -1L;
        activeRestartSignature = null;
        activeRestartLabel = null;
        activeRestartReason = null;

        HandoverStateMachine.Decision followUp = handover.onRestartTerminal(
                success,
                System.currentTimeMillis()
        );
        syncHandoverPreferences();
        if (success) {
            setLastResult("SUCCESS: " + detail);
            setListenerState("Watching " + safePhysicalLabel());
        } else {
            setLastResult("Warning: " + detail);
        }

        if (stopAfterTransaction && (recovery == null || !recovery.isPending())) {
            stopAfterTransaction = false;
            stopSelf();
            return;
        }
        applyDecision(followUp);
    }

    private void handleStopRequest() {
        if (handover.isRestartActive() || (recovery != null && recovery.isPending())) {
            stopAfterTransaction = true;
            setListenerState("Stop requested; finishing Tailscale recovery first");
            if (recovery != null && recovery.isPending()) {
                sendDirectConnect("Stop requested during recovery");
            }
            return;
        }
        setListenerState("Stopped by user");
        stopSelf();
    }

    private boolean verifyTailscaleConnectReceiver() {
        ComponentName component = new ComponentName(TAILSCALE_PACKAGE, TAILSCALE_RECEIVER);
        PackageManager packageManager = getPackageManager();
        try {
            ActivityInfo receiver = packageManager.getReceiverInfo(
                    component,
                    PackageManager.MATCH_DISABLED_COMPONENTS
            );
            ApplicationInfo application = receiver.applicationInfo;
            int componentState = packageManager.getComponentEnabledSetting(component);
            boolean explicitlyDisabled = componentState
                    == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    || componentState == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                    || componentState
                    == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED;
            return receiver.exported
                    && receiver.enabled
                    && application != null
                    && application.enabled
                    && !explicitlyDisabled;
        } catch (PackageManager.NameNotFoundException | RuntimeException exception) {
            Log.e(TAG, "Tailscale connect receiver is unavailable", exception);
            return false;
        }
    }

    private void sendDirectConnect(String reason) {
        boolean sent = verifyTailscaleConnectReceiver() && sendTailscaleBroadcast();
        Log.i(TAG, (sent ? "Sent" : "Could not send")
                + " direct CONNECT_VPN fallback: " + reason);
    }

    private boolean sendTailscaleBroadcast() {
        Intent intent = new Intent(TAILSCALE_CONNECT)
                .setComponent(new ComponentName(TAILSCALE_PACKAGE, TAILSCALE_RECEIVER))
                .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES
                        | Intent.FLAG_RECEIVER_FOREGROUND);
        try {
            sendBroadcast(intent);
            return true;
        } catch (RuntimeException exception) {
            Log.e(TAG, "Direct CONNECT_VPN broadcast failed", exception);
            return false;
        }
    }

    private void closeUserService() {
        IBinder binder = userServiceBinder;
        if (binder != null) {
            binder.unlinkToDeath(userServiceDeathRecipient, 0);
        }
        userServiceBinder = null;
        Shizuku.UserServiceArgs args = userServiceArgs;
        ServiceConnection connection = userServiceConnection;
        userServiceArgs = null;
        userServiceConnection = null;
        boolean removed = false;
        if (args != null && connection != null) {
            try {
                if (Shizuku.pingBinder()) {
                    Shizuku.unbindUserService(args, connection, true);
                    removed = true;
                }
            } catch (RuntimeException exception) {
                Log.e(TAG, "Could not remove temporary UserService", exception);
            }
        }
        if (!removed && binder != null && binder.pingBinder()) {
            try {
                ITailscaleCycleService.Stub.asInterface(binder).destroy();
            } catch (RemoteException | RuntimeException exception) {
                Log.e(TAG, "Could not terminate temporary UserService directly", exception);
            }
        }
        userServiceBinding = false;
    }

    private ServiceConnection createUserServiceConnection() {
        return new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                postToHandler(() -> {
                    if (userServiceConnection == this) {
                        handleUserServiceConnected(service);
                    }
                });
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                postToHandler(() -> {
                    if (userServiceConnection == this) {
                        handlePrivilegedChannelDied("UserService disconnected");
                    }
                });
            }
        };
    }

    private void restoreRecoveryMarker() {
        if (!preferences.getBoolean(KEY_RECOVERY_PENDING, false)) {
            return;
        }
        long startedAt = preferences.getLong(
                KEY_RECOVERY_STARTED_AT,
                System.currentTimeMillis()
        );
        recovery = new RecoveryCoordinator(
                true,
                decodeHandles(preferences.getString(KEY_RECOVERY_OLD_VPNS, "")),
                startedAt,
                preferences.getLong(KEY_RECOVERY_RETRY_SENT_AT, 0L),
                preferences.getBoolean(KEY_RECOVERY_IMMEDIATE_SENT, false)
        );
        activeRestartSignature = preferences.getString(
                KEY_RECOVERY_SIGNATURE,
                preferences.getString(KEY_PHYSICAL_SIGNATURE, "@recovery")
        );
        activeRestartLabel = preferences.getString(
                KEY_RECOVERY_LABEL,
                preferences.getString(KEY_PHYSICAL_LABEL, "physical network")
        );
        activeRestartReason = preferences.getString(
                KEY_RECOVERY_REASON,
                "Interrupted restart recovery"
        );
        privilegedCallFinished = true;
        handover.restoreActiveRestart(
                activeRestartSignature,
                activeRestartLabel,
                activeRestartReason,
                startedAt
        );
        setLastResult("INTERRUPTED: recovery marker restored");
    }

    @SuppressLint("ApplySharedPref") // The marker must reach disk before force-stop is invoked.
    private boolean persistRecoveryMarker() {
        if (recovery == null || !recovery.isPending()) {
            return false;
        }
        return preferences.edit()
                .putBoolean(KEY_RECOVERY_PENDING, true)
                .putString(KEY_RECOVERY_SIGNATURE, activeRestartSignature)
                .putString(KEY_RECOVERY_LABEL, activeRestartLabel)
                .putString(KEY_RECOVERY_REASON, activeRestartReason)
                .putLong(KEY_RECOVERY_STARTED_AT, recovery.getStartedAtMillis())
                .putLong(KEY_RECOVERY_RETRY_SENT_AT, recovery.getRetrySentAtMillis())
                .putBoolean(KEY_RECOVERY_IMMEDIATE_SENT,
                        recovery.wasImmediateConnectIssued())
                .putString(KEY_RECOVERY_OLD_VPNS, encodeHandles(recovery.getOldVpnHandles()))
                .commit();
    }

    @SuppressLint("ApplySharedPref") // Durably clear only after the replacement callback.
    private void clearRecoveryMarker() {
        preferences.edit()
                .putBoolean(KEY_RECOVERY_PENDING, false)
                .remove(KEY_RECOVERY_SIGNATURE)
                .remove(KEY_RECOVERY_LABEL)
                .remove(KEY_RECOVERY_REASON)
                .remove(KEY_RECOVERY_STARTED_AT)
                .remove(KEY_RECOVERY_RETRY_SENT_AT)
                .remove(KEY_RECOVERY_IMMEDIATE_SENT)
                .remove(KEY_RECOVERY_OLD_VPNS)
                .commit();
        recovery = null;
    }

    private void scheduleRecoveryDeadline() {
        if (recovery == null || !recovery.isPending()) {
            return;
        }
        handler.removeCallbacks(recoveryDeadlineRunnable);
        long delay = Math.max(0L, recovery.nextDeadlineMillis() - System.currentTimeMillis());
        handler.postDelayed(recoveryDeadlineRunnable, delay);
    }

    private void confirmReplacementAndFinish() {
        if (recovery == null || replacementHandle < 0L) {
            return;
        }
        RecoveryCoordinator.Action action = recovery.onVpnAvailable(replacementHandle);
        if (action != RecoveryCoordinator.Action.CLEAR_MARKER) {
            return;
        }
        clearRecoveryMarker();
        handler.removeCallbacks(recoveryDeadlineRunnable);
        finishRestart(true, "Tailscale VPN replacement observed");
    }

    private void refreshVpnSnapshot() {
        vpnHandles.clear();
        try {
            for (Network network : connectivityManager.getAllNetworks()) {
                NetworkCapabilities capabilities =
                        connectivityManager.getNetworkCapabilities(network);
                if (capabilities != null
                        && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                    vpnHandles.add(network.getNetworkHandle());
                }
            }
        } catch (RuntimeException exception) {
            Log.e(TAG, "Could not snapshot current VPN handles", exception);
        }
    }

    @SuppressLint("ApplySharedPref") // Stable handovers must survive immediate process recreation.
    private void persistBaseline() {
        SharedPreferences.Editor editor = preferences.edit();
        if (handover.getCurrentSignature() == null) {
            editor.remove(KEY_PHYSICAL_SIGNATURE).remove(KEY_PHYSICAL_LABEL);
        } else {
            editor.putString(KEY_PHYSICAL_SIGNATURE, handover.getCurrentSignature())
                    .putString(KEY_PHYSICAL_LABEL, handover.getCurrentLabel());
        }
        editor.commit();
        refreshNotification();
    }

    @SuppressLint("ApplySharedPref") // Pending expiry is part of the durable event state.
    private void syncHandoverPreferences() {
        SharedPreferences.Editor editor = preferences.edit();
        if (handover.getPendingSignature() == null) {
            editor.remove(KEY_PENDING_SIGNATURE)
                    .remove(KEY_PENDING_LABEL)
                    .remove(KEY_PENDING_REASON)
                    .remove(KEY_PENDING_REQUESTED_AT)
                    .remove(KEY_PENDING_EXPIRY)
                    .remove(KEY_PENDING_FOLLOW_UP)
                    .remove(KEY_PENDING_MANUAL);
        } else {
            editor.putString(KEY_PENDING_SIGNATURE, handover.getPendingSignature())
                    .putString(KEY_PENDING_LABEL, handover.getPendingLabel())
                    .putString(KEY_PENDING_REASON, handover.getPendingReason())
                    .putLong(KEY_PENDING_REQUESTED_AT,
                            handover.getPendingRequestedAtMillis())
                    .putLong(KEY_PENDING_EXPIRY, handover.getPendingExpiresAtMillis())
                    .putBoolean(KEY_PENDING_FOLLOW_UP, handover.isPendingFollowUp())
                    .putBoolean(KEY_PENDING_MANUAL, handover.isPendingManual());
        }
        editor.commit();
        refreshNotification();
    }

    private void setListenerState(String state) {
        setTransitionPreference(KEY_LISTENER_STATE, state);
    }

    private void setLastResult(String result) {
        setTransitionPreference(KEY_LAST_RESULT, result);
    }

    private void setTransitionPreference(String key, String value) {
        String previous = preferences.getString(key, null);
        if (value.equals(previous)) {
            return;
        }
        preferences.edit()
                .putString(key, value)
                .putLong(KEY_STATUS_TIME, System.currentTimeMillis())
                .apply();
        Log.i(TAG, key + ": " + value);
        refreshNotification();
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Tailscale network watcher",
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription("Keeps Android network callbacks active without polling");
        channel.setShowBadge(false);
        notificationManager.createNotificationChannel(channel);
    }

    private Notification buildNotification() {
        Intent activityIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                activityIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        String listener = preferences == null
                ? "Starting event listener"
                : preferences.getString(KEY_LISTENER_STATE, "Starting event listener");
        String physical = preferences == null
                ? "No baseline"
                : preferences.getString(KEY_PHYSICAL_LABEL, "No validated baseline");
        String shizuku = preferences == null
                ? "Unknown"
                : preferences.getString(KEY_SHIZUKU_STATE, "Unknown");
        String last = preferences == null
                ? "None"
                : preferences.getString(KEY_LAST_RESULT, "None");
        long pendingExpiry = preferences == null
                ? 0L
                : preferences.getLong(KEY_PENDING_EXPIRY, 0L);
        String pending = pendingExpiry == 0L
                ? "None"
                : DateFormat.getTimeInstance(DateFormat.MEDIUM).format(new Date(pendingExpiry));
        String detail = listener
                + "\nPhysical: " + physical
                + "\nShizuku: " + shizuku
                + "\nPending until: " + pending
                + "\nLast result: " + last;
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
                .setContentTitle("Tailscale Network Watcher")
                .setContentText(listener)
                .setStyle(new Notification.BigTextStyle().bigText(detail))
                .setContentIntent(pendingIntent)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .build();
    }

    private void refreshNotification() {
        if (notificationManager != null && preferences != null) {
            notificationManager.notify(NOTIFICATION_ID, buildNotification());
        }
    }

    private void startForegroundCompat(Notification notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            );
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void postToHandler(Runnable runnable) {
        Handler eventHandler = handler;
        if (eventHandler != null && !destroyed) {
            eventHandler.post(runnable);
        }
    }

    private String safePhysicalLabel() {
        String label = handover.getCurrentLabel();
        return label == null ? "physical network" : label;
    }

    private static boolean isValidatedPhysical(NetworkCapabilities capabilities) {
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                && !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
    }

    private static String physicalSignature(
            Network network,
            NetworkCapabilities capabilities
    ) {
        int[] baseTransports = new int[]{
                NetworkCapabilities.TRANSPORT_CELLULAR,
                NetworkCapabilities.TRANSPORT_WIFI,
                NetworkCapabilities.TRANSPORT_BLUETOOTH,
                NetworkCapabilities.TRANSPORT_ETHERNET,
                NetworkCapabilities.TRANSPORT_WIFI_AWARE,
                NetworkCapabilities.TRANSPORT_LOWPAN,
                NetworkCapabilities.TRANSPORT_USB
        };
        long mask = 0L;
        for (int transport : baseTransports) {
            if (capabilities.hasTransport(transport)) {
                mask |= 1L << transport;
            }
        }
        if (Build.VERSION.SDK_INT >= 34
                && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_THREAD)) {
            mask |= 1L << NetworkCapabilities.TRANSPORT_THREAD;
        }
        if (Build.VERSION.SDK_INT >= 35
                && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_SATELLITE)) {
            mask |= 1L << NetworkCapabilities.TRANSPORT_SATELLITE;
        }
        return Long.toUnsignedString(network.getNetworkHandle())
                + ":"
                + Long.toUnsignedString(mask, 16);
    }

    private static String physicalLabel(NetworkCapabilities capabilities) {
        List<String> labels = new ArrayList<>();
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            labels.add("Ethernet");
        }
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            labels.add("Wi-Fi");
        }
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            labels.add("cellular");
        }
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_USB)) {
            labels.add("USB");
        }
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) {
            labels.add("Bluetooth");
        }
        if (Build.VERSION.SDK_INT >= 35
                && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_SATELLITE)) {
            labels.add("satellite");
        }
        if (Build.VERSION.SDK_INT >= 34
                && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_THREAD)) {
            labels.add("Thread");
        }
        return labels.isEmpty() ? "physical network" : String.join(" + ", labels);
    }

    private static String encodeHandles(Set<Long> handles) {
        List<Long> sorted = new ArrayList<>(handles);
        sorted.sort(Long::compareUnsigned);
        StringBuilder result = new StringBuilder();
        for (Long handle : sorted) {
            if (result.length() > 0) {
                result.append(',');
            }
            result.append(Long.toUnsignedString(handle));
        }
        return result.toString();
    }

    private static Set<Long> decodeHandles(String encoded) {
        Set<Long> result = new HashSet<>();
        if (encoded == null || encoded.isEmpty()) {
            return result;
        }
        for (String value : encoded.split(",")) {
            try {
                result.add(Long.parseUnsignedLong(value));
            } catch (NumberFormatException ignored) {
                // Ignore a corrupt entry but retain the safety marker.
            }
        }
        return result;
    }

    private static CycleResult interruptedResult(String detail) {
        return new CycleResult(CycleResult.INTERRUPTED, detail, true, false);
    }
}
