package com.tokenhub.service;

import com.tokenhub.mapper.*;
import com.tokenhub.messaging.SeckillOrderMessage;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import static org.mockito.Mockito.*;

class SeckillOrderServiceTest {
    @Test void releasedReservationCannotCreateLateOrder() {
        StringRedisTemplate redis=mock(StringRedisTemplate.class,RETURNS_DEEP_STUBS); OrderMapper orders=mock(OrderMapper.class); SeckillActivityMapper activities=mock(SeckillActivityMapper.class); TokenPackageMapper packages=mock(TokenPackageMapper.class);
        when(redis.opsForHash().get("seckill:reservation:req1","status")).thenReturn("RELEASED");
        new SeckillOrderService(redis,orders,activities,packages).create(new SeckillOrderMessage("req1",7L,2L,3L));
        verifyNoInteractions(orders,activities,packages);
    }
}
