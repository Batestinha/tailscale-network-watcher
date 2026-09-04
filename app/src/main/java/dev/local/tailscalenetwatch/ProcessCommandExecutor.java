package dev.local.tailscalenetwatch;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Runs only fixed platform tools, without a shell, with bounded arguments, output, and time. */
final class ProcessCommandExecutor implements PrivilegedCycle.CommandExecutor {
    private static final int MAX_ARGUMENTS = 16;
    private static final int MAX_ARGUMENT_LENGTH = 512;
    private static final Set<String> ALLOWED_EXECUTABLES = Set.of(
            "/system/bin/settings",
            "/system/bin/am"
    );

    @Override
    public PrivilegedCycle.CommandOutput run(
            List<String> arguments,
            long timeoutMillis,
            int maxOutputBytes
    ) {
        if (!isAllowed(arguments) || timeoutMillis <= 0L || maxOutputBytes <= 0) {
            return PrivilegedCycle.CommandOutput.failure(126, "Rejected command arguments");
        }

        Process process = null;
        OutputDrainer drainer = null;
        Thread drainerThread = null;
        try {
            process = new ProcessBuilder(arguments)
                    .redirectErrorStream(true)
                    .start();
            drainer = new OutputDrainer(process.getInputStream(), maxOutputBytes);
            drainerThread = new Thread(drainer, "tailscale-cycle-output");
            drainerThread.setDaemon(true);
            drainerThread.start();

            boolean completed = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS);
            if (!completed) {
                process.destroy();
                if (!process.waitFor(250L, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly();
                }
                joinQuietly(drainerThread);
                return new PrivilegedCycle.CommandOutput(
                        -1,
                        true,
                        false,
                        drainer.output()
                );
            }

            joinQuietly(drainerThread);
            return new PrivilegedCycle.CommandOutput(
                    process.exitValue(),
                    false,
                    false,
                    drainer.output()
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            if (process != null) {
                process.destroyForcibly();
            }
            return new PrivilegedCycle.CommandOutput(
                    -1,
                    false,
                    true,
                    drainer == null ? "" : drainer.output()
            );
        } catch (IOException | RuntimeException exception) {
            if (process != null) {
                process.destroyForcibly();
            }
            return PrivilegedCycle.CommandOutput.failure(
                    127,
                    exception.getClass().getSimpleName()
            );
        }
    }

    private static boolean isAllowed(List<String> arguments) {
        if (arguments == null
                || arguments.isEmpty()
                || arguments.size() > MAX_ARGUMENTS
                || !ALLOWED_EXECUTABLES.contains(arguments.get(0))) {
            return false;
        }
        for (String argument : arguments) {
            if (argument == null || argument.length() > MAX_ARGUMENT_LENGTH) {
                return false;
            }
        }
        return true;
    }

    private static void joinQuietly(Thread thread) throws InterruptedException {
        if (thread != null) {
            thread.join(500L);
        }
    }

    private static final class OutputDrainer implements Runnable {
        private final InputStream input;
        private final int limit;
        private final ByteArrayOutputStream captured = new ByteArrayOutputStream();

        private OutputDrainer(InputStream input, int limit) {
            this.input = input;
            this.limit = limit;
        }

        @Override
        public void run() {
            byte[] buffer = new byte[512];
            try (InputStream stream = input) {
                int count;
                while ((count = stream.read(buffer)) != -1) {
                    synchronized (captured) {
                        int remaining = limit - captured.size();
                        if (remaining > 0) {
                            captured.write(buffer, 0, Math.min(remaining, count));
                        }
                    }
                }
            } catch (IOException ignored) {
                // A timeout destroys the process and closes its stream.
            }
        }

        private String output() {
            synchronized (captured) {
                return new String(captured.toByteArray(), StandardCharsets.UTF_8);
            }
        }
    }
}
