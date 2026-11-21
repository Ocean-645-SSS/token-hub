package com.tokenhub.service;

import com.tokenhub.common.BusinessException;
import com.tokenhub.common.LedgerType;
import com.tokenhub.common.ModelRequestStatus;
import com.tokenhub.common.ProviderCostStatus;
import com.tokenhub.config.BillingProperties;
import com.tokenhub.entity.ModelRequest;
import com.tokenhub.entity.TokenLedger;
import com.tokenhub.entity.TokenWallet;
import com.tokenhub.mapper.ModelRequestMapper;
import com.tokenhub.mapper.TokenLedgerMapper;
import com.tokenhub.mapper.TokenWalletMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TokenReservationService {
    private final ModelRequestMapper requests;
    private final TokenWalletMapper wallets;
    private final TokenLedgerMapper ledgers;
    private final BillingProperties billing;

    public TokenReservationService(ModelRequestMapper requests, TokenWalletMapper wallets,
                                   TokenLedgerMapper ledgers, BillingProperties billing) {
        this.requests = requests;
        this.wallets = wallets;
        this.ledgers = ledgers;
        this.billing = billing;
    }

    @Transactional
    public void freeze(ModelRequest request) {
        requests.insert(request);
        if (wallets.findByUserAndModel(request.getUserId(), request.getModelCode()) == null) {
            wallets.createIfAbsent(request.getUserId(), request.getModelCode());
        }
        TokenWallet wallet = lockWallet(request);
        long amount = request.getReservedTokens();
        long available = wallet.getAvailableToken() - amount;
        if (available < -billing.getCreditLimit()) {
            throw new BusinessException("CREDIT_LIMIT_EXCEEDED", "token credit limit exceeded",
                    HttpStatus.PAYMENT_REQUIRED);
        }
        wallets.setBalances(wallet.getId(), available, wallet.getFrozenToken() + amount);
        request.setReservationActive(true);
    }

    @Transactional
    public boolean finish(ModelRequest request) {
        if (requests.finish(request) == 0) return false;
        // Old in-flight rows need reconciliation using the old reservation records.
        if (!request.isReservationActive()) return true;

        TokenWallet wallet = lockWallet(request);
        long reserved = request.getReservedTokens();
        long frozen = wallet.getFrozenToken() - reserved;
        if (frozen < 0) throw new IllegalStateException("wallet reservation is missing");

        long before = wallet.getAvailableToken() + reserved;
        boolean success = request.getStatus() == ModelRequestStatus.SUCCESS;
        long available = success ? before - request.getTotalTokens() : before;
        wallets.setBalances(wallet.getId(), available, frozen);
        if (success) {
            writeLedger(request, before, available);
        }
        return true;
    }

    @Transactional
    public void expire(String requestId) {
        ModelRequest request = requests.lockByRequestId(requestId);
        if (request == null || request.getStatus() != ModelRequestStatus.PROCESSING) return;
        request.setStatus(request.isReservationActive() && request.getProviderCode() == null
                ? ModelRequestStatus.FAILED : ModelRequestStatus.BILLING_UNKNOWN);
        request.setProviderCostStatus(ProviderCostStatus.UNKNOWN);
        finish(request);
    }

    private TokenWallet lockWallet(ModelRequest request) {
        TokenWallet wallet = wallets.lockByUserAndModel(request.getUserId(), request.getModelCode());
        if (wallet == null) throw new IllegalStateException("wallet missing");
        return wallet;
    }

    private void writeLedger(ModelRequest request, long before, long available) {
        TokenLedger ledger = new TokenLedger();
        ledger.setUserId(request.getUserId());
        ledger.setModelCode(request.getModelCode());
        ledger.setBizId("MODEL_CONSUME_" + request.getRequestId());
        ledger.setType(LedgerType.CONSUME);
        ledger.setAmount(-request.getTotalTokens());
        ledger.setBalanceBefore(before);
        ledger.setBalanceAfter(available);
        ledger.setRemark("model request");
        if (ledgers.insertIgnore(ledger) != 1) {
            throw new IllegalStateException("model consumption ledger already exists");
        }
    }
}
