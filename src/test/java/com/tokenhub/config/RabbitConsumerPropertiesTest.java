package com.tokenhub.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RabbitConsumerPropertiesTest {
    @Test
    void acceptsConservativeConsumerDefaults() {
        RabbitConsumerProperties properties = new RabbitConsumerProperties(4, 8, 32);

        assertEquals(4, properties.concurrency());
        assertEquals(8, properties.maxConcurrency());
        assertEquals(32, properties.prefetch());
    }

    @Test
    void rejectsInvalidCapacity() {
        assertThrows(IllegalArgumentException.class, () -> new RabbitConsumerProperties(0, 8, 32));
        assertThrows(IllegalArgumentException.class, () -> new RabbitConsumerProperties(8, 4, 32));
        assertThrows(IllegalArgumentException.class, () -> new RabbitConsumerProperties(4, 8, 0));
    }
}
