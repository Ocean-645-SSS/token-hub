package com.tokenhub.entity;

import lombok.Data;

@Data public class TokenWallet {
    private Long id; private Long userId; private String modelCode; private Long availableToken;
    private Long frozenToken; private Long creditLimit; private Integer version;
}
