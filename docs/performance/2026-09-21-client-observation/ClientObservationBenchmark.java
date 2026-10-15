import java.io.BufferedWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Profiling-only companion. Its load, polling, sleep and HTTP behavior matches
 * SeckillBenchmark; it only records client observation timestamps.
 */
public final class ClientObservationBenchmark {
    private static final String SECRET = "tokenhub-v1-development-secret-change-before-production-20260919";
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    private record Accepted(String requestId, long userId, long c0WallMs, long c0Nanos,
                            long c1Nanos, long c2Nanos, long c2WallMs) {}
    private record WorkerResult(List<Long> latencyMicros, long total, long accepted, long failed,
                                long maxMicros, List<Accepted> captured) {}
    private record PollAttempt(String requestId, int sequence, long c5WallMs, long c6WallMs,
                               double httpLatencyMs, int statusCode, String businessState,
                               long intervalSincePreviousC5Ms) {}
    private record PollResult(Accepted accepted, long c3WallMs, long c3Nanos, long c4WallMs,
                              long c4Nanos, long c7WallMs, long c7Nanos,
                              List<PollAttempt> attempts) {}

    public static void main(String[] args) throws Exception {
        if (args.length < 6 || args.length > 8) {
            throw new IllegalArgumentException("usage: <baseUrl> <activityId> <concurrency> <seconds> <firstUserId> <summaryCsv> [captureCsv] [traceDir]");
        }
        String baseUrl = args[0], activityId = args[1];
        int concurrency = Integer.parseInt(args[2]), seconds = Integer.parseInt(args[3]);
        AtomicLong users = new AtomicLong(Long.parseLong(args[4]));
        Path summary = Path.of(args[5]); Path capture = args.length >= 7 ? Path.of(args[6]) : null;
        Path traceDir = args.length == 8 ? Path.of(args[7]) : summary.toAbsolutePath().getParent();
        Files.createDirectories(traceDir);

        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).version(HttpClient.Version.HTTP_1_1).build();
        CountDownLatch ready = new CountDownLatch(concurrency), start = new CountDownLatch(1);
        List<Future<WorkerResult>> futures = new ArrayList<>();
        AtomicLong deadlineNano = new AtomicLong();
        for (int i = 0; i < concurrency; i++) {
            futures.add(executor.submit(() -> worker(client, baseUrl, activityId, users, deadlineNano, ready, start, capture != null)));
        }
        ready.await();
        long wallStart = System.nanoTime();
        deadlineNano.set(wallStart + TimeUnit.SECONDS.toNanos(seconds));
        start.countDown();
        List<Long> samples = new ArrayList<>(); List<Accepted> captures = new ArrayList<>();
        long total = 0, accepted = 0, failed = 0, max = 0;
        for (Future<WorkerResult> future : futures) {
            WorkerResult result = future.get(seconds + 45L, TimeUnit.SECONDS);
            samples.addAll(result.latencyMicros()); captures.addAll(result.captured());
            total += result.total(); accepted += result.accepted(); failed += result.failed(); max = Math.max(max, result.maxMicros());
        }
        long elapsedNanos = System.nanoTime() - wallStart;
        executor.shutdownNow(); executor.awaitTermination(10, TimeUnit.SECONDS);
        samples.sort(Comparator.naturalOrder());
        double avg = samples.stream().mapToLong(Long::longValue).average().orElse(0) / 1000.0;
        double qps = total * 1_000_000_000.0 / elapsedNanos;
        String row = "%s,%d,%d,%d,%d,%.2f,%.3f,%.3f,%.3f,%.3f,%.3f,%.4f%n".formatted(
                Instant.now(), concurrency, total, accepted, failed, qps, avg,
                percentile(samples, .50), percentile(samples, .95), percentile(samples, .99),
                max / 1000.0, total == 0 ? 0 : failed * 100.0 / total);
        boolean exists = Files.exists(summary); Files.createDirectories(summary.getParent());
        try (BufferedWriter out = Files.newBufferedWriter(summary, StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)) {
            if (!exists) out.write("timestamp,concurrency,total,accepted,failed,qps,avg_ms,p50_ms,p95_ms,p99_ms,max_ms,error_rate_pct\n");
            out.write(row);
        }
        if (capture != null) {
            try (BufferedWriter out = Files.newBufferedWriter(capture, StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING)) {
                out.write("request_id,user_id,started_epoch_ms\n");
                for (Accepted item : captures) out.write(item.requestId() + "," + item.userId() + "," + item.c0WallMs() + "\n");
            }
            pollCompletions(baseUrl, captures, capture.resolveSibling(capture.getFileName() + ".e2e.csv"), traceDir);
        }
        System.out.print(row);
    }

    private static WorkerResult worker(HttpClient client, String baseUrl, String activityId, AtomicLong users,
                                       AtomicLong deadlineNano, CountDownLatch ready, CountDownLatch start,
                                       boolean capture) {
        List<Long> latency = new ArrayList<>(); List<Accepted> captured = new ArrayList<>();
        long total = 0, accepted = 0, failed = 0, max = 0; long user = users.getAndIncrement(); String bearer;
        try {
            bearer = token(user);
            client.send(HttpRequest.newBuilder(URI.create(baseUrl + "/api/packages")).timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.discarding());
        } catch (Exception exception) { ready.countDown(); return new WorkerResult(latency, 0, 0, 0, 0, captured); }
        ready.countDown();
        try { start.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return new WorkerResult(latency, 0, 0, 0, 0, captured); }
        while (System.nanoTime() < deadlineNano.get()) {
            long c0Nanos = System.nanoTime(), c0WallMs = System.currentTimeMillis();
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/seckill/" + activityId))
                        .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer " + bearer)
                        .POST(HttpRequest.BodyPublishers.noBody()).build();
                long c1Nanos = System.nanoTime();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                long c2Nanos = System.nanoTime(), c2WallMs = System.currentTimeMillis();
                long micros = TimeUnit.NANOSECONDS.toMicros(c2Nanos - c0Nanos);
                latency.add(micros); max = Math.max(max, micros); total++;
                String requestId = requestId(response.body());
                if (response.statusCode() == 200 && requestId != null) {
                    accepted++; if (capture) captured.add(new Accepted(requestId, user, c0WallMs, c0Nanos, c1Nanos, c2Nanos, c2WallMs));
                } else failed++;
            } catch (Exception e) {
                long micros = TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - c0Nanos);
                latency.add(micros); max = Math.max(max, micros); total++; failed++;
            } finally { user = users.getAndIncrement(); try { bearer = token(user); } catch (GeneralSecurityException exception) { throw new IllegalStateException(exception); } }
        }
        return new WorkerResult(latency, total, accepted, failed, max, captured);
    }

    private static void pollCompletions(String baseUrl, List<Accepted> accepted, Path output, Path traceDir) throws Exception {
        ThreadPoolExecutor pollers = (ThreadPoolExecutor) Executors.newFixedThreadPool(Math.min(accepted.size(), 100));
        AtomicLong rejected = new AtomicLong();
        pollers.setRejectedExecutionHandler((task, executor) -> {
            rejected.incrementAndGet();
            throw new java.util.concurrent.RejectedExecutionException("polling executor rejected task");
        });
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        AtomicBoolean sampling = new AtomicBoolean(true);
        Path executorTrace = traceDir.resolve("poll-executor.csv");
        Thread sampler = new Thread(() -> sampleExecutor(pollers, rejected, sampling, executorTrace), "poll-profiler");
        sampler.start();
        List<Future<PollResult>> futures = new ArrayList<>();
        for (Accepted item : accepted) {
            long c3Nanos = System.nanoTime(), c3WallMs = System.currentTimeMillis();
            futures.add(pollers.submit(() -> pollOne(baseUrl, client, item, c3Nanos, c3WallMs)));
        }
        List<PollResult> results = new ArrayList<>();
        for (Future<PollResult> future : futures) results.add(future.get(35, TimeUnit.SECONDS));
        sampling.set(false); sampler.join(2000);
        List<Long> elapsed = new ArrayList<>(); long timedOut = 0;
        for (PollResult result : results) {
            if (result.c7WallMs() >= 0) elapsed.add(result.c7WallMs() - result.accepted().c0WallMs()); else timedOut++;
        }
        pollers.shutdownNow(); elapsed.sort(Comparator.naturalOrder());
        writeClientTrace(traceDir.resolve("client-request-trace.csv"), results);
        writePollTrace(traceDir.resolve("client-poll-trace.csv"), results);
        boolean exists = Files.exists(output);
        try (BufferedWriter out = Files.newBufferedWriter(output, StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)) {
            if (!exists) out.write("accepted,completed,timed_out,avg_ms,p50_ms,p95_ms,p99_ms,max_ms\n");
            double average = elapsed.stream().mapToLong(Long::longValue).average().orElse(0);
            long max = elapsed.stream().mapToLong(Long::longValue).max().orElse(0);
            String row = "%d,%d,%d,%.3f,%.3f,%.3f,%.3f,%d%n".formatted(accepted.size(), elapsed.size(), timedOut,
                    average, percentileMillis(elapsed, .50), percentileMillis(elapsed, .95), percentileMillis(elapsed, .99), max);
            out.write(row); System.out.print("E2E," + row);
        }
    }

    private static PollResult pollOne(String baseUrl, HttpClient client, Accepted item, long c3Nanos, long c3WallMs) throws Exception {
        long c4Nanos = System.nanoTime(), c4WallMs = System.currentTimeMillis();
        long deadline = c4Nanos + TimeUnit.SECONDS.toNanos(30); long previousC5WallMs = -1;
        List<PollAttempt> attempts = new ArrayList<>(); int sequence = 0; long c7Nanos = -1, c7WallMs = -1;
        while (System.nanoTime() < deadline) {
            long c5Nanos = System.nanoTime(), c5WallMs = System.currentTimeMillis();
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/seckill/result/" + item.requestId()))
                    .timeout(Duration.ofSeconds(5)).header("Authorization", "Bearer " + token(item.userId()))
                    .header("X-TokenHub-Poll-Sequence", Integer.toString(sequence)).GET().build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            long c6Nanos = System.nanoTime(), c6WallMs = System.currentTimeMillis();
            String state = businessState(response.body());
            attempts.add(new PollAttempt(item.requestId(), sequence, c5WallMs, c6WallMs,
                    (c6Nanos - c5Nanos) / 1_000_000.0, response.statusCode(), state,
                    previousC5WallMs < 0 ? -1 : c5WallMs - previousC5WallMs));
            previousC5WallMs = c5WallMs;
            if (response.statusCode() == 200 && response.body().contains("SUCCESS:")) {
                c7Nanos = System.nanoTime(); c7WallMs = System.currentTimeMillis(); break;
            }
            sequence++; Thread.sleep(50);
        }
        return new PollResult(item, c3WallMs, c3Nanos, c4WallMs, c4Nanos, c7WallMs, c7Nanos, attempts);
    }

    private static void sampleExecutor(ThreadPoolExecutor executor, AtomicLong rejected, AtomicBoolean active, Path output) {
        try (BufferedWriter out = Files.newBufferedWriter(output, StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING)) {
            out.write("timestamp,pool_size,active_count,queue_size,completed_task_count,largest_pool_size,rejected_count\n");
            while (active.get()) {
                out.write("%s,%d,%d,%d,%d,%d,%d%n".formatted(Instant.now(), executor.getPoolSize(), executor.getActiveCount(),
                        executor.getQueue().size(), executor.getCompletedTaskCount(), executor.getLargestPoolSize(), rejected.get()));
                out.flush(); Thread.sleep(100);
            }
        } catch (Exception ignored) { }
    }

    private static void writeClientTrace(Path output, List<PollResult> results) throws Exception {
        try (BufferedWriter out = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
            out.write("request_id,user_id,c0_wall_ms,c1_minus_c0_ms,c2_minus_c1_ms,c2_wall_ms,c3_wall_ms,c4_minus_c3_ms,c7_wall_ms,c7_minus_c6_ms,post_response_to_first_poll_ms,poll_count,success\n");
            for (PollResult result : results) {
                Accepted a = result.accepted(); PollAttempt first = result.attempts().isEmpty() ? null : result.attempts().get(0);
                PollAttempt last = result.attempts().isEmpty() ? null : result.attempts().get(result.attempts().size() - 1);
                double c7MinusC6 = result.c7WallMs() < 0 || last == null ? -1 : result.c7WallMs() - last.c6WallMs();
                out.write("%s,%d,%d,%.3f,%.3f,%d,%d,%.3f,%d,%.3f,%.3f,%d,%s%n".formatted(
                        a.requestId(), a.userId(), a.c0WallMs(), (a.c1Nanos() - a.c0Nanos()) / 1_000_000.0,
                        (a.c2Nanos() - a.c1Nanos()) / 1_000_000.0, a.c2WallMs(), result.c3WallMs(),
                        (result.c4Nanos() - result.c3Nanos()) / 1_000_000.0, result.c7WallMs(), c7MinusC6,
                        first == null ? -1.0 : (double) (first.c5WallMs() - a.c2WallMs()), result.attempts().size(), result.c7WallMs() >= 0));
            }
        }
    }

    private static void writePollTrace(Path output, List<PollResult> results) throws Exception {
        try (BufferedWriter out = Files.newBufferedWriter(output, StandardCharsets.UTF_8)) {
            out.write("request_id,poll_sequence,c5_wall_ms,c6_wall_ms,http_latency_ms,status_code,business_state,interval_since_previous_c5_ms\n");
            for (PollResult result : results) for (PollAttempt attempt : result.attempts()) {
                out.write("%s,%d,%d,%d,%.3f,%d,%s,%d%n".formatted(attempt.requestId(), attempt.sequence(), attempt.c5WallMs(),
                        attempt.c6WallMs(), attempt.httpLatencyMs(), attempt.statusCode(), attempt.businessState(), attempt.intervalSincePreviousC5Ms()));
            }
        }
    }

    private static String requestId(String json) { String marker = "\"requestId\":\""; int begin = json.indexOf(marker); if (begin < 0) return null; begin += marker.length(); int end = json.indexOf('"', begin); return end < 0 ? null : json.substring(begin, end); }
    private static String businessState(String json) { int marker = json.indexOf("\"result\":\""); if (marker < 0) return "UNKNOWN"; int begin = marker + 10; int end = json.indexOf('"', begin); return end < 0 ? "UNKNOWN" : json.substring(begin, end); }
    private static double percentile(List<Long> samples, double p) { if (samples.isEmpty()) return 0; int index = Math.max(0, (int) Math.ceil(samples.size() * p) - 1); return samples.get(index) / 1000.0; }
    private static double percentileMillis(List<Long> samples, double p) { if (samples.isEmpty()) return 0; int index = Math.max(0, (int) Math.ceil(samples.size() * p) - 1); return samples.get(index); }
    private static String token(long userId) throws GeneralSecurityException { String header = B64.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8)); long now = Instant.now().getEpochSecond(); String payload = B64.encodeToString(("{\"sub\":\"" + userId + "\",\"username\":\"bench-" + userId + "\",\"iat\":" + now + ",\"exp\":" + (now + 3600) + "}").getBytes(StandardCharsets.UTF_8)); Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256")); return header + "." + payload + "." + B64.encodeToString(mac.doFinal((header + "." + payload).getBytes(StandardCharsets.UTF_8))); }
}
