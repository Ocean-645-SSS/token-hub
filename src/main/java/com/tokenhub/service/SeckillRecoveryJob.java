package com.tokenhub.service;

import com.tokenhub.mapper.SeckillActivityMapper;
import com.tokenhub.mapper.SeckillReservationMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Component
public class SeckillRecoveryJob {
    private static final Logger log = LoggerFactory.getLogger(SeckillRecoveryJob.class);
    private final SeckillReservationMapper records;
    private final SeckillReservationService recovery;
    private final SeckillActivityMapper activities;
    private final SeckillService seckill;
    private final int timeoutSeconds;

    public SeckillRecoveryJob(SeckillReservationMapper records, SeckillReservationService recovery,
            SeckillActivityMapper activities, SeckillService seckill,
            @Value("${tokenhub.seckill.reservation-timeout-seconds:300}") int timeoutSeconds) {
        if (timeoutSeconds < 1) throw new IllegalArgumentException("reservation timeout must be positive");
        this.records = records; this.recovery = recovery; this.activities = activities; this.seckill = seckill;
        this.timeoutSeconds = timeoutSeconds;
    }

    @Scheduled(fixedDelayString = "${tokenhub.seckill.recovery-interval-ms:30000}")
    public void recover() {
        for (String id : records.expired(LocalDateTime.now(ZoneOffset.UTC).minusSeconds(timeoutSeconds))) {
            try { recovery.compensate(id); }
            catch (RuntimeException exception) { log.error("seckill compensation failed requestId={}", id, exception); }
        }
        for (String id : records.pendingReleases()) {
            try { recovery.releaseReservation(id); }
            catch (RuntimeException exception) { log.error("seckill release failed requestId={}", id, exception); }
        }
        for (var activity : activities.findWarmupCandidates()) {
            try { seckill.preheat(activity.getId()); }
            catch (RuntimeException exception) { log.error("seckill cache repair failed activityId={}", activity.getId(), exception); }
        }
    }
}
