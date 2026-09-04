package dev.local.tailscalenetwatch;

import java.util.List;

/** The privileged operation, separated from process creation so command policy is unit-testable. */
final class PrivilegedCycle {
    static final String TAILSCALE_PACKAGE = "com.tailscale.ipn";
    static final String TAILSCALE_RECEIVER = "com.tailscale.ipn/.IPNReceiver";
    static final String CONNECT_ACTION = "com.tailscale.ipn.CONNECT_VPN";

    private static final long COMMAND_TIMEOUT_MS = 5_000L;
    private static final int MAX_OUTPUT_BYTES = 4_096;

    private static final List<String> READ_ALWAYS_ON = List.of(
            "/system/bin/settings", "get", "secure", "always_on_vpn_app"
    );
    private static final List<String> FORCE_STOP = List.of(
            "/system/bin/am", "force-stop", "--user", "current", TAILSCALE_PACKAGE
    );
    private static final List<String> CONNECT = List.of(
            "/system/bin/am",
            "broadcast",
            "--user",
            "current",
            "-a",
            CONNECT_ACTION,
            "-n",
            TAILSCALE_RECEIVER,
            "-f",
            "0x10000020"
    );

    interface CommandExecutor {
        CommandOutput run(List<String> arguments, long timeoutMillis, int maxOutputBytes);
    }

    static final class CommandOutput {
        final int exitCode;
        final boolean timedOut;
        final boolean interrupted;
        final String output;

        CommandOutput(int exitCode, boolean timedOut, boolean interrupted, String output) {
            this.exitCode = exitCode;
            this.timedOut = timedOut;
            this.interrupted = interrupted;
            this.output = output == null ? "" : output;
        }

        static CommandOutput success(String output) {
            return new CommandOutput(0, false, false, output);
        }

        static CommandOutput failure(int exitCode, String output) {
            return new CommandOutput(exitCode, false, false, output);
        }
    }

    static final class Outcome {
        final int code;
        final String detail;
        final boolean forceStopAttempted;
        final boolean forceStopCompleted;

        Outcome(
                int code,
                String detail,
                boolean forceStopAttempted,
                boolean forceStopCompleted
        ) {
            this.code = code;
            this.detail = detail;
            this.forceStopAttempted = forceStopAttempted;
            this.forceStopCompleted = forceStopCompleted;
        }

        CycleResult toParcelable() {
            return new CycleResult(code, detail, forceStopAttempted, forceStopCompleted);
        }
    }

    private PrivilegedCycle() {
    }

    static Outcome cycle(CommandExecutor executor) {
        CommandOutput alwaysOn = executor.run(READ_ALWAYS_ON, COMMAND_TIMEOUT_MS, MAX_OUTPUT_BYTES);
        if (alwaysOn.interrupted || alwaysOn.timedOut || alwaysOn.exitCode != 0) {
            return new Outcome(
                    CycleResult.INTERRUPTED,
                    "Could not read always_on_vpn_app",
                    false,
                    false
            );
        }

        String configuredPackage = alwaysOn.output.trim();
        if (!TAILSCALE_PACKAGE.equals(configuredPackage)) {
            String value = configuredPackage.isEmpty() ? "unset" : configuredPackage;
            return new Outcome(
                    CycleResult.SKIPPED_NOT_ALWAYS_ON,
                    "always_on_vpn_app is " + value,
                    false,
                    false
            );
        }

        CommandOutput forceStop = executor.run(FORCE_STOP, COMMAND_TIMEOUT_MS, MAX_OUTPUT_BYTES);
        if (forceStop.interrupted || forceStop.timedOut || forceStop.exitCode != 0) {
            return new Outcome(
                    CycleResult.FORCE_STOP_FAILED,
                    summarizeFailure("am force-stop failed", forceStop),
                    true,
                    false
            );
        }

        CommandOutput connect = executor.run(CONNECT, COMMAND_TIMEOUT_MS, MAX_OUTPUT_BYTES);
        if (connect.interrupted || connect.timedOut || connect.exitCode != 0) {
            return new Outcome(
                    CycleResult.CONNECT_FAILED,
                    summarizeFailure("CONNECT_VPN broadcast failed", connect),
                    true,
                    true
            );
        }

        return new Outcome(
                CycleResult.SUCCESS,
                "Force-stop and CONNECT_VPN broadcast completed",
                true,
                true
        );
    }

    private static String summarizeFailure(String prefix, CommandOutput output) {
        if (output.interrupted) {
            return prefix + ": interrupted";
        }
        if (output.timedOut) {
            return prefix + ": timed out";
        }
        String text = output.output.trim().replace('\n', ' ');
        if (text.length() > 160) {
            text = text.substring(0, 160);
        }
        return text.isEmpty()
                ? prefix + " (exit " + output.exitCode + ")"
                : prefix + " (exit " + output.exitCode + "): " + text;
    }
}
