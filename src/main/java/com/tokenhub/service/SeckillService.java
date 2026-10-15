package com.tokenhub.service;

import com.tokenhub.common.BusinessException;
import com.tokenhub.common.SeckillReservationState;
import com.tokenhub.dto.SeckillReservationResponse;
import com.tokenhub.dto.SeckillResultResponse;
import com.tokenhub.entity.SeckillActivity;
import com.tokenhub.mapper.SeckillActivityMapper;
import com.tokenhub.mapper.LocalMessageMapper;
import com.tokenhub.entity.LocalMessage;
import com.tokenhub.messaging.SeckillOrderMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tokenhub.security.CurrentUser;
import org.springframework.core.io.ClassPathResource;
import org.springframework.beans.factory.annotation.Autowired;
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
    private static final DefaultRedisScript<Long> COMPENSATE_RESERVATION_SCRIPT = compensateReservationScript();
    private final StringRedisTemplate redis; private final SeckillActivityMapper activities; private final LocalMessageMapper messages; private final ObjectMapper json;
    @Autowired public SeckillService(StringRedisTemplate redis, SeckillActivityMapper activities, LocalMessageMapper messages, ObjectMapper json) { this.redis = redis; this.activities = activities; this.messages = messages; this.json = json; }
    /** Test-only compatibility constructor; production wiring always provides the outbox collaborators. */
    public SeckillService(StringRedisTemplate redis, SeckillActivityMapper activities) { this(redis, activities, null, null); }
    public SeckillReservationResponse reserve(long activityId) {
        return reserveSeckillStock(activityId);
    }
    /** Atomically reserves only seckill stock; the asynchronous reservation workflow remains PENDING/SENT. */
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
            if (messages != null) {
                try {
                    redis.opsForHash().put(reservationKey(requestId), "reservationState", SeckillReservationState.STOCK_RESERVED.name());
                    SeckillTrace.mark(requestId, "t2");
                    createOrderMessage(new SeckillOrderMessage(requestId, userId, activityId, packageId(requestId)));
                } catch (RuntimeException exception) {
                    compensateReservation(activityId, requestId);
                    throw unavailable();
                }
            }
            SeckillTrace.mark(requestId, "t4");
            return new SeckillReservationResponse(requestId, "QUEUING");
        }
        throw error(result);
    }
    public SeckillResultResponse result(String requestId) {
        String reservationKey = reservationKey(requestId); String status;
        try { status = (String) redis.opsForHash().get(reservationKey, "status"); }
        catch (RuntimeException exception) { throw unavailable(); }
        if (status == null) throw new BusinessException("SECKILL_REQUEST_NOT_FOUND", "seckill request not found", HttpStatus.NOT_FOUND);
        Object userId = redis.opsForHash().get(reservationKey, "userId");
        if (userId == null || !userId.equals(Long.toString(requireUser()))) throw new BusinessException("SECKILL_REQUEST_NOT_FOUND", "seckill request not found", HttpStatus.NOT_FOUND);
        if ("ORDER_CREATED".equals(status)) {
            Object orderNo = redis.opsForHash().get(reservationKey, "orderNo");
            return new SeckillResultResponse(requestId, "SUCCESS:" + orderNo);
        }
        return new SeckillResultResponse(requestId, "FAILED".equals(status) ? "FAILED" : "QUEUING");
    }
    public void preheat(long activityId) { preheat(requireActivity(activityId)); }
    private void preheat(SeckillActivity activity) {
        validate(activity); String activityKey = activityKey(activity.getId());
        redis.opsForHash().putAll(activityKey, java.util.Map.of("id", activity.getId().toString(), "packageId", activity.getPackageId().toString(), "status", activity.getStatus().toString(), "startTime", Long.toString(activity.getStartTime().toInstant(ZoneOffset.UTC).toEpochMilli()), "endTime", Long.toString(activity.getEndTime().toInstant(ZoneOffset.UTC).toEpochMilli())));
        redis.opsForValue().set(stockKey(activity.getId()), activity.getStock().toString());
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
    private void createOrderMessage(SeckillOrderMessage payload) {
        if (messages == null) return;
        try {
            LocalMessage message = new LocalMessage();
            message.setMessageId(UUID.randomUUID().toString().replace("-", ""));
            message.setEventType("SECKILL_ORDER");
            message.setBizId(payload.requestId());
            message.setTopic("seckill.order");
            message.setPayload(json.writeValueAsString(payload));
            if (messages.insert(message) != 1) throw unavailable();
            SeckillTrace.mark(payload.requestId(), "t3");
            SeckillTrace.mark(payload.requestId(), "p0");
        } catch (Exception exception) {
            throw unavailable();
        }
    }
    private void compensateReservation(long activityId, String requestId) {
        try {
            redis.execute(COMPENSATE_RESERVATION_SCRIPT,
                    List.of(reservationKey(requestId), stockKey(activityId), "seckill:release:" + requestId));
        } catch (RuntimeException ignored) {
            // The original failure is returned to the caller; a Redis outage prevents immediate compensation.
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
    private static DefaultRedisScript<Long> compensateReservationScript() { DefaultRedisScript<Long> script = new DefaultRedisScript<>(); script.setLocation(new ClassPathResource("lua/compensate_seckill_reservation.lua")); script.setResultType(Long.class); return script; }
}
