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
        // /login 已改由 LoginController 接管：渲染登录页时要问一句「这台终端/这个工号
        // 现在要不要验证码」，而 ViewController 既拿不到 model 也没法注入依赖。
        // 路径、视图名、permitAll 行为都没变，formLogin().loginPage("/login") 仍指向同一个 URL。
        // /admin/tools 为已废弃入口。原先用
        //   addViewController("/admin/tools").setStatusCode(404)
        // 期望返回 404，但未设置视图名 → Spring 以 URL 作为视图名交 Thymeleaf 解析，
        // 抛 TemplateInputException，异常处理器二次失败 → 实际返回 500（缺陷 M-4）。
        // 这里直接移除该路由：无匹配处理器时 Spring Boot 统一返回标准 404，
        // 既不暴露框架栈信息，也避免了无效的 ERROR 日志噪音。
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
        // 静态资源缓存（带版本化，解决缓存更新问题）
        registry.addResourceHandler("/static/**")
            .addResourceLocations("classpath:/static/")
            .setCachePeriod(3600)
            .resourceChain(true)
            .addResolver(new org.springframework.web.servlet.resource.VersionResourceResolver().addContentVersionStrategy("/**"));

        // 站点图标（`static/favicon.ico`）要**单独注册**成 `/favicon.ico`。
        //   本项目实际生效的静态映射只有上面那条 `/static/**` ，
        //   把文件放在 `static/favicon.ico` 后，`GET /favicon.ico` 仍是 404
        //   （「No mapping for GET /favicon.ico」），`GET /static/favicon.ico` 才 200。
        //   而浏览器请求的是**根路径**那个（`<link rel="icon" href="/favicon.ico">`
        //   以及每个页面的隐式请求），所以这里必须显式映射一次。
        //   注意：别忘了 `SecurityConfig` 里也要放行 `/favicon.ico`：它在**登录页**
        //      （未认证）就会被请求，不放行会被 302 到 /login、浏览器拿到 HTML 而非图片。
        registry.addResourceHandler("/favicon.ico")
            .addResourceLocations("classpath:/static/")
            .setCachePeriod(3600);
    }
}
