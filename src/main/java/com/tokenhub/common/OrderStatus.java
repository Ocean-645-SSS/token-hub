package com.tokenhub.common;

import com.baomidou.mybatisplus.annotation.EnumValue;

public enum OrderStatus { WAIT_PAY(101), USER_CANCELLED(102), TIMEOUT_CANCELLED(103), PAID(201), GRANTING(301), GRANTED(302), COMPLETED(401); @EnumValue private final int value; OrderStatus(int value) { this.value = value; } public int value() { return value; } }
