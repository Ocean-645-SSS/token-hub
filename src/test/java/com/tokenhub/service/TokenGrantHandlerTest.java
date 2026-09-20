package com.tokenhub.service;

import com.tokenhub.common.OrderStatus;
import com.tokenhub.entity.Order;
import com.tokenhub.entity.TokenPackage;
import com.tokenhub.entity.TokenWallet;
import com.tokenhub.mapper.*;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TokenGrantHandlerTest {
    @Test void paidOrderIsGrantedOnceAndDuplicateDeliveryDoesNotAddBalanceAgain() {
        OrderMapper orders=mock(OrderMapper.class); TokenPackageMapper packages=mock(TokenPackageMapper.class); TokenWalletMapper wallets=mock(TokenWalletMapper.class); TokenLedgerMapper ledgers=mock(TokenLedgerMapper.class); LocalMessageMapper messages=mock(LocalMessageMapper.class);
        TokenGrantHandler service=new TokenGrantHandler(orders,packages,wallets,ledgers,messages);
        Order paid=order(OrderStatus.PAID); Order granting=order(OrderStatus.GRANTING); Order granted=order(OrderStatus.GRANTED); Order completed=order(OrderStatus.COMPLETED);
        when(orders.findByOrderNo("ORD1")).thenReturn(paid,granting,granted,completed);
        when(orders.transition(9L,OrderStatus.PAID,OrderStatus.GRANTING)).thenReturn(1); when(orders.transition(9L,OrderStatus.GRANTING,OrderStatus.GRANTED)).thenReturn(1); when(orders.complete(9L,OrderStatus.GRANTED,OrderStatus.COMPLETED)).thenReturn(1);
        TokenPackage tokenPackage=new TokenPackage(); tokenPackage.setModelCode("gpt-4o-mini"); when(packages.findById(3L)).thenReturn(tokenPackage);
        TokenWallet wallet=new TokenWallet(); wallet.setId(4L); wallet.setAvailableToken(100L); when(wallets.lockByUserAndModel(7L,"gpt-4o-mini")).thenReturn(wallet); when(ledgers.insertIgnore(any())).thenReturn(1);
        service.handle("ORD1"); service.handle("ORD1");
        verify(wallets,times(1)).addAvailable(4L,1000L); verify(ledgers,times(1)).insertIgnore(argThat(l->l.getBizId().equals("TOKEN_GRANT_ORDER_ORD1"))); verify(messages,times(1)).markSent("TOKEN_GRANT","ORD1");
    }
    private static Order order(OrderStatus status){Order o=new Order();o.setId(9L);o.setOrderNo("ORD1");o.setUserId(7L);o.setPackageId(3L);o.setTokenAmount(1000L);o.setStatus(status);return o;}
}
