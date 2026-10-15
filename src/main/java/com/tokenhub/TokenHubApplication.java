package com.tokenhub;

import com.tokenhub.config.JwtProperties;
import com.tokenhub.config.BillingProperties;
import com.tokenhub.config.ModelProperties;
import com.tokenhub.config.RateLimitProperties;
import com.tokenhub.config.MessagingProperties;
import com.tokenhub.config.AdminProperties;
import com.tokenhub.config.PackageIndexSyncProperties;
import com.tokenhub.config.RabbitConsumerProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@MapperScan("com.tokenhub.mapper")
@EnableConfigurationProperties({JwtProperties.class, BillingProperties.class, ModelProperties.class, RateLimitProperties.class, MessagingProperties.class, RabbitConsumerProperties.class, AdminProperties.class, PackageIndexSyncProperties.class})
@EnableScheduling
public class TokenHubApplication {
    public static void main(String[] args) { SpringApplication.run(TokenHubApplication.class, args); }
}
