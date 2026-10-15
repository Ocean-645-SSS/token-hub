package com.tokenhub.config;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class RabbitConsumerConfigTest {
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
