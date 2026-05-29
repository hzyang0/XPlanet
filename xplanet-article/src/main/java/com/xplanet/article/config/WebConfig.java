package com.xplanet.article.config;

import com.xplanet.common.auth.AuthInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 注册鉴权拦截器。
 *
 * <p>读接口(看文章、列表、看评论)放开,无需登录;
 * 写接口(发文章、改、删、发评论)需要登录鉴权。
 * 体现「读写分离的权限控制」——浏览不设门槛,写操作必须有身份。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Bean
    public AuthInterceptor authInterceptor() {
        return new AuthInterceptor();
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 拦截所有 article / comment 接口;拦截器内部对 GET(读)放行,只对写操作鉴权
        registry.addInterceptor(authInterceptor())
                .addPathPatterns("/api/article/**", "/api/comment/**");
    }
}
