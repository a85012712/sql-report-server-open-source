package com.report.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.session.HttpSessionEventPublisher;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    @Value("${app.security.admin.username:admin}")
    private String adminUsername;

    @Value("${app.security.admin.password:}")
    private String adminPassword;

    @Value("${app.security.users:}")
    private List<String> extraUsers;

    // Bug #6修复：移除未使用的sessionTimeout字段（实际超时由server.servlet.session.timeout控制）

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .csrf(csrf -> csrf
                .ignoringRequestMatchers("/login", "/api/report/**", "/api/reports/**", "/api/admin/**", "/api/user/**")
            )
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/login", "/static/**", "/css/**", "/js/**").permitAll()
                .requestMatchers("/actuator/health").permitAll()
                .anyRequest().authenticated()
            )
            .formLogin(form -> form
                .loginPage("/login")
                .successHandler((request, response, authentication) -> {
                    String username = authentication.getName();
                    String ip = getClientIp(request);
                    log.info("[AUDIT] action=login, username={}, ip={}, status=success", username, ip);
                    response.sendRedirect(request.getContextPath() + "/");
                })
                .failureHandler((request, response, exception) -> {
                    String username = request.getParameter("username");
                    String ip = getClientIp(request);
                    log.warn("[AUDIT] action=login, username={}, ip={}, status=fail, reason={}", username, ip, exception.getMessage());
                    response.sendRedirect(request.getContextPath() + "/login?error");
                })
                .permitAll()
            )
            .logout(logout -> logout
                .logoutUrl("/logout")
                .logoutSuccessHandler((request, response, authentication) -> {
                    String username = (authentication != null) ? authentication.getName() : "unknown";
                    String ip = getClientIp(request);
                    log.info("[AUDIT] action=logout, username={}, ip={}", username, ip);
                    response.sendRedirect(request.getContextPath() + "/login?logout");
                })
                .deleteCookies("JSESSIONID")
                .invalidateHttpSession(true)
                .permitAll()
            )
            .sessionManagement(session -> session
                .sessionAuthenticationStrategy(sessionAuthenticationStrategy())
                .maximumSessions(1)  // 限制单用户单会话
                .maxSessionsPreventsLogin(false)
            )
            .exceptionHandling(ex -> ex
                .defaultAuthenticationEntryPointFor(
                    new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED),
                    new AntPathRequestMatcher("/api/**")
                )
            )
            .headers(headers -> headers
                .frameOptions(frame -> frame.sameOrigin())  // 允许同源iframe
                .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'self'; script-src 'self' 'unsafe-inline' https://cdn.jsdelivr.net; style-src 'self' 'unsafe-inline' https://cdn.jsdelivr.net; img-src 'self' data:; font-src 'self' https://cdn.jsdelivr.net;"))
            );

        return http.build();
    }

    @Bean
    public SessionAuthenticationStrategy sessionAuthenticationStrategy() {
        return new ChangeSessionIdAuthenticationStrategy();
    }

    @Bean
    public HttpSessionEventPublisher httpSessionEventPublisher() {
        return new HttpSessionEventPublisher();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        // 限制为内网IP段
        configuration.setAllowedOriginPatterns(Arrays.asList(
            "http://localhost:*",
            "http://127.0.0.1:*",
            "http://10.*.*.*:*",
            "http://192.168.*.*:*",
            "http://172.*.*.*:*"
        ));
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(Arrays.asList("*"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    public InMemoryUserDetailsManager userDetailsService(PasswordEncoder passwordEncoder) {
        List<org.springframework.security.core.userdetails.UserDetails> users = new ArrayList<>();

        // 校验管理员密码是否配置
        if (adminPassword == null || adminPassword.isEmpty()) {
            log.error("管理员密码未配置！请设置环境变量 ADMIN_PASSWORD 或在 application.yml 中配置 app.security.admin.password");
            throw new IllegalStateException("管理员密码未配置，系统无法启动。请设置环境变量 ADMIN_PASSWORD");
        }

        users.add(User.withUsername(adminUsername)
            .password(passwordEncoder.encode(adminPassword))
            .roles("ADMIN")
            .build());

        if (extraUsers != null) {
            for (String userConfig : extraUsers) {
                if (userConfig == null || userConfig.trim().isEmpty()) {
                    continue;
                }
                String[] parts = userConfig.split(":", 3);
                if (parts.length >= 2) {
                    String uname = parts[0].trim();
                    String upass = parts[1].trim();
                    String urole = parts.length >= 3 ? parts[2].trim().toUpperCase() : "USER";

                    // 校验用户名和密码
                    if (uname.isEmpty() || upass.length() < 6) {
                        continue;
                    }

                    users.add(User.withUsername(uname)
                        .password(passwordEncoder.encode(upass))
                        .roles(urole)
                        .build());
                }
            }
        }

        return new InMemoryUserDetailsManager(users);
    }

    /**
     * 获取客户端IP
     */
    private String getClientIp(HttpServletRequest request) {
        String ip = request.getRemoteAddr();
        if ("127.0.0.1".equals(ip) || "0:0:0:0:0:0:0:1".equals(ip)) {
            String forwarded = request.getHeader("X-Forwarded-For");
            if (forwarded != null && !forwarded.isEmpty() && !"unknown".equalsIgnoreCase(forwarded)) {
                ip = forwarded.split(",")[0].trim();
            } else {
                String realIp = request.getHeader("X-Real-IP");
                if (realIp != null && !realIp.isEmpty() && !"unknown".equalsIgnoreCase(realIp)) {
                    ip = realIp;
                }
            }
        }
        return ip;
    }
}
