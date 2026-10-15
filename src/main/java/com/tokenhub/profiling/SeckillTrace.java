package com.tokenhub.profiling;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory, bounded-by-completion seckill trace; disabled unless explicitly enabled. */
public final class SeckillTrace {
    private static final Logger log = LoggerFactory.getLogger(SeckillTrace.class);
    private static final Map<String, Trace> TRACES = new ConcurrentHashMap<>();
    private static final boolean ENABLED = Boolean.parseBoolean(System.getProperty("tokenhub.profiling.enabled", System.getenv().getOrDefault("TOKENHUB_PROFILING_ENABLED", "false")));
    private SeckillTrace() {}
    public static boolean enabled() { return ENABLED; }
    public static void start(String requestId) { if (ENABLED) TRACES.put(requestId, new Trace(System.nanoTime())); }
    public static void mark(String requestId, String stage) { Trace t = ENABLED ? TRACES.get(requestId) : null; if (t != null) t.mark(stage); }
    public static void markAt(String requestId, String stage, long timestampNanos) { Trace t = ENABLED ? TRACES.get(requestId) : null; if (t != null) t.markAt(stage, timestampNanos); }
    public static void finish(String requestId) { if (!ENABLED) return; Trace t = TRACES.remove(requestId); if (t != null) log.info("seckill-profile requestId={} finishedEpochMs={} {}", requestId, System.currentTimeMillis(), t.csv()); }
    private static final class Trace {
        private final long t0; private final Map<String, Long> marks = new ConcurrentHashMap<>();
        private Trace(long t0) { this.t0 = t0; }
        private void mark(String stage) { markAt(stage, System.nanoTime()); }
        private void markAt(String stage, long timestampNanos) { marks.putIfAbsent(stage, timestampNanos); }
        private String csv() {
            StringBuilder b = new StringBuilder("t0=0");
            for (String stage : new String[]{"t1","t2","t3","t4","t5","t6","t7","t8","t9","t10","t11","p0","p1","p2","p3","p4","p5","p6","p7","p8","p9","m0","m1","m2","m3","m4","m5"}) {
                Long timestamp = marks.get(stage);
                if (timestamp != null) b.append(',').append(stage).append("=").append((timestamp-t0)/1_000_000.0).append("ms");
            }
            return b.toString();
        }
    }
}
