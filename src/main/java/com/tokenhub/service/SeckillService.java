package com.tokenhub.service;

import com.tokenhub.common.BusinessException;
import com.tokenhub.dto.SeckillReservationResponse;
import com.tokenhub.dto.SeckillResultResponse;
import com.tokenhub.entity.SeckillActivity;
import com.tokenhub.mapper.SeckillActivityMapper;
import com.tokenhub.messaging.SeckillOrderMessage;
import com.tokenhub.security.CurrentUser;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import com.tokenhub.profiling.SeckillTrace;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

@Service public class SeckillService {
    private static final DefaultRedisScript<Long> RESERVE_SCRIPT = reserveScript();
    private final StringRedisTemplate redis;
    private final SeckillActivityMapper activities;
    private final SeckillReservationService reservations;
    private final com.tokenhub.mapper.SeckillReservationMapper reservationRecords;
    private final SeckillInventoryProjection projection;
    public SeckillService(StringRedisTemplate redis, SeckillActivityMapper activities, SeckillReservationService reservations,
            com.tokenhub.mapper.SeckillReservationMapper reservationRecords, SeckillInventoryProjection projection) {
        this.redis = redis; this.activities = activities; this.reservations = reservations;
        this.reservationRecords = reservationRecords; this.projection = projection;
    }
    public SeckillReservationResponse reserve(long activityId) {
        return reserveSeckillStock(activityId);
    }
    /** Redis is an admission estimate; durable reservation and outbox commit before QUEUING is returned. */
    public SeckillReservationResponse reserveSeckillStock(long activityId) {
        long userId = requireUser(); String requestId = UUID.randomUUID().toString().replace("-", "");
        SeckillTrace.start(requestId);
        Long result;
        try {
            result = redis.execute(RESERVE_SCRIPT, keys(activityId, requestId), Long.toString(userId), requestId, Long.toString(System.currentTimeMillis()));
        } catch (RuntimeException exception) {
            throw new BusinessException("SECKILL_UNAVAILABLE", "seckill is temporarily unavailable", HttpStatus.SERVICE_UNAVAILABLE);
        }
        SeckillTrace.mark(requestId, "t1");
        if (result == null) throw unavailable();
        if (result == 0) {
            try {
                reservations.reserve(new SeckillOrderMessage(requestId, userId, activityId, packageId(requestId)));
                SeckillTrace.mark(requestId, "t3");
                SeckillTrace.mark(requestId, "p0");
            } catch (RuntimeException exception) {
                repairRejectedAdmission(activityId);
                if (exception instanceof org.springframework.dao.DuplicateKeyException)
                    throw new BusinessException("SECKILL_DUPLICATE", "already joined this seckill", HttpStatus.CONFLICT);
                if (exception instanceof BusinessException business) throw business;
                throw unavailable();
            }
            SeckillTrace.mark(requestId, "t4");
            return new SeckillReservationResponse(requestId, "QUEUING");
        }
        throw error(result);
    }
    public SeckillResultResponse result(String requestId) {
        long userId = requireUser();
        var reservation = reservationRecords.find(requestId);
        if (reservation == null || !java.util.Objects.equals(reservation.getUserId(), userId))
            throw new BusinessException("SECKILL_REQUEST_NOT_FOUND", "seckill request not found", HttpStatus.NOT_FOUND);
        if (reservation.getOrderNo() != null)
            return new SeckillResultResponse(requestId, "SUCCESS:" + reservation.getOrderNo());
        return new SeckillResultResponse(requestId, "RESERVED".equals(reservation.getState()) ? "QUEUING" : "FAILED");
    }
    public void preheat(long activityId) { preheat(requireActivity(activityId)); }
    private void preheat(SeckillActivity activity) {
        validate(activity); String activityKey = activityKey(activity.getId());
        redis.opsForHash().putAll(activityKey, java.util.Map.of("id", activity.getId().toString(), "packageId", activity.getPackageId().toString(), "status", activity.getStatus().toString(), "startTime", Long.toString(activity.getStartTime().toInstant(ZoneOffset.UTC).toEpochMilli()), "endTime", Long.toString(activity.getEndTime().toInstant(ZoneOffset.UTC).toEpochMilli())));
        projection.synchronize(activity.getId());
    }
    private SeckillActivity requireActivity(long activityId) {
        SeckillActivity activity = activities.findById(activityId);
        if (activity == null) throw new BusinessException("SECKILL_ACTIVITY_NOT_FOUND", "seckill activity not found", HttpStatus.NOT_FOUND);
        return activity;
    }
    private void validate(SeckillActivity activity) {
        if (activity.getPackageId() == null || activity.getStatus() == null || activity.getStatus() != 1 || activity.getStock() == null || activity.getStock() < 0 || activity.getStartTime() == null || activity.getEndTime() == null || !activity.getEndTime().isAfter(activity.getStartTime())) throw new BusinessException("SECKILL_ACTIVITY_INVALID", "seckill activity is invalid", HttpStatus.CONFLICT);
    }
    private List<String> keys(long activityId, String requestId) { return List.of(activityKey(activityId), stockKey(activityId), usersKey(activityId), reservationKey(requestId)); }
    private long packageId(String requestId) { Object value = redis.opsForHash().get(reservationKey(requestId), "packageId"); if (value == null) throw unavailable(); return Long.parseLong(value.toString()); }
    private void repairRejectedAdmission(long activityId) {
        try {
            projection.synchronize(activityId);
        } catch (RuntimeException ignored) {
            // Redis is advisory; durable inventory is repaired by versioned snapshots.
        }
    }
    private String activityKey(long activityId) { return "seckill:activity:" + activityId; }
    private String stockKey(long activityId) { return "seckill:stock:" + activityId; }
    private String usersKey(long activityId) { return "seckill:users:" + activityId; }
    private String reservationKey(String requestId) { return "seckill:reservation:" + requestId; }
    private long requireUser() { Long userId = CurrentUser.get(); if (userId == null) throw new BusinessException("UNAUTHORIZED", "valid access token required", HttpStatus.UNAUTHORIZED); return userId; }
    private BusinessException unavailable() { return new BusinessException("SECKILL_UNAVAILABLE", "seckill is temporarily unavailable", HttpStatus.SERVICE_UNAVAILABLE); }
    private BusinessException error(long result) {
        return switch ((int) result) {
            case 1 -> new BusinessException("SECKILL_NOT_STARTED", "seckill has not started", HttpStatus.CONFLICT);
            case 2 -> new BusinessException("SECKILL_ENDED", "seckill has ended", HttpStatus.GONE);
            case 3 -> new BusinessException("SECKILL_UNAVAILABLE", "seckill activity is unavailable", HttpStatus.CONFLICT);
            case 4 -> new BusinessException("SECKILL_OUT_OF_STOCK", "seckill is out of stock", HttpStatus.CONFLICT);
            case 5 -> new BusinessException("SECKILL_DUPLICATE", "already joined this seckill", HttpStatus.CONFLICT);
            default -> unavailable();
        };
    }
    private static DefaultRedisScript<Long> reserveScript() { DefaultRedisScript<Long> script = new DefaultRedisScript<>(); script.setLocation(new ClassPathResource("lua/reserve_seckill_stock.lua")); script.setResultType(Long.class); return script; }
}
