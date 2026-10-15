package com.tokenhub.service;

import com.tokenhub.common.BusinessException;
import com.tokenhub.entity.SeckillActivity;
import com.tokenhub.mapper.LocalMessageMapper;
import com.tokenhub.mapper.SeckillActivityMapper;
import com.tokenhub.security.CurrentUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.time.LocalDateTime;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SeckillServiceTest {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final SeckillActivityMapper activities = mock(SeckillActivityMapper.class);
    private final SeckillService service = new SeckillService(redis, activities);
    @AfterEach void clearUser() { CurrentUser.clear(); }

    @Test void successfulLuaReservationReturnsUniqueQueuingRequestWithoutDatabaseLookup() {
        CurrentUser.set(8L); when(redis.execute(any(), anyList(), any(), any(), any())).thenReturn(0L);
        var response = service.reserve(15L);
        assertEquals("QUEUING", response.status()); assertEquals(32, response.requestId().length());
        verifyNoInteractions(activities);
    }

    @Test void explicitStockReservationMethodKeepsQuotaFreezingSeparate() {
        CurrentUser.set(8L); when(redis.execute(any(), anyList(), any(), any(), any())).thenReturn(0L);
        var response = service.reserveSeckillStock(15L);
        assertEquals("QUEUING", response.status());
        verify(redis).execute(any(), anyList(), eq("8"), eq(response.requestId()), any());
    }

    @Test void luaBusinessResultsMapToStableSeckillErrors() {
        CurrentUser.set(8L); when(redis.execute(any(), anyList(), any(), any(), any())).thenReturn(5L);
        assertEquals("SECKILL_DUPLICATE", assertThrows(BusinessException.class, () -> service.reserve(15L)).code());
        when(redis.execute(any(), anyList(), any(), any(), any())).thenReturn(4L);
        assertEquals("SECKILL_OUT_OF_STOCK", assertThrows(BusinessException.class, () -> service.reserve(15L)).code());
    }

    @Test void redisFailureFailsFastWithoutMySqlFallback() {
        CurrentUser.set(8L); when(redis.execute(any(), anyList(), any(), any(), any())).thenThrow(new RuntimeException("redis down"));
        assertEquals("SECKILL_UNAVAILABLE", assertThrows(BusinessException.class, () -> service.reserve(15L)).code());
        verifyNoInteractions(activities);
    }

    @Test void outboxInsertFailureImmediatelyCompensatesReservedRedisStock() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class, RETURNS_DEEP_STUBS);
        LocalMessageMapper messages = mock(LocalMessageMapper.class);
        SeckillService service = new SeckillService(redis, activities, messages, new com.fasterxml.jackson.databind.ObjectMapper());
        CurrentUser.set(8L);
        when(redis.execute(any(), anyList(), any(), any(), any())).thenReturn(0L);
        when(redis.opsForHash().get(anyString(), eq("packageId"))).thenReturn("23");
        when(messages.insert(any())).thenReturn(0);

        BusinessException error = assertThrows(BusinessException.class, () -> service.reserve(15L));

        assertEquals("SECKILL_UNAVAILABLE", error.code());
        verify(redis).execute(any(), org.mockito.ArgumentMatchers.<java.util.List<String>>argThat(keys -> keys.size() == 3
                && keys.get(0).startsWith("seckill:reservation:")
                && keys.get(1).equals("seckill:stock:15")
                && keys.get(2).startsWith("seckill:release:")));
    }

    @Test void preheatRejectsInvalidActivityBeforeWritingRedis() {
        SeckillActivity activity = new SeckillActivity(); activity.setId(1L); activity.setStatus(1); activity.setStock(1L); activity.setStartTime(LocalDateTime.now()); activity.setEndTime(LocalDateTime.now());
        when(activities.findById(1L)).thenReturn(activity);
        assertEquals("SECKILL_ACTIVITY_INVALID", assertThrows(BusinessException.class, () -> service.preheat(1L)).code());
        verifyNoInteractions(redis);
    }
}
