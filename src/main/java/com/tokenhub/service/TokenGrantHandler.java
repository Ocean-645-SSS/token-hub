package com.tokenhub.service;

import com.tokenhub.common.LedgerType;
import com.tokenhub.common.OrderStatus;
import com.tokenhub.entity.Order;
import com.tokenhub.entity.TokenLedger;
import com.tokenhub.entity.TokenPackage;
import com.tokenhub.entity.TokenWallet;
import com.tokenhub.mapper.LocalMessageMapper;
import com.tokenhub.mapper.OrderMapper;
import com.tokenhub.mapper.TokenLedgerMapper;
import com.tokenhub.mapper.TokenPackageMapper;
import com.tokenhub.mapper.TokenWalletMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service public class TokenGrantHandler {
    private final OrderMapper orders; private final TokenPackageMapper packages; private final TokenWalletMapper wallets;
    private final TokenLedgerMapper ledgers; private final LocalMessageMapper messages;
    public TokenGrantHandler(OrderMapper orders, TokenPackageMapper packages, TokenWalletMapper wallets, TokenLedgerMapper ledgers, LocalMessageMapper messages) {
        this.orders = orders; this.packages = packages; this.wallets = wallets; this.ledgers = ledgers; this.messages = messages;
    }
    @Transactional public void handle(String orderNo) {
        Order order = orders.findByOrderNo(orderNo);
        if (order == null) return;
        if (order.getStatus() == OrderStatus.COMPLETED) { messages.markSent("TOKEN_GRANT", orderNo); return; }
        if (order.getStatus() == OrderStatus.PAID && orders.transition(order.getId(), OrderStatus.PAID, OrderStatus.GRANTING) != 1) return;
        order = orders.findByOrderNo(orderNo);
        if (order.getStatus() != OrderStatus.GRANTING && order.getStatus() != OrderStatus.GRANTED) return;
        if (order.getStatus() == OrderStatus.GRANTING) {
            TokenPackage tokenPackage = packages.findById(order.getPackageId());
            if (tokenPackage == null) throw new IllegalStateException("purchased package is missing");
            wallets.createIfAbsent(order.getUserId(), tokenPackage.getModelCode());
            TokenWallet wallet = wallets.lockByUserAndModel(order.getUserId(), tokenPackage.getModelCode());
            long before = wallet.getAvailableToken();
            TokenLedger ledger = new TokenLedger();
            ledger.setUserId(order.getUserId()); ledger.setModelCode(tokenPackage.getModelCode());
            ledger.setBizId("TOKEN_GRANT_ORDER_" + orderNo); ledger.setType(LedgerType.PURCHASE);
            ledger.setAmount(order.getTokenAmount()); ledger.setBalanceBefore(before); ledger.setBalanceAfter(before + order.getTokenAmount());
            ledger.setRemark("grant for order " + orderNo);
            if (ledgers.insertIgnore(ledger) == 1) wallets.addAvailable(wallet.getId(), order.getTokenAmount());
            if (orders.transition(order.getId(), OrderStatus.GRANTING, OrderStatus.GRANTED) != 1) return;
        }
        if (orders.complete(order.getId(), OrderStatus.GRANTED, OrderStatus.COMPLETED) == 1) messages.markSent("TOKEN_GRANT", orderNo);
    }
}
