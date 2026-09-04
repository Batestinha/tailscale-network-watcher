package dev.local.tailscalenetwatch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Set;

public final class RecoveryCoordinatorTest {
    @Test
    public void binderDeathSendsImmediateFallbackWithoutConsumingRetry() {
        RecoveryCoordinator recovery = RecoveryCoordinator.start(Set.of(10L), 1_000L);

        assertEquals(
                RecoveryCoordinator.Action.SEND_IMMEDIATE_CONNECT,
                recovery.onPrivilegedChannelDied()
        );
        assertEquals(
                RecoveryCoordinator.Action.NONE,
                recovery.onPrivilegedChannelDied()
        );
        assertEquals(0L, recovery.getRetrySentAtMillis());
        assertEquals(
                RecoveryCoordinator.Action.SEND_RETRY,
                recovery.onDeadline(16_000L)
        );
    }

    @Test
    public void restoredMarkerOnlyClearsForReplacementVpnHandle() {
        RecoveryCoordinator restored = new RecoveryCoordinator(
                true,
                Set.of(10L, 11L),
                1_000L,
                0L,
                false
        );

        assertEquals(RecoveryCoordinator.Action.SEND_IMMEDIATE_CONNECT, restored.onRestored());
        assertEquals(RecoveryCoordinator.Action.NONE, restored.onVpnAvailable(10L));
        assertTrue(restored.isPending());
        assertEquals(RecoveryCoordinator.Action.CLEAR_MARKER, restored.onVpnAvailable(12L));
        assertFalse(restored.isPending());
    }

    @Test
    public void restoredMarkerDoesNotRepeatAPersistedImmediateFallback() {
        RecoveryCoordinator restored = new RecoveryCoordinator(
                true,
                Set.of(10L),
                1_000L,
                0L,
                true
        );

        assertEquals(RecoveryCoordinator.Action.NONE, restored.onRestored());
        assertTrue(restored.wasImmediateConnectIssued());
    }

    @Test
    public void oneRetryThenOneWarningAndNoLoop() {
        RecoveryCoordinator recovery = RecoveryCoordinator.start(Set.of(), 100L);

        assertEquals(RecoveryCoordinator.Action.NONE, recovery.onDeadline(15_099L));
        assertEquals(RecoveryCoordinator.Action.SEND_RETRY, recovery.onDeadline(15_100L));
        assertEquals(15_100L, recovery.getRetrySentAtMillis());
        assertEquals(RecoveryCoordinator.Action.NONE, recovery.onDeadline(30_099L));
        assertEquals(RecoveryCoordinator.Action.REPORT_WARNING,
                recovery.onDeadline(30_100L));
        assertEquals(RecoveryCoordinator.Action.NONE, recovery.onDeadline(60_000L));
    }
}
