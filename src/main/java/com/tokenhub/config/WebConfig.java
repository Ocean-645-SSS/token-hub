package com.tokenhub.config;
import com.tokenhub.security.JwtAuthenticationInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
@Configuration
public class WebConfig implements WebMvcConfigurer {
    private final JwtAuthenticationInterceptor interceptor;
    public WebConfig(JwtAuthenticationInterceptor interceptor) { this.interceptor = interceptor; }
    @Override public void addInterceptors(InterceptorRegistry registry) { registry.addInterceptor(interceptor).addPathPatterns("/api/**").excludePathPatterns("/api/auth/login", "/api/packages/**"); }
}
