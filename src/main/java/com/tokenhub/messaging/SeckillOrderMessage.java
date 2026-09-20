package com.tokenhub.messaging;
public record SeckillOrderMessage(String requestId, Long userId, Long activityId, Long packageId) { }
