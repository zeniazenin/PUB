package app.pub.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class RatePacerTest {
    /** Counts events delivered from a source emitting every {@code period} for {@code duration}. */
    private static int delivered(long interval, long period, long jitter, long duration) {
        RatePacer pacer = new RatePacer(interval);
        int count = 0;
        for (long t = 0, i = 0; t < duration; t = ++i * period) {
            long arrival = t + (i % 2 == 0 ? jitter : -jitter);
            if (pacer.accept(Math.max(0, arrival))) count++;
        }
        return count;
    }

    @Test public void slightlyFastSourceKeepsTheRequestedRate() {
        // 10 Hz requested, hardware at 12.5 Hz: a last-event filter would deliver 6.25 Hz.
        assertEquals(100, delivered(100, 80, 0, 10_000), 2);
    }

    @Test public void jitterAroundTheIntervalDropsNothing() {
        assertEquals(100, delivered(100, 100, 4, 10_000));
    }

    @Test public void fastSourceIsCappedAtTheInterval() {
        assertEquals(100, delivered(100, 10, 0, 10_000), 1);
    }

    @Test public void slowSourceIsNeverThrottled() {
        assertEquals(50, delivered(100, 200, 0, 10_000));
    }

    @Test public void slightlyEarlyLocationFixIsAccepted() {
        RatePacer pacer = new RatePacer(1000);
        assertTrue(pacer.accept(0));
        assertTrue("A fix 2 ms early must not be dropped", pacer.accept(998));
        assertTrue(pacer.accept(2001));
    }

    @Test public void resumesWithoutBurstingAfterAGap() {
        RatePacer pacer = new RatePacer(100);
        assertTrue(pacer.accept(0));
        assertTrue(pacer.accept(5_000));
        assertFalse("No catch-up burst after a pause", pacer.accept(5_010));
        assertTrue(pacer.accept(5_100));
    }

    @Test(expected = IllegalArgumentException.class) public void rejectsNonPositiveInterval() {
        new RatePacer(0);
    }
}
