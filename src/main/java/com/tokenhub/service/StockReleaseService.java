package com.tokenhub.service;
import com.tokenhub.common.OrderType;
import com.tokenhub.entity.Order;
import com.tokenhub.mapper.OrderMapper;
import org.springframework.stereotype.Service;
@Service public class StockReleaseService {
 private final OrderMapper orders; private final OrderService normal; private final SeckillReservationService recovery;
 public StockReleaseService(OrderMapper orders, OrderService normal, SeckillReservationService recovery) { this.orders=orders;this.normal=normal;this.recovery=recovery; }
 public void release(String orderNo) {
     Order order = orders.findByOrderNo(orderNo);
     if (order == null) return;
     if (order.getOrderType() == OrderType.NORMAL) { normal.releaseStock(orderNo); return; }
     if (order.getStatus() != com.tokenhub.common.OrderStatus.USER_CANCELLED
             && order.getStatus() != com.tokenhub.common.OrderStatus.TIMEOUT_CANCELLED) return;
     recovery.releaseOrder(orderNo);
 }
}
