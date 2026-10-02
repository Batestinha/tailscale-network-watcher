package dev.local.tailscalenetwatch;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

public final class LegacyBestNetworkSelectorTest {
    @Test
    public void higherPriorityCandidateWins() {
        LegacyBestNetworkSelector.Candidate wifi = candidate(1L, "wifi", 500);
        LegacyBestNetworkSelector.Candidate ethernet = candidate(2L, "ethernet", 600);

        assertEquals(
                ethernet,
                LegacyBestNetworkSelector.select(Arrays.asList(wifi, ethernet), 1L)
        );
    }

    @Test
    public void currentCandidateWinsEqualPriorityTie() {
        LegacyBestNetworkSelector.Candidate first = candidate(1L, "first", 500);
        LegacyBestNetworkSelector.Candidate current = candidate(2L, "current", 500);

        assertEquals(
                current,
                LegacyBestNetworkSelector.select(Arrays.asList(first, current), 2L)
        );
    }

    @Test
    public void unsignedHandleBreaksTieWithoutCurrentCandidate() {
        LegacyBestNetworkSelector.Candidate highUnsigned = candidate(-1L, "high", 500);
        LegacyBestNetworkSelector.Candidate lowUnsigned = candidate(3L, "low", 500);

        assertEquals(
                lowUnsigned,
                LegacyBestNetworkSelector.select(
                        Arrays.asList(highUnsigned, lowUnsigned),
                        null
                )
        );
    }

    @Test
    public void emptyCandidateSetReturnsNull() {
        assertEquals(
                null,
                LegacyBestNetworkSelector.select(Collections.emptyList(), null)
        );
    }

    private static LegacyBestNetworkSelector.Candidate candidate(
            long handle,
            String signature,
            int priority
    ) {
        return new LegacyBestNetworkSelector.Candidate(
                handle,
                signature,
                signature,
                priority
        );
    }
}
