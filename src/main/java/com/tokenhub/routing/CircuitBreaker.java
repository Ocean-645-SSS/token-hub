package com.tokenhub.routing;

import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Per-provider circuit breaker: CLOSED -> OPEN -> HALF_OPEN -> CLOSED.
 * Uses a simple consecutive-failure threshold (not a rolling window), which is
 * intentional for this scope; a rolling failure-rate window is the natural upgrade.
 * Thread-safe via synchronized.
 */
public final class CircuitBreaker {

    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final int failureThreshold;
    private final long cooldownNanos;
    private final LongSupplier nanoTime;
    private long generation;

    private State state = State.CLOSED;
    private int consecutiveFailures = 0;
    private long openedAtNanos = 0L;
    private boolean halfOpenProbeInFlight = false;

    public CircuitBreaker(int failureThreshold, long cooldownMs) {
        this(failureThreshold, cooldownMs, System::nanoTime);
    }

    CircuitBreaker(int failureThreshold, long cooldownMs, LongSupplier nanoTime) {
        if (failureThreshold <= 0) throw new IllegalArgumentException("failureThreshold must be > 0");
        if (cooldownMs <= 0) throw new IllegalArgumentException("cooldownMs must be > 0");
        this.failureThreshold = failureThreshold;
        this.cooldownNanos = TimeUnit.MILLISECONDS.toNanos(cooldownMs);
        this.nanoTime = nanoTime;
    }

    public synchronized boolean eligible() {
        return switch (state) {
            case CLOSED -> true;
            case OPEN -> nanoTime.getAsLong() - openedAtNanos >= cooldownNanos;
            case HALF_OPEN -> !halfOpenProbeInFlight;
        };
    }

    public enum Outcome { SUCCESS, FAILURE, NEUTRAL }

    /** A permit ties the outcome to the breaker generation that admitted the call. */
    public synchronized Permit acquire() {
        return allow() ? new Permit(this, generation, state == State.HALF_OPEN) : null;
    }

    public record Permit(CircuitBreaker breaker, long generation, boolean probe) {
        public void complete(Outcome outcome) {
            breaker.complete(this, outcome);
        }
    }

    private synchronized void complete(Permit permit, Outcome outcome) {
        if (permit.generation() != generation) return;
        switch (outcome) {
            case SUCCESS -> recordSuccess();
            case FAILURE -> recordFailure();
            case NEUTRAL -> {
                if (permit.probe()) halfOpenProbeInFlight = false;
            }
        }
    }

    /** Whether a request may be attempted through this provider right now. */
    public synchronized boolean allow() {
        switch (state) {
            case CLOSED:
                return true;
            case OPEN:
                if (eligible()) {
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
        if (state != State.CLOSED) generation++;
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
        openedAtNanos = nanoTime.getAsLong();
        generation++;
    }

    public synchronized State state() { return state; }
}
