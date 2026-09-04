package dev.local.tailscalenetwatch;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** One-shot recovery policy for the window between force-stop and replacement VPN callback. */
final class RecoveryCoordinator {
    static final long VPN_REPLACEMENT_TIMEOUT_MS = 15_000L;

    enum Action {
        NONE,
        CLEAR_MARKER,
        SEND_IMMEDIATE_CONNECT,
        SEND_RETRY,
        REPORT_WARNING
    }

    private boolean pending;
    private final Set<Long> oldVpnHandles;
    private final long startedAtMillis;
    private long retrySentAtMillis;
    private boolean immediateConnectIssued;
    private boolean warningReported;

    RecoveryCoordinator(
            boolean pending,
            Set<Long> oldVpnHandles,
            long startedAtMillis,
            long retrySentAtMillis,
            boolean immediateConnectIssued
    ) {
        this.pending = pending;
        this.oldVpnHandles = oldVpnHandles == null
                ? new HashSet<>()
                : new HashSet<>(oldVpnHandles);
        this.startedAtMillis = startedAtMillis;
        this.retrySentAtMillis = retrySentAtMillis;
        this.immediateConnectIssued = immediateConnectIssued;
    }

    static RecoveryCoordinator start(Set<Long> oldVpnHandles, long nowMillis) {
        return new RecoveryCoordinator(true, oldVpnHandles, nowMillis, 0L, false);
    }

    Action onVpnAvailable(long handle) {
        if (!pending || oldVpnHandles.contains(handle)) {
            return Action.NONE;
        }
        pending = false;
        return Action.CLEAR_MARKER;
    }

    Action onPrivilegedChannelDied() {
        return requestImmediateConnect();
    }

    Action onRestored() {
        return requestImmediateConnect();
    }

    Action onDeadline(long nowMillis) {
        if (!pending || warningReported || nowMillis < nextDeadlineMillis()) {
            return Action.NONE;
        }
        if (retrySentAtMillis == 0L) {
            retrySentAtMillis = nowMillis;
            return Action.SEND_RETRY;
        }
        warningReported = true;
        return Action.REPORT_WARNING;
    }

    long nextDeadlineMillis() {
        return (retrySentAtMillis == 0L ? startedAtMillis : retrySentAtMillis)
                + VPN_REPLACEMENT_TIMEOUT_MS;
    }

    boolean isPending() {
        return pending;
    }

    long getStartedAtMillis() {
        return startedAtMillis;
    }

    long getRetrySentAtMillis() {
        return retrySentAtMillis;
    }

    boolean wasImmediateConnectIssued() {
        return immediateConnectIssued;
    }

    Set<Long> getOldVpnHandles() {
        return Collections.unmodifiableSet(oldVpnHandles);
    }

    private Action requestImmediateConnect() {
        if (!pending || immediateConnectIssued) {
            return Action.NONE;
        }
        immediateConnectIssued = true;
        return Action.SEND_IMMEDIATE_CONNECT;
    }
}
