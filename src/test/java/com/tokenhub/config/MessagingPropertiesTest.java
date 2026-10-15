package com.tokenhub.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MessagingPropertiesTest {
    @Test
    void acceptsBoundedPublisherExecutorConfiguration() {
        MessagingProperties properties = new MessagingProperties(200, 500, 1000, 30, 5, 4, 8, 500);

        assertEquals(4, properties.publisherCoreThreads());
        assertEquals(8, properties.publisherMaxThreads());
        assertEquals(500, properties.publisherQueueCapacity());
    }

    @Test
    void rejectsInvalidPublisherExecutorConfiguration() {
        assertThrows(IllegalArgumentException.class,
                () -> new MessagingProperties(200, 500, 1000, 30, 5, 0, 8, 500));
        assertThrows(IllegalArgumentException.class,
                () -> new MessagingProperties(200, 500, 1000, 30, 5, 8, 4, 500));
        assertThrows(IllegalArgumentException.class,
                () -> new MessagingProperties(200, 500, 1000, 30, 5, 4, 8, 0));
    }
}
