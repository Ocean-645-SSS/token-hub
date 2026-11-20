package com.tokenhub.routing;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CircuitBreakerTest {

    @Test void tripsAfterConsecutiveFailures() {
        CircuitBreaker cb = new CircuitBreaker(3, 10_000);
        assertTrue(cb.allow());
        cb.recordFailure();
        assertTrue(cb.allow());
        cb.recordFailure();
        assertTrue(cb.allow());
        cb.recordFailure();
        assertEquals(CircuitBreaker.State.OPEN, cb.state());
        assertFalse(cb.allow());
    }

    @Test void successResetsFailureCount() {
        CircuitBreaker cb = new CircuitBreaker(3, 10_000);
        cb.recordFailure();
        cb.recordFailure();
        cb.recordSuccess();
        cb.recordFailure();
        cb.recordFailure();
        assertEquals(CircuitBreaker.State.CLOSED, cb.state());
        assertTrue(cb.allow());
    }

    @Test void halfOpenProbeRecoversToClosed() {
        CircuitBreaker cb = new CircuitBreaker(2, 1);
        cb.recordFailure();
        cb.recordFailure();
        assertEquals(CircuitBreaker.State.OPEN, cb.state());
        sleep(5);
        assertTrue(cb.allow()); // transitions to HALF_OPEN and allows one probe
        assertEquals(CircuitBreaker.State.HALF_OPEN, cb.state());
        assertFalse(cb.allow()); // only one probe at a time
        cb.recordSuccess();
        assertEquals(CircuitBreaker.State.CLOSED, cb.state());
    }

    @Test void halfOpenProbeFailureReopens() {
        CircuitBreaker cb = new CircuitBreaker(2, 1);
        cb.recordFailure();
        cb.recordFailure();
        assertEquals(CircuitBreaker.State.OPEN, cb.state());
        sleep(5);
        assertTrue(cb.allow());
        cb.recordFailure();
        assertEquals(CircuitBreaker.State.OPEN, cb.state());
        assertFalse(cb.allow());
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
