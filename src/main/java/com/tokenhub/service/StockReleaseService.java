package com.tokenhub.service;
import com.tokenhub.common.OrderType;
import com.tokenhub.entity.Order;
import com.tokenhub.mapper.LocalMessageMapper;
import com.tokenhub.mapper.OrderMapper;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import java.util.List;
@Service public class StockReleaseService {
 private static final DefaultRedisScript<Long> SCRIPT = script(); private final OrderMapper orders; private final LocalMessageMapper messages; private final StringRedisTemplate redis; private final OrderService normal;
 public StockReleaseService(OrderMapper orders, LocalMessageMapper messages, StringRedisTemplate redis, OrderService normal) { this.orders=orders;this.messages=messages;this.redis=redis;this.normal=normal; }
 public void release(String orderNo) { Order order=orders.findByOrderNo(orderNo); if(order==null)return; if(order.getOrderType()==OrderType.NORMAL){normal.processStockRelease(orderNo);return;} if(messages.markSent("SECKILL_STOCK_RELEASE",orderNo)!=1)return; redis.execute(SCRIPT,List.of("release:"+orderNo,"seckill:stock:"+order.getSeckillActivityId())); }
 private static DefaultRedisScript<Long> script(){DefaultRedisScript<Long>s=new DefaultRedisScript<>();s.setLocation(new ClassPathResource("lua/release_seckill_stock.lua"));s.setResultType(Long.class);return s;}
}
