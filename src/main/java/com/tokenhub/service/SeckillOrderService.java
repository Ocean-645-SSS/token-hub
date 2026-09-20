package com.tokenhub.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokenhub.common.OrderStatus;
import com.tokenhub.common.OrderType;
import com.tokenhub.entity.Order;
import com.tokenhub.entity.SeckillActivity;
import com.tokenhub.entity.TokenPackage;
import com.tokenhub.mapper.OrderMapper;
import com.tokenhub.mapper.SeckillActivityMapper;
import com.tokenhub.mapper.TokenPackageMapper;
import com.tokenhub.messaging.SeckillOrderMessage;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

@Service public class SeckillOrderService {
    private final StringRedisTemplate redis; private final OrderMapper orders; private final SeckillActivityMapper activities; private final TokenPackageMapper packages;
    public SeckillOrderService(StringRedisTemplate redis, OrderMapper orders, SeckillActivityMapper activities, TokenPackageMapper packages) { this.redis = redis; this.orders = orders; this.activities = activities; this.packages = packages; }
    @Transactional public void create(SeckillOrderMessage message) {
        String key = "seckill:reservation:" + message.requestId(); Object status = redis.opsForHash().get(key, "status");
        if (!"PENDING".equals(status) && !"SENT".equals(status)) return;
        Order existing = orders.findByRequestId(message.requestId());
        if (existing != null) { complete(key, existing.getOrderNo()); return; }
        SeckillActivity activity = activities.findById(message.activityId()); TokenPackage tokenPackage = packages.findById(message.packageId());
        if (activity == null || tokenPackage == null || !activity.getPackageId().equals(message.packageId())) { fail(key); return; }
        Order order = new Order(); order.setOrderNo("ORD" + UUID.randomUUID().toString().replace("-", "").substring(0, 29));
        order.setUserId(message.userId()); order.setPackageId(message.packageId()); order.setOrderType(OrderType.SECKILL); order.setSeckillActivityId(message.activityId()); order.setRequestId(message.requestId());
        order.setOriginalPrice(tokenPackage.getPrice()); order.setPayPrice(activity.getSeckillPrice()); order.setTokenAmount(tokenPackage.getTokenAmount()); order.setStatus(OrderStatus.WAIT_PAY);
        try { orders.insertSeckill(order); complete(key, order.getOrderNo()); }
        catch (RuntimeException exception) { Order raced = orders.findByRequestId(message.requestId()); if (raced != null) complete(key, raced.getOrderNo()); else throw exception; }
    }
    private void complete(String key, String orderNo) { redis.opsForHash().put(key, "status", "ORDER_CREATED"); redis.opsForHash().put(key, "orderNo", orderNo); }
    private void fail(String key) { redis.opsForHash().put(key, "status", "FAILED"); }
}
