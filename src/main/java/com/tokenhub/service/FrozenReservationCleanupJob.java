package com.tokenhub.service;

import com.tokenhub.config.BillingProperties;
import com.tokenhub.mapper.ModelRequestMapper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
public class FrozenReservationCleanupJob {
    private final ModelRequestMapper requests;
    private final TokenReservationService reservations;
    private final BillingProperties billing;

    public FrozenReservationCleanupJob(ModelRequestMapper requests, TokenReservationService reservations,
                                       BillingProperties billing) {
        this.requests = requests;
        this.reservations = reservations;
        this.billing = billing;
    }

    @Scheduled(fixedDelay = 60000)
    public void releaseExpired() {
        var cutoff = LocalDateTime.now().minusSeconds(billing.getReservationTimeoutSeconds());
        for (var request : requests.listExpired(cutoff)) {
            reservations.expire(request.getRequestId());
        }
    }
}
