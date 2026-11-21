package com.tokenhub.config;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
@Data @ConfigurationProperties(prefix = "tokenhub.billing") public class BillingProperties { private long creditLimit = 0; private long safetyMargin = 512; private long reservationTimeoutSeconds = 300; }
