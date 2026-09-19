package com.tokenhub;

import com.tokenhub.config.JwtProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@MapperScan("com.tokenhub.mapper")
@EnableConfigurationProperties(JwtProperties.class)
public class TokenHubApplication {
    public static void main(String[] args) { SpringApplication.run(TokenHubApplication.class, args); }
}
