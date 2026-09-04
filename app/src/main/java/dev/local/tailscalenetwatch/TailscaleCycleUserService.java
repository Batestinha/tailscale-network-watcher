package dev.local.tailscalenetwatch;

import android.content.Context;

import androidx.annotation.Keep;

import java.util.concurrent.atomic.AtomicBoolean;

/** Temporary Shizuku UserService. It accepts one transaction and is then removed by the app. */
public final class TailscaleCycleUserService extends ITailscaleCycleService.Stub {
    private final AtomicBoolean used = new AtomicBoolean(false);

    public TailscaleCycleUserService() {
    }

    @Keep
    public TailscaleCycleUserService(Context ignored) {
    }

    @Override
    public CycleResult cycleTailscale() {
        if (!used.compareAndSet(false, true)) {
            return new CycleResult(
                    CycleResult.INTERRUPTED,
                    "UserService transaction was already consumed",
                    false,
                    false
            );
        }
        return PrivilegedCycle.cycle(new ProcessCommandExecutor()).toParcelable();
    }

    /** Reserved Shizuku UserService removal transaction. */
    @Override
    public void destroy() {
        System.exit(0);
    }
}
