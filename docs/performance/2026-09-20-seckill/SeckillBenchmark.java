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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Standalone, dependency-free HTTP load generator for the existing reservation
 * endpoint. It deliberately uses a unique signed synthetic identity for every
 * request, so Lua's one-user-one-order guard does not turn load into duplicate
 * rejections. It never calls application internals or changes business data.
 */
public final class SeckillBenchmark {
    private static final String SECRET = "tokenhub-v1-development-secret-change-before-production-20260919";
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private record Accepted(String requestId, long userId, long startedEpochMillis) {}
    private record WorkerResult(List<Long> latencyMicros, long total, long accepted, long failed, long maxMicros, List<Accepted> captured) {}

    public static void main(String[] args) throws Exception {
        if (args.length < 6 || args.length > 7) {
            throw new IllegalArgumentException("usage: <baseUrl> <activityId> <concurrency> <seconds> <firstUserId> <summaryCsv> [captureCsv]");
        }
        String baseUrl = args[0], activityId = args[1];
        int concurrency = Integer.parseInt(args[2]), seconds = Integer.parseInt(args[3]);
        AtomicLong users = new AtomicLong(Long.parseLong(args[4]));
        Path summary = Path.of(args[5]); Path capture = args.length == 7 ? Path.of(args[6]) : null;
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        // Do not share the worker pool with HttpClient's transport tasks: doing
        // so can deadlock synchronous send() when every worker is occupied.
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
                Instant.now(), concurrency, total, accepted, failed, qps, avg, percentile(samples, .50), percentile(samples, .95), percentile(samples, .99), max / 1000.0, total == 0 ? 0 : failed * 100.0 / total);
        boolean exists = Files.exists(summary); Files.createDirectories(summary.getParent());
        try (BufferedWriter out = Files.newBufferedWriter(summary, StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)) {
            if (!exists) out.write("timestamp,concurrency,total,accepted,failed,qps,avg_ms,p50_ms,p95_ms,p99_ms,max_ms,error_rate_pct\n");
            out.write(row);
        }
        if (capture != null) {
            try (BufferedWriter out = Files.newBufferedWriter(capture, StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING)) {
                out.write("request_id,user_id,started_epoch_ms\n");
                for (Accepted item : captures) out.write(item.requestId() + "," + item.userId() + "," + item.startedEpochMillis() + "\n");
            }
            pollCompletions(baseUrl, captures, capture.resolveSibling(capture.getFileName() + ".e2e.csv"));
        }
        System.out.print(row);
    }

    private static WorkerResult worker(HttpClient client, String baseUrl, String activityId, AtomicLong users, AtomicLong deadlineNano, CountDownLatch ready, CountDownLatch start, boolean capture) {
        List<Long> latency = new ArrayList<>(); List<Accepted> captured = new ArrayList<>(); long total = 0, accepted = 0, failed = 0, max = 0;
        long user = users.getAndIncrement(); String bearer;
        try {
            bearer = token(user);
            client.send(HttpRequest.newBuilder(URI.create(baseUrl + "/api/packages")).timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.discarding());
        } catch (Exception exception) { ready.countDown(); return new WorkerResult(latency, 0, 0, 0, 0, captured); }
        ready.countDown(); try { start.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return new WorkerResult(latency, 0, 0, 0, 0, captured); }
        while (System.nanoTime() < deadlineNano.get()) {
            long before = System.nanoTime(), startedMs = System.currentTimeMillis();
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/seckill/" + activityId))
                        .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer " + bearer)
                        .POST(HttpRequest.BodyPublishers.noBody()).build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                long micros = TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - before); latency.add(micros); max = Math.max(max, micros); total++;
                String requestId = requestId(response.body());
                if (response.statusCode() == 200 && requestId != null) { accepted++; if (capture) captured.add(new Accepted(requestId, user, startedMs)); }
                else failed++;
            } catch (Exception e) {
                long micros = TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - before); latency.add(micros); max = Math.max(max, micros); total++; failed++;
            } finally { user = users.getAndIncrement(); try { bearer = token(user); } catch (GeneralSecurityException exception) { throw new IllegalStateException(exception); } }
        }
        return new WorkerResult(latency, total, accepted, failed, max, captured);
    }

    private static String requestId(String json) {
        String marker = "\"requestId\":\""; int begin = json.indexOf(marker); if (begin < 0) return null;
        begin += marker.length(); int end = json.indexOf('"', begin); return end < 0 ? null : json.substring(begin, end);
    }
    private static void pollCompletions(String baseUrl, List<Accepted> accepted, Path output) throws Exception {
        ExecutorService pollers = Executors.newFixedThreadPool(Math.min(accepted.size(), 100));
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        List<Future<Long>> futures = new ArrayList<>();
        for (Accepted item : accepted) futures.add(pollers.submit(() -> {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (System.nanoTime() < deadline) {
                HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/seckill/result/" + item.requestId())).timeout(Duration.ofSeconds(5)).header("Authorization", "Bearer " + token(item.userId())).GET().build();
                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200 && response.body().contains("SUCCESS:")) return System.currentTimeMillis() - item.startedEpochMillis();
                Thread.sleep(50);
            }
            return -1L;
        }));
        List<Long> elapsed = new ArrayList<>(); long timedOut = 0;
        for (Future<Long> future : futures) { long value = future.get(35, TimeUnit.SECONDS); if (value >= 0) elapsed.add(value); else timedOut++; }
        pollers.shutdownNow(); elapsed.sort(Comparator.naturalOrder());
        boolean exists = Files.exists(output); try (BufferedWriter out = Files.newBufferedWriter(output, StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND)) {
            if (!exists) out.write("accepted,completed,timed_out,avg_ms,p50_ms,p95_ms,p99_ms,max_ms\n");
            double average = elapsed.stream().mapToLong(Long::longValue).average().orElse(0);
            long max = elapsed.stream().mapToLong(Long::longValue).max().orElse(0);
            String row = "%d,%d,%d,%.3f,%.3f,%.3f,%.3f,%d%n".formatted(accepted.size(), elapsed.size(), timedOut, average, percentileMillis(elapsed, .50), percentileMillis(elapsed, .95), percentileMillis(elapsed, .99), max);
            out.write(row); System.out.print("E2E," + row);
        }
    }
    private static double percentile(List<Long> samples, double p) { if (samples.isEmpty()) return 0; int index = Math.max(0, (int) Math.ceil(samples.size() * p) - 1); return samples.get(index) / 1000.0; }
    private static double percentileMillis(List<Long> samples, double p) { if (samples.isEmpty()) return 0; int index = Math.max(0, (int) Math.ceil(samples.size() * p) - 1); return samples.get(index); }
    private static String token(long userId) throws GeneralSecurityException {
        String header = B64.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        long now = Instant.now().getEpochSecond();
        String payload = B64.encodeToString(("{\"sub\":\"" + userId + "\",\"username\":\"bench-" + userId + "\",\"iat\":" + now + ",\"exp\":" + (now + 3600) + "}").getBytes(StandardCharsets.UTF_8));
        Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return header + "." + payload + "." + B64.encodeToString(mac.doFinal((header + "." + payload).getBytes(StandardCharsets.UTF_8)));
    }
}
