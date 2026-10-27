package com.tokenhub.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokenhub.service.SeckillInventoryProjection;
import com.tokenhub.service.SeckillReservationService;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SeckillRecoveryConsumerTest {
    private final SeckillReservationService recovery = mock(SeckillReservationService.class);
    private final SeckillInventoryProjection projection = mock(SeckillInventoryProjection.class);
    private final SeckillRecoveryConsumer consumer = new SeckillRecoveryConsumer(new ObjectMapper(), recovery, projection);

    @Test void deadLetterSchedulesCompensationByOriginalRequestId() throws Exception {
        consumer.failed(new ObjectMapper().writeValueAsString(new SeckillOrderMessage("r1", 7L, 2L, 3L)));
        verify(recovery).compensate("r1");
    }
    @Test void dbFailureIsNotSwallowedSoBrokerCanRedeliver() {
        doThrow(new IllegalStateException("database unavailable")).when(recovery).compensate("r1");
        assertThrows(IllegalStateException.class, () -> consumer.failed("{\"requestId\":\"r1\"}"));
    }
    @Test void releaseAndProjectionRetryTheirOwnDurableEffects() {
        consumer.release("r1"); consumer.synchronize("2");
        verify(recovery).releaseReservation("r1"); verify(projection).synchronize(2L);
    }
}
