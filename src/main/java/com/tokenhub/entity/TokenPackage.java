package com.tokenhub.entity;
import lombok.Data;
import java.math.BigDecimal;
@Data public class TokenPackage { private Long id; private String name; private String modelCode; private Long tokenAmount; private BigDecimal price; private String description; private String suitableScenario; private Long stock; private Integer status; }
