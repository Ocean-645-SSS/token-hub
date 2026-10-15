package com.tokenhub.messaging;

import com.tokenhub.config.MessagingProperties;
import com.tokenhub.entity.LocalMessage;
import com.tokenhub.mapper.LocalMessageMapper;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LocalMessagePublisherTest {
    @Test
    void acknowledgedConfirmMarksOnlyClaimedMessageAsSent() throws Exception {
        LocalMessageMapper messages = mock(LocalMessageMapper.class);
        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class, RETURNS_DEEP_STUBS);
        LocalMessage message = message(7L);
        when(messages.findPending(10)).thenReturn(List.of(message));
        when(messages.claim(7L)).thenReturn(1);
        when(messages.markSentClaimedById(7L)).thenReturn(1);
        AtomicReference<CorrelationData> correlation = new AtomicReference<>();
        doAnswer(invocation -> { correlation.set(invocation.getArgument(4)); return null; })
                .when(rabbit).convertAndSend(anyString(), anyString(), any(Object.class), any(org.springframework.amqp.core.MessagePostProcessor.class), any(CorrelationData.class));
        ThreadPoolTaskExecutor executor = executor(1, 1, 10);
        try {
            LocalMessagePublisher publisher = new LocalMessagePublisher(messages, rabbit, redis, properties(), executor);

            publisher.publishPending();
            awaitValue(correlation);
            correlation.get().getFuture().complete(new CorrelationData.Confirm(true, null));

            verify(messages).recoverExpiredClaims(30, 5);
            verify(messages).claim(7L);
            verify(messages).markSentClaimedById(7L);
            verify(messages, never()).recordClaimedRetry(anyLong(), anyInt());
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void rejectedConfirmSchedulesClaimedMessageForRetry() throws Exception {
        LocalMessageMapper messages = mock(LocalMessageMapper.class);
        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class, RETURNS_DEEP_STUBS);
        LocalMessage message = message(8L);
        when(messages.findPending(10)).thenReturn(List.of(message));
        when(messages.claim(8L)).thenReturn(1);
        when(messages.recordClaimedRetry(8L, 5)).thenReturn(1);
        AtomicReference<CorrelationData> correlation = new AtomicReference<>();
        doAnswer(invocation -> { correlation.set(invocation.getArgument(4)); return null; })
                .when(rabbit).convertAndSend(anyString(), anyString(), any(Object.class), any(org.springframework.amqp.core.MessagePostProcessor.class), any(CorrelationData.class));
        ThreadPoolTaskExecutor executor = executor(1, 1, 10);
        try {
            LocalMessagePublisher publisher = new LocalMessagePublisher(messages, rabbit, redis, properties(), executor);

            publisher.publishPending();
            awaitValue(correlation);
            correlation.get().getFuture().complete(new CorrelationData.Confirm(false, "nack"));

            verify(messages).recordClaimedRetry(8L, 5);
            verify(messages, never()).markSentClaimedById(anyLong());
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void rowLostToAnotherPublisherIsNotSent() {
        LocalMessageMapper messages = mock(LocalMessageMapper.class);
        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class, RETURNS_DEEP_STUBS);
        when(messages.findPending(10)).thenReturn(List.of(message(9L)));
        when(messages.claim(9L)).thenReturn(0);
        ThreadPoolTaskExecutor executor = executor(1, 1, 10);
        try {
            new LocalMessagePublisher(messages, rabbit, redis, properties(), executor).publishPending();
            verifyNoInteractions(rabbit);
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void schedulerDoesNotClaimWhenExecutorHasNoCapacity() throws Exception {
        LocalMessageMapper messages = mock(LocalMessageMapper.class);
        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class, RETURNS_DEEP_STUBS);
        CountDownLatch release = new CountDownLatch(1);
        ThreadPoolTaskExecutor executor = executor(1, 1, 0);
        executor.execute(() -> await(release));
        try {
            new LocalMessagePublisher(messages, rabbit, redis, properties(), executor).publishPending();

            verify(messages, never()).findPending(anyInt());
            verify(messages, never()).claim(anyLong());
            assertEquals(1, executor.getActiveCount());
        } finally {
            release.countDown();
            executor.shutdown();
        }
    }

    @Test
    void workerSendsOffSchedulerThread() throws Exception {
        LocalMessageMapper messages = mock(LocalMessageMapper.class);
        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        StringRedisTemplate redis = mock(StringRedisTemplate.class, RETURNS_DEEP_STUBS);
        LocalMessage message = message(10L);
        when(messages.findPending(10)).thenReturn(List.of(message));
        when(messages.claim(10L)).thenReturn(1);
        AtomicReference<String> senderThread = new AtomicReference<>();
        AtomicReference<CorrelationData> correlation = new AtomicReference<>();
        doAnswer(invocation -> {
            senderThread.set(Thread.currentThread().getName());
            correlation.set(invocation.getArgument(4));
            return null;
        }).when(rabbit).convertAndSend(anyString(), anyString(), any(Object.class), any(org.springframework.amqp.core.MessagePostProcessor.class), any(CorrelationData.class));
        ThreadPoolTaskExecutor executor = executor(1, 1, 10);
        try {
            new LocalMessagePublisher(messages, rabbit, redis, properties(), executor).publishPending();
            awaitValue(correlation);
            assertNotNull(senderThread.get());
            assertEquals(true, senderThread.get().startsWith("publisher-"));
            correlation.get().getFuture().complete(new CorrelationData.Confirm(true, null));
        } finally {
            executor.shutdown();
        }
    }

    private static MessagingProperties properties() {
        return new MessagingProperties(200, 10, 10, 30, 5, 1, 1, 10);
    }

    private static ThreadPoolTaskExecutor executor(int core, int max, int queue) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(core);
        executor.setMaxPoolSize(max);
        executor.setQueueCapacity(queue);
        executor.setThreadNamePrefix("publisher-");
        executor.initialize();
        return executor;
    }

    private static void awaitValue(AtomicReference<?> value) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (value.get() == null && System.nanoTime() < deadline) Thread.sleep(5);
        assertNotNull(value.get());
    }

    private static void await(CountDownLatch latch) {
        try { latch.await(2, TimeUnit.SECONDS); }
        catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
    }

    private static LocalMessage message(long id) {
        LocalMessage message = new LocalMessage();
        message.setId(id);
        message.setMessageId("msg" + id);
        message.setEventType("TOKEN_GRANT");
        message.setTopic("token.grant");
        message.setPayload("{}");
        return message;
    }
}
