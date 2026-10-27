package com.tokenhub.entity;

import lombok.Data;

@Data
public class SeckillReservation {
    private String requestId;
    private Long userId;
    private Long activityId;
    private Long packageId;
    private String state;
    private String orderNo;
}
