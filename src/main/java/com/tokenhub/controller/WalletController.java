package com.tokenhub.controller;

import com.tokenhub.common.ApiResponse;
import com.tokenhub.dto.LedgerResponse;
import com.tokenhub.dto.PageResponse;
import com.tokenhub.dto.WalletResponse;
import com.tokenhub.service.WalletService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;

@RestController @RequestMapping("/api/wallet") public class WalletController {
    private final WalletService service;
    public WalletController(WalletService service) { this.service = service; }
    @GetMapping public ApiResponse<List<WalletResponse>> wallet() { return ApiResponse.success(service.wallet()); }
    @GetMapping("/ledger") public ApiResponse<PageResponse<LedgerResponse>> ledger(@RequestParam(defaultValue="1") int page, @RequestParam(defaultValue="20") int size) { return ApiResponse.success(service.ledger(Math.max(1, page), Math.min(100, Math.max(1, size)))); }
}
