package dev.local.tailscalenetwatch;

import java.util.Objects;

/**
 * Pure state for physical-network handovers. Android callbacks and timers are adapters around
 * this class, which keeps the coalescing and restart limits independently unit-testable.
 */
final class HandoverStateMachine {
    static final long HANDOVER_DEBOUNCE_MS = 3_000L;
    static final long SHIZUKU_PENDING_MS = 5 * 60_000L;

    enum Kind {
        NONE,
        SCHEDULE_DEBOUNCE,
        CANCEL_DEBOUNCE,
        BASELINE_ESTABLISHED,
        NETWORK_COMMITTED_DURING_RESTART,
        RESTART_NOW,
        RESTART_PENDING,
        PENDING_EXPIRED
    }

    static final class Decision {
        static final Decision NONE = new Decision(Kind.NONE, null, null, null, 0L, false);

        final Kind kind;
        final String signature;
        final String label;
        final String reason;
        final long atMillis;
        final boolean followUp;

        private Decision(
                Kind kind,
                String signature,
                String label,
                String reason,
                long atMillis,
                boolean followUp
        ) {
            this.kind = kind;
            this.signature = signature;
            this.label = label;
            this.reason = reason;
            this.atMillis = atMillis;
            this.followUp = followUp;
        }

        static Decision of(Kind kind) {
            return new Decision(kind, null, null, null, 0L, false);
        }

        static Decision debounce(String signature, String label, long dueAtMillis) {
            return new Decision(
                    Kind.SCHEDULE_DEBOUNCE,
                    signature,
                    label,
                    null,
                    dueAtMillis,
                    false
            );
        }

        static Decision baseline(String signature, String label) {
            return new Decision(
                    Kind.BASELINE_ESTABLISHED,
                    signature,
                    label,
                    "Initial validated network",
                    0L,
                    false
            );
        }

        static Decision committedDuringRestart(
                String signature,
                String label,
                String reason
        ) {
            return new Decision(
                    Kind.NETWORK_COMMITTED_DURING_RESTART,
                    signature,
                    label,
                    reason,
                    0L,
                    false
            );
        }

        static Decision restart(
                Kind kind,
                String signature,
                String label,
                String reason,
                long atMillis,
                boolean followUp
        ) {
            return new Decision(kind, signature, label, reason, atMillis, followUp);
        }
    }

    private String currentSignature;
    private String currentLabel;
    private String validatedSignature;
    private String validatedLabel;
    private String candidateSignature;
    private String candidateLabel;
    private long candidateDueAtMillis;

    private boolean shizukuReady;

    private String pendingSignature;
    private String pendingLabel;
    private String pendingReason;
    private long pendingRequestedAtMillis;
    private long pendingExpiresAtMillis;
    private boolean pendingFollowUp;
    private boolean pendingManual;

    private String activeSignature;
    private String activeLabel;
    private String activeReason;
    private long activeRequestedAtMillis;
    private boolean activeFollowUp;
    private boolean activeManual;

    private String queuedSignature;
    private String queuedLabel;
    private String queuedReason;
    private long queuedRequestedAtMillis;

    HandoverStateMachine(
            String currentSignature,
            String currentLabel,
            String pendingSignature,
            String pendingLabel,
            String pendingReason,
            long pendingRequestedAtMillis,
            long pendingExpiresAtMillis,
            boolean pendingFollowUp,
            boolean pendingManual
    ) {
        this.currentSignature = emptyToNull(currentSignature);
        this.currentLabel = emptyToNull(currentLabel);
        this.pendingSignature = emptyToNull(pendingSignature);
        this.pendingLabel = emptyToNull(pendingLabel);
        this.pendingReason = emptyToNull(pendingReason);
        this.pendingRequestedAtMillis = pendingRequestedAtMillis;
        this.pendingExpiresAtMillis = pendingExpiresAtMillis;
        this.pendingFollowUp = pendingFollowUp;
        this.pendingManual = pendingManual;

        if (this.pendingSignature == null || pendingExpiresAtMillis <= 0L) {
            clearPending();
        }
    }

    static HandoverStateMachine empty() {
        return new HandoverStateMachine(null, null, null, null, null, 0L, 0L, false, false);
    }

    Decision onValidated(String signature, String label, long nowMillis) {
        Objects.requireNonNull(signature, "signature");
        Objects.requireNonNull(label, "label");

        boolean sameValidated = signature.equals(validatedSignature);
        validatedSignature = signature;
        validatedLabel = label;

        if (signature.equals(currentSignature)) {
            boolean canceled = candidateSignature != null;
            clearCandidate();
            Decision pendingDecision = tryStartPending(nowMillis);
            if (pendingDecision.kind != Kind.NONE) {
                return pendingDecision;
            }
            return canceled ? Decision.of(Kind.CANCEL_DEBOUNCE) : Decision.NONE;
        }

        if (sameValidated && signature.equals(candidateSignature)) {
            return Decision.NONE;
        }

        candidateSignature = signature;
        candidateLabel = label;
        candidateDueAtMillis = nowMillis + HANDOVER_DEBOUNCE_MS;
        return Decision.debounce(signature, label, candidateDueAtMillis);
    }

