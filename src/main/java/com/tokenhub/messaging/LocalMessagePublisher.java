package com.tokenhub.messaging;

import com.tokenhub.config.MessagingProperties;
import com.tokenhub.config.RabbitMqConfig;
import com.tokenhub.entity.LocalMessage;
import com.tokenhub.mapper.LocalMessageMapper;
import com.tokenhub.profiling.SeckillTrace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.LongAdder;

@Component
public class LocalMessagePublisher {
    private static final Logger log = LoggerFactory.getLogger(LocalMessagePublisher.class);
    private static final DefaultRedisScript<Long> MARK_RESERVATION_SENT = markReservationSentScript();

    private final LocalMessageMapper messages;
    private final RabbitTemplate rabbit;
    private final StringRedisTemplate redis;
    private final MessagingProperties properties;
    private final ThreadPoolTaskExecutor publisherExecutor;
    private final Semaphore inFlight;
    private final LongAdder published = new LongAdder();
    private final LongAdder dispatched = new LongAdder();
    private final LongAdder confirmed = new LongAdder();
    private final LongAdder failed = new LongAdder();
    private final LongAdder retried = new LongAdder();
    private final LongAdder rejected = new LongAdder();
    private final LongAdder queueWaitNanos = new LongAdder();
    private final LongAdder workerStarts = new LongAdder();
    private long previousCycleEndNanos;
    private long cycleSequence;

    public LocalMessagePublisher(LocalMessageMapper messages, RabbitTemplate rabbit, StringRedisTemplate redis,
                                 MessagingProperties properties, ThreadPoolTaskExecutor publisherExecutor) {
        this.messages = messages;
        this.rabbit = rabbit;
        this.redis = redis;
        this.properties = properties;
        this.publisherExecutor = publisherExecutor;
        this.inFlight = new Semaphore(properties.maxInFlight());
    }

    @Scheduled(fixedDelayString = "${tokenhub.messaging.poll-interval-ms:200}")
    public void publishPending() {
        long cycleStartNanos = System.nanoTime();
        long expectedStartNanos = previousCycleEndNanos == 0 ? 0 : previousCycleEndNanos + properties.pollIntervalMs() * 1_000_000L;
        int selectedCount = 0;
        int claimedCount = 0;
        int dispatchedCount = 0;
        int rejectedCount = 0;
        int recovered = messages.recoverExpiredClaims(properties.claimTimeoutSeconds(), properties.retryDelaySeconds());
        if (recovered > 0) {
            retried.add(recovered);
            log.warn("recovered {} expired outbox claims", recovered);
        }

        int capacity = dispatchCapacity();
        long pendingQueryStartNanos = System.nanoTime();
        List<LocalMessage> pending = capacity == 0 ? List.of() : messages.findPending(Math.min(properties.batchSize(), capacity));
        long pendingQueryEndNanos = System.nanoTime();
        selectedCount = pending.size();
        for (LocalMessage message : pending) {
            if ("SECKILL_ORDER".equals(message.getEventType())) {
                SeckillTrace.markAt(message.getBizId(), "p1", cycleStartNanos);
            }
            if (!inFlight.tryAcquire()) break;
            boolean claimed = !"stock.release".equals(message.getTopic());
            if (claimed) {
                long claimStartNanos = System.nanoTime();
                int claimResult = messages.claim(message.getId());
                if ("SECKILL_ORDER".equals(message.getEventType())) {
                    SeckillTrace.markAt(message.getBizId(), "p5", claimStartNanos);
                    SeckillTrace.markAt(message.getBizId(), "p6", System.nanoTime());
                }
                if (claimResult != 1) {
                    inFlight.release();
                    continue;
                }
                claimedCount++;
            }

            long dispatchNanos = System.nanoTime();
            if ("SECKILL_ORDER".equals(message.getEventType())) {
                SeckillTrace.markAt(message.getBizId(), "p7", dispatchNanos);
            }
            if (dispatch(message, claimed, dispatchNanos)) {
                dispatchedCount++;
            } else {
                rejectedCount++;
            }
        }

        long cycleEndNanos = System.nanoTime();
        previousCycleEndNanos = cycleEndNanos;
        if (SeckillTrace.enabled()) {
            ThreadPoolExecutor executor = publisherExecutor.getThreadPoolExecutor();
            log.info("publisher-profile cycle={} thread={} scheduledExpected={} actualStart={} driftMs={} selected={} claimed={} dispatched={} rejected={} inFlight={} pendingQueryMs={} cycleMs={} executorCore={} executorMax={} executorPool={} executorActive={} executorQueue={} executorRemaining={} executorCompleted={} executorQueueWaitAvgMs={}",
                    ++cycleSequence, Thread.currentThread().getName(), expectedStartNanos == 0 ? "n/a" : expectedStartNanos,
                    cycleStartNanos, expectedStartNanos == 0 ? "n/a" : (cycleStartNanos - expectedStartNanos) / 1_000_000.0,
                    selectedCount, claimedCount, dispatchedCount, rejectedCount,
                    properties.maxInFlight() - inFlight.availablePermits(),
                    (pendingQueryEndNanos - pendingQueryStartNanos) / 1_000_000.0,
                    (cycleEndNanos - cycleStartNanos) / 1_000_000.0,
                    executor.getCorePoolSize(), executor.getMaximumPoolSize(), executor.getPoolSize(), executor.getActiveCount(),
                    executor.getQueue().size(), executor.getQueue().remainingCapacity(), executor.getCompletedTaskCount(), queueWaitAverageMs());
        }
    }

