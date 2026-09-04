package dev.local.tailscalenetwatch;

import dev.local.tailscalenetwatch.CycleResult;

interface ITailscaleCycleService {
    CycleResult cycleTailscale() = 0;
    void destroy() = 16777114;
}
