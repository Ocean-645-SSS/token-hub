package com.tokenhub.profiling;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class ResultApiTrace {
    private static final Logger log = LoggerFactory.getLogger(ResultApiTrace.class);
    private static final boolean ENABLED = SeckillTrace.enabled();

    private ResultApiTrace() {}

    public static void record(String requestId, int pollSequence, long r0WallMs,
                               long r0Nanos, long r1Nanos, long r2Nanos, long r3Nanos,
                               int statusCode, String businessState) {
        if (!ENABLED) return;
        log.info("result-profile requestId={} pollSequence={} r0WallMs={} r1MinusR0Ms={} "
                        + "lookupMs={} r3MinusR2Ms={} totalMs={} statusCode={} businessState={}",
                requestId, pollSequence, r0WallMs,
                durationMs(r0Nanos, r1Nanos), durationMs(r1Nanos, r2Nanos),
                durationMs(r2Nanos, r3Nanos), durationMs(r0Nanos, r3Nanos),
                statusCode, businessState);
    }

    private static double durationMs(long startNanos, long endNanos) {
        return (endNanos - startNanos) / 1_000_000.0;
    }
}