    Decision onInvalidatedOrLost(String signature) {
        if (!Objects.equals(signature, validatedSignature)) {
            return Decision.NONE;
        }

        validatedSignature = null;
        validatedLabel = null;
        if (Objects.equals(signature, candidateSignature)) {
            clearCandidate();
            return Decision.of(Kind.CANCEL_DEBOUNCE);
        }
        return Decision.NONE;
    }

    Decision onDebounce(long nowMillis) {
        if (candidateSignature == null) {
            return Decision.NONE;
        }
        if (nowMillis < candidateDueAtMillis) {
            return Decision.debounce(candidateSignature, candidateLabel, candidateDueAtMillis);
        }
        if (!candidateSignature.equals(validatedSignature)) {
            clearCandidate();
            return Decision.of(Kind.CANCEL_DEBOUNCE);
        }

        String nextSignature = candidateSignature;
        String nextLabel = candidateLabel;
        String previousLabel = currentLabel == null ? "physical network" : currentLabel;
        clearCandidate();

        if (nextSignature.equals(currentSignature)) {
            return Decision.NONE;
        }

        boolean initialBaseline = currentSignature == null;
        currentSignature = nextSignature;
        currentLabel = nextLabel;
        if (initialBaseline) {
            return Decision.baseline(nextSignature, nextLabel);
        }

        String reason = "Network changed: " + previousLabel + " to " + nextLabel;
        if (activeSignature != null) {
            if (nextSignature.equals(activeSignature)) {
                clearQueued();
            } else {
                queuedSignature = nextSignature;
                queuedLabel = nextLabel;
                queuedReason = reason;
                queuedRequestedAtMillis = nowMillis;
            }
            return Decision.committedDuringRestart(nextSignature, nextLabel, reason);
        }

        return requestRestart(
                nextSignature,
                nextLabel,
                reason,
                nowMillis,
                nowMillis,
                false,
                false
        );
    }

    Decision onManualRestart(long nowMillis) {
        if (activeSignature != null) {
            return Decision.NONE;
        }
        String signature = currentSignature == null ? "@manual" : currentSignature;
        String label = currentLabel == null ? "physical network" : currentLabel;
        return requestRestart(
                signature,
                label,
                "Manual restart",
                nowMillis,
                nowMillis,
                false,
                true
        );
    }

    Decision onShizukuReadinessChanged(boolean ready, long nowMillis) {
        shizukuReady = ready;
        if (!ready) {
            return Decision.NONE;
        }
        return tryStartPending(nowMillis);
    }

    Decision onRestartCouldNotStart(long nowMillis) {
        if (activeSignature == null) {
            return Decision.NONE;
        }

        String signature = activeSignature;
        String label = activeLabel;
        String reason = activeReason;
        long requestedAt = activeRequestedAtMillis;
        boolean followUp = activeFollowUp;
        boolean manual = activeManual;
        clearActive();
        shizukuReady = false;

        long expiresAt = requestedAt + SHIZUKU_PENDING_MS;
        if (nowMillis >= expiresAt) {
            clearPending();
            return Decision.restart(
                    Kind.PENDING_EXPIRED,
                    signature,
                    label,
                    reason,
                    expiresAt,
                    followUp
            );
        }

        setPending(signature, label, reason, requestedAt, expiresAt, followUp, manual);
        return Decision.restart(
                Kind.RESTART_PENDING,
                signature,
                label,
                reason,
                expiresAt,
                followUp
        );
    }

    Decision onRestartTerminal(boolean success, long nowMillis) {
        if (activeSignature == null) {
            return Decision.NONE;
        }

        String completedSignature = activeSignature;
        boolean completedFollowUp = activeFollowUp;
        clearActive();

        if (success
                && !completedFollowUp
                && queuedSignature != null
                && !queuedSignature.equals(completedSignature)) {
            String signature = queuedSignature;
            String label = queuedLabel;
            String reason = queuedReason;
            long requestedAt = queuedRequestedAtMillis;
            clearQueued();
            return requestRestart(
                    signature,
                    label,
                    reason,
                    requestedAt,
                    nowMillis,
                    true,
                    false
            );
        }

        clearQueued();
        return Decision.NONE;
    }

    void restoreActiveRestart(String signature, String label, String reason, long requestedAtMillis) {
        if (signature == null || activeSignature != null) {
            return;
        }
        clearPending();
        activeSignature = signature;
        activeLabel = label;
        activeReason = reason == null ? "Interrupted restart recovery" : reason;
        activeRequestedAtMillis = requestedAtMillis;
        activeFollowUp = false;
        activeManual = signature.startsWith("@manual");
    }

