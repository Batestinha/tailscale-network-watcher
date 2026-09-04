package dev.local.tailscalenetwatch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

public final class PrivilegedCycleTest {
    @Test
    public void alwaysOnMismatchSkipsBeforeForceStop() {
        FakeExecutor executor = new FakeExecutor(
                PrivilegedCycle.CommandOutput.success("com.example.other\n")
        );

        PrivilegedCycle.Outcome outcome = PrivilegedCycle.cycle(executor);

        assertEquals(CycleResult.SKIPPED_NOT_ALWAYS_ON, outcome.code);
        assertFalse(outcome.forceStopAttempted);
        assertFalse(outcome.forceStopCompleted);
        assertEquals(1, executor.commands.size());
        assertEquals("/system/bin/settings", executor.commands.get(0).get(0));
    }

    @Test
    public void successfulCycleUsesThreeArgumentArrayCommands() {
        FakeExecutor executor = new FakeExecutor(
                PrivilegedCycle.CommandOutput.success("com.tailscale.ipn\n"),
                PrivilegedCycle.CommandOutput.success(""),
                PrivilegedCycle.CommandOutput.success("Broadcast completed: result=0")
        );

        PrivilegedCycle.Outcome outcome = PrivilegedCycle.cycle(executor);

        assertEquals(CycleResult.SUCCESS, outcome.code);
        assertTrue(outcome.forceStopCompleted);
        assertEquals(3, executor.commands.size());
        assertEquals(List.of(
                "/system/bin/am", "force-stop", "--user", "current", "com.tailscale.ipn"
        ), executor.commands.get(1));
        assertTrue(executor.commands.get(2).contains("com.tailscale.ipn.CONNECT_VPN"));
        assertTrue(executor.commands.get(2).contains("com.tailscale.ipn/.IPNReceiver"));
    }

    @Test
    public void forceStopAndConnectFailuresAreTyped() {
        FakeExecutor forceStopFailure = new FakeExecutor(
                PrivilegedCycle.CommandOutput.success("com.tailscale.ipn"),
                PrivilegedCycle.CommandOutput.failure(1, "denied")
        );
        PrivilegedCycle.Outcome stopped = PrivilegedCycle.cycle(forceStopFailure);
        assertEquals(CycleResult.FORCE_STOP_FAILED, stopped.code);
        assertTrue(stopped.forceStopAttempted);
        assertFalse(stopped.forceStopCompleted);

        FakeExecutor connectFailure = new FakeExecutor(
                PrivilegedCycle.CommandOutput.success("com.tailscale.ipn"),
                PrivilegedCycle.CommandOutput.success(""),
                PrivilegedCycle.CommandOutput.failure(1, "receiver unavailable")
        );
        PrivilegedCycle.Outcome connected = PrivilegedCycle.cycle(connectFailure);
        assertEquals(CycleResult.CONNECT_FAILED, connected.code);
        assertTrue(connected.forceStopCompleted);
    }

    private static final class FakeExecutor implements PrivilegedCycle.CommandExecutor {
        private final Deque<PrivilegedCycle.CommandOutput> results = new ArrayDeque<>();
        private final List<List<String>> commands = new ArrayList<>();

        private FakeExecutor(PrivilegedCycle.CommandOutput... outputs) {
            results.addAll(List.of(outputs));
        }

        @Override
        public PrivilegedCycle.CommandOutput run(
                List<String> arguments,
                long timeoutMillis,
                int maxOutputBytes
        ) {
            commands.add(List.copyOf(arguments));
            return results.removeFirst();
        }
    }
}
