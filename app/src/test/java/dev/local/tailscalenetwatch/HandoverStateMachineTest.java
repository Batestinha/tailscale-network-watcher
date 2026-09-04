package dev.local.tailscalenetwatch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class HandoverStateMachineTest {
    @Test
    public void firstValidatedNetworkBecomesBaselineWithoutRestart() {
        HandoverStateMachine state = HandoverStateMachine.empty();

        HandoverStateMachine.Decision observed = state.onValidated("1:2", "Wi-Fi", 100L);
        assertEquals(HandoverStateMachine.Kind.SCHEDULE_DEBOUNCE, observed.kind);
        assertEquals(3_100L, observed.atMillis);

        HandoverStateMachine.Decision early = state.onDebounce(3_099L);
        assertEquals(HandoverStateMachine.Kind.SCHEDULE_DEBOUNCE, early.kind);
        assertNull(state.getCurrentSignature());

        HandoverStateMachine.Decision committed = state.onDebounce(3_100L);
        assertEquals(HandoverStateMachine.Kind.BASELINE_ESTABLISHED, committed.kind);
        assertEquals("1:2", state.getCurrentSignature());
        assertFalse(state.isRestartActive());
        assertNull(state.getPendingSignature());
    }

    @Test
    public void persistedBaselineSurvivesServiceRecreationWithoutFalseHandover() {
        HandoverStateMachine restored = new HandoverStateMachine(
                "1:2",
                "Wi-Fi",
                null,
                null,
                null,
                0L,
                0L,
                false,
                false
        );

        assertEquals(
                HandoverStateMachine.Kind.NONE,
                restored.onValidated("1:2", "Wi-Fi", 100L).kind
        );
        assertEquals("1:2", restored.getCurrentSignature());
        assertFalse(restored.isRestartActive());
    }

    @Test
    public void baselineEstablishmentDoesNotDiscardManualRestartWaitingForShizuku() {
        HandoverStateMachine state = HandoverStateMachine.empty();
        assertEquals(
                HandoverStateMachine.Kind.RESTART_PENDING,
                state.onManualRestart(0L).kind
        );
        state.onValidated("1:2", "Wi-Fi", 100L);
        assertEquals(
                HandoverStateMachine.Kind.BASELINE_ESTABLISHED,
                state.onDebounce(3_100L).kind
        );
        assertEquals("@manual", state.getPendingSignature());
    }

    @Test
    public void validationFlapCancelsCandidate() {
        HandoverStateMachine state = baseline("1:2", "Wi-Fi");

        assertEquals(
                HandoverStateMachine.Kind.SCHEDULE_DEBOUNCE,
                state.onValidated("2:1", "cellular", 4_000L).kind
        );
        assertEquals(
                HandoverStateMachine.Kind.CANCEL_DEBOUNCE,
                state.onInvalidatedOrLost("2:1").kind
        );
        assertEquals(HandoverStateMachine.Kind.NONE, state.onDebounce(7_000L).kind);
        assertEquals("1:2", state.getCurrentSignature());
        assertNull(state.getPendingSignature());
    }

    @Test
    public void threeSecondDebounceCoalescesRapidChangesToFinalNetwork() {
        HandoverStateMachine state = baseline("1:2", "Wi-Fi");

        state.onValidated("2:1", "cellular", 4_000L);
        HandoverStateMachine.Decision finalCandidate =
                state.onValidated("3:8", "Ethernet", 5_000L);
        assertEquals(8_000L, finalCandidate.atMillis);
        assertEquals(HandoverStateMachine.Kind.SCHEDULE_DEBOUNCE,
                state.onDebounce(7_000L).kind);

        HandoverStateMachine.Decision commit = state.onDebounce(8_000L);
        assertEquals(HandoverStateMachine.Kind.RESTART_PENDING, commit.kind);
        assertEquals("3:8", state.getCurrentSignature());
        assertEquals("3:8", state.getPendingSignature());
        assertEquals(8_000L + HandoverStateMachine.SHIZUKU_PENDING_MS,
                state.getPendingExpiresAtMillis());
    }

    @Test
    public void rapidReturnToBaselineDoesNotRestart() {
        HandoverStateMachine state = baseline("1:2", "Wi-Fi");

        state.onValidated("2:1", "cellular", 4_000L);
        HandoverStateMachine.Decision returned =
                state.onValidated("1:2", "Wi-Fi", 5_000L);
        assertEquals(HandoverStateMachine.Kind.CANCEL_DEBOUNCE, returned.kind);
        assertEquals(HandoverStateMachine.Kind.NONE, state.onDebounce(8_000L).kind);
        assertEquals("1:2", state.getCurrentSignature());
    }

    @Test
    public void transitionDuringRestartProducesAtMostOneFollowUp() {
        HandoverStateMachine state = baseline("1:2", "Wi-Fi");
        state.onShizukuReadinessChanged(true, 3_001L);

        state.onValidated("2:1", "cellular", 4_000L);
        HandoverStateMachine.Decision first = state.onDebounce(7_000L);
        assertEquals(HandoverStateMachine.Kind.RESTART_NOW, first.kind);
        assertFalse(first.followUp);

        state.onValidated("3:8", "Ethernet", 8_000L);
        assertEquals(
                HandoverStateMachine.Kind.NETWORK_COMMITTED_DURING_RESTART,
                state.onDebounce(11_000L).kind
        );
        HandoverStateMachine.Decision followUp = state.onRestartTerminal(true, 12_000L);
        assertEquals(HandoverStateMachine.Kind.RESTART_NOW, followUp.kind);
        assertTrue(followUp.followUp);
        assertEquals("3:8", followUp.signature);

        state.onValidated("4:100", "USB", 13_000L);
        state.onDebounce(16_000L);
        assertEquals(
                HandoverStateMachine.Kind.NONE,
                state.onRestartTerminal(true, 17_000L).kind
        );
        assertEquals("4:100", state.getCurrentSignature());
        assertFalse(state.isRestartActive());
    }

    @Test
    public void finalSignatureEqualToCompletedRestartSuppressesFollowUp() {
        HandoverStateMachine state = baseline("1:2", "Wi-Fi");
        state.onShizukuReadinessChanged(true, 3_001L);
        state.onValidated("2:1", "cellular", 4_000L);
        state.onDebounce(7_000L);

        state.onValidated("3:8", "Ethernet", 8_000L);
        state.onDebounce(11_000L);
        state.onValidated("2:1", "cellular", 12_000L);
        state.onDebounce(15_000L);

        assertEquals(
                HandoverStateMachine.Kind.NONE,
                state.onRestartTerminal(true, 16_000L).kind
        );
        assertFalse(state.isRestartActive());
    }

    @Test
    public void pendingShizukuWorkRunsInsideFiveMinutesAndExpiresOutsideIt() {
        HandoverStateMachine inside = baseline("1:2", "Wi-Fi");
        inside.onValidated("2:1", "cellular", 4_000L);
        HandoverStateMachine.Decision pending = inside.onDebounce(7_000L);
        assertEquals(HandoverStateMachine.Kind.RESTART_PENDING, pending.kind);
        long expiry = inside.getPendingExpiresAtMillis();
        assertEquals(
                HandoverStateMachine.Kind.RESTART_NOW,
                inside.onShizukuReadinessChanged(true, expiry - 1L).kind
        );

        HandoverStateMachine outside = baseline("1:2", "Wi-Fi");
        outside.onValidated("2:1", "cellular", 4_000L);
        outside.onDebounce(7_000L);
        assertEquals(
                HandoverStateMachine.Kind.PENDING_EXPIRED,
                outside.onShizukuReadinessChanged(true, expiry).kind
        );
        assertNull(outside.getPendingSignature());
        assertFalse(outside.isRestartActive());
    }

    @Test
    public void restoredPendingWorkWaitsForTheSameValidatedCurrentNetwork() {
        HandoverStateMachine restored = new HandoverStateMachine(
                "2:1",
                "cellular",
                "2:1",
                "cellular",
                "Network changed",
                1_000L,
                301_000L,
                false,
                false
        );

        assertEquals(
                HandoverStateMachine.Kind.NONE,
                restored.onShizukuReadinessChanged(true, 2_000L).kind
        );
        assertFalse(restored.isRestartActive());
        assertEquals(
                HandoverStateMachine.Kind.RESTART_NOW,
                restored.onValidated("2:1", "cellular", 2_001L).kind
        );
    }

    @Test
    public void binderDeathBeforePrivilegedCallReturnsRequestToOriginalExpiryWindow() {
        HandoverStateMachine state = baseline("1:2", "Wi-Fi");
        state.onShizukuReadinessChanged(true, 3_001L);
        state.onValidated("2:1", "cellular", 4_000L);
        state.onDebounce(7_000L);

        HandoverStateMachine.Decision pending = state.onRestartCouldNotStart(8_000L);
        assertEquals(HandoverStateMachine.Kind.RESTART_PENDING, pending.kind);
        assertEquals(7_000L + HandoverStateMachine.SHIZUKU_PENDING_MS,
                pending.atMillis);
        assertFalse(state.isRestartActive());
    }

    private static HandoverStateMachine baseline(String signature, String label) {
        HandoverStateMachine state = HandoverStateMachine.empty();
        state.onValidated(signature, label, 0L);
        assertEquals(
                HandoverStateMachine.Kind.BASELINE_ESTABLISHED,
                state.onDebounce(HandoverStateMachine.HANDOVER_DEBOUNCE_MS).kind
        );
        return state;
    }
}
