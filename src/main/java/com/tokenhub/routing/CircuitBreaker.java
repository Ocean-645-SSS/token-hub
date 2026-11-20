package com.tokenhub.routing;

/**
 * Per-provider circuit breaker: CLOSED -> OPEN -> HALF_OPEN -> CLOSED.
 * Uses a simple consecutive-failure threshold (not a rolling window), which is
 * intentional for this scope; a rolling failure-rate window is the natural upgrade.
 * Thread-safe via synchronized.
 */
public final class CircuitBreaker {

    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final int failureThreshold;
    private final long cooldownMs;

    private State state = State.CLOSED;
    private int consecutiveFailures = 0;
    private long openedAtMillis = 0L;
    private boolean halfOpenProbeInFlight = false;

    public CircuitBreaker(int failureThreshold, long cooldownMs) {
        if (failureThreshold <= 0) throw new IllegalArgumentException("failureThreshold must be > 0");
        if (cooldownMs <= 0) throw new IllegalArgumentException("cooldownMs must be > 0");
        this.failureThreshold = failureThreshold;
        this.cooldownMs = cooldownMs;
    }

    /** Whether a request may be attempted through this provider right now. */
    public synchronized boolean allow() {
        switch (state) {
            case CLOSED:
                return true;
            case OPEN:
                if (System.currentTimeMillis() - openedAtMillis >= cooldownMs) {
                    state = State.HALF_OPEN;
                    halfOpenProbeInFlight = true;
                    return true;
                }
                return false;
            case HALF_OPEN:
                if (halfOpenProbeInFlight) return false;
                halfOpenProbeInFlight = true;
                return true;
            default:
                return false;
        }
    }

    public synchronized void recordSuccess() {
        consecutiveFailures = 0;
        halfOpenProbeInFlight = false;
        state = State.CLOSED;
    }

    public synchronized void recordFailure() {
        halfOpenProbeInFlight = false;
        switch (state) {
            case CLOSED:
                consecutiveFailures++;
                if (consecutiveFailures >= failureThreshold) open();
                break;
            case HALF_OPEN:
                open();
                break;
            case OPEN:
                // already open; nothing further to count
                break;
        }
    }

    private void open() {
        state = State.OPEN;
        openedAtMillis = System.currentTimeMillis();
    }

    public synchronized State state() { return state; }
}
