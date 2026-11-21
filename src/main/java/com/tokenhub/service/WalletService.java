package com.tokenhub.service;

import com.tokenhub.config.BillingProperties;
import com.tokenhub.dto.LedgerResponse;
import com.tokenhub.dto.PageResponse;
import com.tokenhub.dto.WalletResponse;
import com.tokenhub.entity.TokenLedger;
import com.tokenhub.mapper.TokenLedgerMapper;
import com.tokenhub.mapper.TokenWalletMapper;
import com.tokenhub.security.CurrentUser;
import org.springframework.stereotype.Service;
import java.util.List;

@Service public class WalletService {
    private final TokenWalletMapper wallets; private final TokenLedgerMapper ledgers;
    private final BillingProperties billing;
    public WalletService(TokenWalletMapper wallets, TokenLedgerMapper ledgers, BillingProperties billing) {
        this.wallets = wallets;
        this.ledgers = ledgers;
        this.billing = billing;
    }
    public List<WalletResponse> wallet() {
        return wallets.listByUser(requireUser()).stream().map(wallet -> new WalletResponse(wallet.getModelCode(), wallet.getAvailableToken(), wallet.getFrozenToken(), billing.getCreditLimit())).toList();
    }
    public PageResponse<LedgerResponse> ledger(int page, int size) {
        long userId = requireUser(); List<TokenLedger> items = ledgers.listByUser(userId, size, (long) (page - 1) * size);
        return new PageResponse<>(items.stream().map(this::response).toList(), page, size, ledgers.countByUser(userId));
    }
    private LedgerResponse response(TokenLedger ledger) { return new LedgerResponse(ledger.getBizId(), ledger.getModelCode(), ledger.getType(), ledger.getAmount(), ledger.getBalanceBefore(), ledger.getBalanceAfter(), ledger.getRemark(), ledger.getCreatedAt()); }
    private long requireUser() { return CurrentUser.get(); }
}
