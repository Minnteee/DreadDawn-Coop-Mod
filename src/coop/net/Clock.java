package coop.net;

/**
 * Maps the other machine's send times onto our own clock, so remote movement is replayed with the
 * timing it really had (not the bursty arrival times of a VPN line), and picks a replay delay that
 * covers the measured jitter.
 */
public final class Clock {
    private Clock() {}

    /** our monotonic clock in ms */
    public static long now() {
        return System.nanoTime() / 1_000_000L;
    }

    /** smallest seen (arrival - send time): clock offset + fastest transport time */
    private static double offset = Double.NaN;
    /** smoothed lateness of messages compared to the fastest one */
    private static double jitter = 15;

    /** a remote send time (ms on the other machine) in our clock; arrival = our time when it arrived */
    public static synchronized long toLocal(long remoteSent, long arrival) {
        double d = arrival - remoteSent;
        if (Double.isNaN(offset) || d < offset) {
            offset = d;
        } else {
            // follow a slowly growing offset (clock drift, a slower route)
            offset += (d - offset) * 0.002;
        }
        double late = d - offset;
        jitter += (Math.min(late, 400) - jitter) * (late > jitter ? 0.1 : 0.01);
        return (long) (remoteSent + offset);
    }

    /** how far in the past the other player is shown: enough to always have two states to blend */
    public static synchronized long renderDelay() {
        return (long) Math.max(70, Math.min(260, 45 + 2.0 * jitter));
    }

    public static synchronized void reset() {
        offset = Double.NaN;
        jitter = 15;
    }
}
