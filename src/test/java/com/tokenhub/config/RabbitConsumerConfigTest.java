package com.tokenhub.config;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class RabbitConsumerConfigTest {
    interface Delivery { void onMessage(org.springframework.amqp.core.Message message); }

    @Test void orderFailureStopsAfterThreeAttempts() {
        var factory = new RabbitConsumerConfig().seckillRabbitListenerContainerFactory(
                mock(SimpleRabbitListenerContainerFactoryConfigurer.class), mock(ConnectionFactory.class),
                new RabbitConsumerProperties(1, 1, 1));
        Delivery target = mock(Delivery.class);
        var message = new org.springframework.amqp.core.Message("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        org.mockito.Mockito.doThrow(new IllegalStateException("DB unavailable")).when(target).onMessage(message);
        var proxyFactory = new org.springframework.aop.framework.ProxyFactory(target);
        proxyFactory.addAdvice(((org.aopalliance.aop.Advice[]) field(factory, "adviceChain"))[0]);
        Delivery proxy = (Delivery) proxyFactory.getProxy();
        org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class, () -> proxy.onMessage(message));
        org.mockito.Mockito.verify(target, org.mockito.Mockito.times(3)).onMessage(message);
    }
    @Test
    void appliesConsumerCapacityToDedicatedFactory() {
        RabbitConsumerProperties properties = new RabbitConsumerProperties(4, 8, 32);
        SimpleRabbitListenerContainerFactory factory = new RabbitConsumerConfig()
                .seckillRabbitListenerContainerFactory(
                        mock(SimpleRabbitListenerContainerFactoryConfigurer.class),
                        mock(ConnectionFactory.class), properties);

        assertEquals(4, field(factory, "concurrentConsumers"));
        assertEquals(8, field(factory, "maxConcurrentConsumers"));
        assertEquals(32, field(factory, "prefetchCount"));
        assertEquals(false, field(factory, "defaultRequeueRejected"));
        assertEquals(1, ((org.aopalliance.aop.Advice[]) field(factory, "adviceChain")).length);
    }

    private static Object field(Object target, String name) {
        try {
            Class<?> type = target.getClass();
            Field field = null;
            while (type != null && field == null) {
                try {
                    field = type.getDeclaredField(name);
                } catch (NoSuchFieldException ignored) {
                    type = type.getSuperclass();
                }
            }
            if (field == null) throw new NoSuchFieldException(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }
}
