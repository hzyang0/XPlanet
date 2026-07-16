package com.xplanet.ai.config;

import com.xplanet.common.auth.RequiredAuthInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** AI tasks, reports and evidence are private; read and write requests both require authentication. */
@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final RequiredAuthInterceptor requiredAuthInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(requiredAuthInterceptor).addPathPatterns("/api/ai/**");
    }
}