    private boolean dispatch(LocalMessage message, boolean claimed, long dispatchNanos) {
        try {
            publisherExecutor.execute(() -> {
                long workerStartNanos = System.nanoTime();
                workerStarts.increment();
                queueWaitNanos.add(workerStartNanos - dispatchNanos);
                if ("SECKILL_ORDER".equals(message.getEventType())) {
                    SeckillTrace.markAt(message.getBizId(), "p8", workerStartNanos);
                }
                if (claimed) publishClaimed(message);
                else publishStockRelease(message);
            });
            dispatched.increment();
            return true;
        } catch (RejectedExecutionException exception) {
            rejected.increment();
            if (claimed) retry(message, exception);
            else if (messages.recordRetry(message.getId(), properties.retryDelaySeconds()) == 1) retried.increment();
            inFlight.release();
            log.warn("publisher executor rejected messageId={}; retry scheduled", message.getMessageId(), exception);
            return false;
        }
    }

    void publishClaimed(LocalMessage message) {
        try {
            if ("SECKILL_ORDER".equals(message.getEventType())) {
                SeckillTrace.markAt(message.getBizId(), "p9", System.nanoTime());
                SeckillTrace.mark(message.getBizId(), "m0");
            }
            CorrelationData correlation = new CorrelationData(message.getMessageId());
            rabbit.convertAndSend(RabbitMqConfig.EXCHANGE, message.getTopic(), message.getPayload(), outgoing -> {
                outgoing.getMessageProperties().setMessageId(message.getMessageId());
                outgoing.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                return outgoing;
            }, correlation);
            if ("SECKILL_ORDER".equals(message.getEventType())) SeckillTrace.mark(message.getBizId(), "t6");
            published.increment();
            correlation.getFuture().whenComplete((confirm, throwable) -> complete(message, correlation, confirm, throwable));
        } catch (RuntimeException exception) {
            try { retry(message, exception); }
            finally { inFlight.release(); }
        }
    }

