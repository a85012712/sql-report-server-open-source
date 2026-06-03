package com.report.config;

import com.report.interceptor.RateLimitInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.*;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Autowired(required = false)
    private RateLimitInterceptor rateLimitInterceptor;

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        // 主页路由
        registry.addViewController("/").setViewName("index");
        registry.addViewController("/login").setViewName("login");
        // Bug #14修复：admin/tools 已废弃，返回404而非重定向（避免信息泄露）
        registry.addViewController("/admin/tools").setStatusCode(org.springframework.http.HttpStatus.NOT_FOUND);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 添加限流拦截器（所有API接口统一限流，包括管理接口）
        if (rateLimitInterceptor != null) {
            registry.addInterceptor(rateLimitInterceptor)
                .addPathPatterns("/api/**");
        }
    }

    // Bug #8修复：移除重复的CORS配置，由SecurityConfig中的CorsConfigurationSource统一管理

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 静态资源缓存
        registry.addResourceHandler("/static/**")
            .addResourceLocations("classpath:/static/")
            .setCachePeriod(3600) // 缓存1小时
            .resourceChain(true);
    }
}
