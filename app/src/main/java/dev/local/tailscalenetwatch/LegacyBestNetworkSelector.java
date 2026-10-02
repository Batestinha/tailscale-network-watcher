package dev.local.tailscalenetwatch;

import java.util.Collection;

/** Deterministic best-network selection for Android 10 and 11 passive callbacks. */
final class LegacyBestNetworkSelector {
    static final class Candidate {
        final long handle;
        final String signature;
        final String label;
        final int priority;

        Candidate(long handle, String signature, String label, int priority) {
            this.handle = handle;
            this.signature = signature;
            this.label = label;
            this.priority = priority;
        }
    }

    private LegacyBestNetworkSelector() { }

    static Candidate select(Collection<Candidate> candidates, Long currentHandle) {
        Candidate best = null;
        for (Candidate candidate : candidates) {
            if (best == null || isBetter(candidate, best, currentHandle)) {
                best = candidate;
            }
        }
        return best;
    }

    private static boolean isBetter(Candidate candidate, Candidate best, Long currentHandle) {
        if (candidate.priority != best.priority) {
            return candidate.priority > best.priority;
        }
        if (currentHandle != null) {
            if (candidate.handle == currentHandle && best.handle != currentHandle) {
                return true;
            }
            if (best.handle == currentHandle && candidate.handle != currentHandle) {
                return false;
            }
        }
        return Long.compareUnsigned(candidate.handle, best.handle) < 0;
    }
}
