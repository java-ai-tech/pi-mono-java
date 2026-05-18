package com.glmapper.coding.http.api.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final RateLimitInterceptor rateLimitInterceptor;
    private final long asyncRequestTimeoutMs;

    public WebMvcConfig(RateLimitInterceptor rateLimitInterceptor,
                        @Value("${pi.http.async-request-timeout-ms:0}") long asyncRequestTimeoutMs) {
        this.rateLimitInterceptor = rateLimitInterceptor;
        this.asyncRequestTimeoutMs = asyncRequestTimeoutMs;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(rateLimitInterceptor)
                .addPathPatterns("/api/chat/**");
    }

    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        configurer.setDefaultTimeout(asyncRequestTimeoutMs);
    }
}
