package app.pub.core;

/**
 * Paces an event stream to at most one event per interval on average.
 *
 * <p>A plain "drop if less than one interval since the last event" filter halves the delivered rate
 * when the source runs slightly fast or jittery (a 10 Hz request fed at 12.5 Hz delivers 6.25 Hz).
 * This uses deadline scheduling instead: each accepted event moves the deadline forward by exactly
 * one interval, events up to 10% early are accepted, and after a gap the schedule restarts instead
 * of bursting to catch up.
 *
 * <p>Times are any monotonic clock in the same unit as the interval. Not thread-safe.
 */
public final class RatePacer {
    private final long interval, tolerance;
    private long next;
    private boolean started;

    public RatePacer(long interval) {
        if (interval <= 0) throw new IllegalArgumentException("interval must be positive");
        this.interval = interval;
        this.tolerance = interval / 10;
    }

    /** Returns true if an event arriving at {@code now} should be delivered. */
    public boolean accept(long now) {
        if (started && now < next - tolerance) return false;
        next = !started || now - next >= interval ? now + interval : next + interval;
        started = true;
        return true;
    }
}