    private void publishStockRelease(LocalMessage message) {
        try {
            CorrelationData correlation = new CorrelationData(message.getMessageId());
            rabbit.convertAndSend(RabbitMqConfig.EXCHANGE, message.getTopic(), message.getPayload(), outgoing -> {
                outgoing.getMessageProperties().setMessageId(message.getMessageId());
                outgoing.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                return outgoing;
            }, correlation);
            published.increment();
            correlation.getFuture().whenComplete((confirm, throwable) -> {
                try {
                    if (throwable != null || confirm == null || !confirm.isAck() || correlation.getReturned() != null) {
                        if (correlation.getReturned() != null) {
                            log.warn("outbox message was returned as unroutable messageId={} replyCode={} replyText={}",
                                    message.getMessageId(), correlation.getReturned().getReplyCode(), correlation.getReturned().getReplyText());
                        }
                        if (messages.recordRetry(message.getId(), properties.retryDelaySeconds()) == 1) retried.increment();
                    }
                } finally { inFlight.release(); }
            });
        } catch (RuntimeException exception) {
            try {
                if (messages.recordRetry(message.getId(), properties.retryDelaySeconds()) == 1) retried.increment();
            } finally { inFlight.release(); }
        }
    }

    private void complete(LocalMessage message, CorrelationData correlation, CorrelationData.Confirm confirm, Throwable throwable) {
        try {
            if (throwable == null && confirm != null && confirm.isAck() && correlation.getReturned() == null) {
                if ("SECKILL_ORDER".equals(message.getEventType())) {
                    SeckillTrace.mark(message.getBizId(), "t7");
                    SeckillTrace.mark(message.getBizId(), "m1");
                }
                markReservationSent(message);
                if (messages.markSentClaimedById(message.getId()) == 1) confirmed.increment();
                return;
            }
            if (correlation.getReturned() != null) {
                log.warn("outbox message was returned as unroutable messageId={} replyCode={} replyText={}",
                        message.getMessageId(), correlation.getReturned().getReplyCode(), correlation.getReturned().getReplyText());
            }
            retry(message, throwable == null
                    ? new IllegalStateException("broker rejected message: " + (confirm == null ? "missing confirm" : confirm.getReason()))
                    : throwable);
        } finally { inFlight.release(); }
    }

    private void retry(LocalMessage message, Throwable exception) {
        if (messages.recordClaimedRetry(message.getId(), properties.retryDelaySeconds()) == 1) {
            failed.increment();
            retried.increment();
            log.warn("outbox publish failed for messageId={}; retry scheduled", message.getMessageId(), exception);
        }
    }

    private void markReservationSent(LocalMessage message) {
        if ("SECKILL_ORDER".equals(message.getEventType())) {
            redis.execute(MARK_RESERVATION_SENT, List.of("seckill:reservation:" + message.getBizId()));
        }
    }

    @Scheduled(fixedDelay = 5000)
    public void logStats() {
        ThreadPoolExecutor executor = publisherExecutor.getThreadPoolExecutor();
        log.info("outbox pending={} inFlight={} published={} dispatched={} confirmed={} failed={} retries={} rejected={} executorCore={} executorMax={} executorPool={} executorActive={} executorQueue={} executorRemaining={} executorCompleted={} executorQueueWaitAvgMs={}",
                messages.countPending(), properties.maxInFlight() - inFlight.availablePermits(), published.sum(), dispatched.sum(),
                confirmed.sum(), failed.sum(), retried.sum(), rejected.sum(), executor.getCorePoolSize(), executor.getMaximumPoolSize(),
                executor.getPoolSize(), executor.getActiveCount(), executor.getQueue().size(), executor.getQueue().remainingCapacity(),
                executor.getCompletedTaskCount(), queueWaitAverageMs());
    }

    private int dispatchCapacity() {
        ThreadPoolExecutor executor = publisherExecutor.getThreadPoolExecutor();
        int executorCapacity = executor.getQueue().remainingCapacity()
                + Math.max(0, executor.getMaximumPoolSize() - executor.getPoolSize());
        return Math.min(inFlight.availablePermits(), executorCapacity);
    }

    private double queueWaitAverageMs() {
        long started = workerStarts.sum();
        return started == 0 ? 0.0 : queueWaitNanos.sum() / 1_000_000.0 / started;
    }

    private static DefaultRedisScript<Long> markReservationSentScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("lua/mark_seckill_reservation_sent.lua"));
        script.setResultType(Long.class);
        return script;
    }
}