    private Decision requestRestart(
            String signature,
            String label,
            String reason,
            long requestedAtMillis,
            long nowMillis,
            boolean followUp,
            boolean manual
    ) {
        clearPending();
        if (shizukuReady) {
            activeSignature = signature;
            activeLabel = label;
            activeReason = reason;
            activeRequestedAtMillis = requestedAtMillis;
            activeFollowUp = followUp;
            activeManual = manual;
            return Decision.restart(
                    Kind.RESTART_NOW,
                    signature,
                    label,
                    reason,
                    requestedAtMillis,
                    followUp
            );
        }

        long expiresAtMillis = requestedAtMillis + SHIZUKU_PENDING_MS;
        if (nowMillis >= expiresAtMillis) {
            return Decision.restart(
                    Kind.PENDING_EXPIRED,
                    signature,
                    label,
                    reason,
                    expiresAtMillis,
                    followUp
            );
        }
        setPending(
                signature,
                label,
                reason,
                requestedAtMillis,
                expiresAtMillis,
                followUp,
                manual
        );
        return Decision.restart(
                Kind.RESTART_PENDING,
                signature,
                label,
                reason,
                expiresAtMillis,
                followUp
        );
    }

    private Decision tryStartPending(long nowMillis) {
        if (pendingSignature == null) {
            return Decision.NONE;
        }
        if (nowMillis >= pendingExpiresAtMillis) {
            String signature = pendingSignature;
            String label = pendingLabel;
            String reason = pendingReason;
            long expiredAt = pendingExpiresAtMillis;
            boolean followUp = pendingFollowUp;
            clearPending();
            return Decision.restart(
                    Kind.PENDING_EXPIRED,
                    signature,
                    label,
                    reason,
                    expiredAt,
                    followUp
            );
        }
        if (!shizukuReady) {
            return Decision.NONE;
        }
        if (!pendingManual
                && (!pendingSignature.equals(currentSignature)
                || !pendingSignature.equals(validatedSignature))) {
            return Decision.NONE;
        }

        String signature = pendingSignature;
        String label = pendingLabel;
        String reason = pendingReason;
        long requestedAt = pendingRequestedAtMillis;
        boolean followUp = pendingFollowUp;
        boolean manual = pendingManual;
        clearPending();

        activeSignature = signature;
        activeLabel = label;
        activeReason = reason;
        activeRequestedAtMillis = requestedAt;
        activeFollowUp = followUp;
        activeManual = manual;
        return Decision.restart(
                Kind.RESTART_NOW,
                signature,
                label,
                reason,
                requestedAt,
                followUp
        );
    }

    private void setPending(
            String signature,
            String label,
            String reason,
            long requestedAtMillis,
            long expiresAtMillis,
            boolean followUp,
            boolean manual
    ) {
        pendingSignature = signature;
        pendingLabel = label;
        pendingReason = reason;
        pendingRequestedAtMillis = requestedAtMillis;
        pendingExpiresAtMillis = expiresAtMillis;
        pendingFollowUp = followUp;
        pendingManual = manual;
    }

    private void clearCandidate() {
        candidateSignature = null;
        candidateLabel = null;
        candidateDueAtMillis = 0L;
    }

    private void clearPending() {
        pendingSignature = null;
        pendingLabel = null;
        pendingReason = null;
        pendingRequestedAtMillis = 0L;
        pendingExpiresAtMillis = 0L;
        pendingFollowUp = false;
        pendingManual = false;
    }

    private void clearActive() {
        activeSignature = null;
        activeLabel = null;
        activeReason = null;
        activeRequestedAtMillis = 0L;
        activeFollowUp = false;
        activeManual = false;
    }

    private void clearQueued() {
        queuedSignature = null;
        queuedLabel = null;
        queuedReason = null;
        queuedRequestedAtMillis = 0L;
    }

    String getCurrentSignature() {
        return currentSignature;
    }

    String getCurrentLabel() {
        return currentLabel;
    }

    String getValidatedSignature() {
        return validatedSignature;
    }

    String getPendingSignature() {
        return pendingSignature;
    }

    String getPendingLabel() {
        return pendingLabel;
    }

    String getPendingReason() {
        return pendingReason;
    }

    long getPendingRequestedAtMillis() {
        return pendingRequestedAtMillis;
    }

    long getPendingExpiresAtMillis() {
        return pendingExpiresAtMillis;
    }

    boolean isPendingFollowUp() {
        return pendingFollowUp;
    }

    boolean isPendingManual() {
        return pendingManual;
    }

    boolean isRestartActive() {
        return activeSignature != null;
    }

    String getActiveSignature() {
        return activeSignature;
    }

    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }
}
