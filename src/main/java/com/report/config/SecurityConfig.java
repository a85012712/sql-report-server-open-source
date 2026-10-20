package com.report.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.session.ChangeSessionIdAuthenticationStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.session.HttpSessionEventPublisher;
import org.springframework.security.web.session.SessionInformationExpiredStrategy;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import com.report.service.AuditService;
import com.report.service.RolePermissionService;
import com.report.service.UserManagementService;

import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    private final MustChangePasswordFilter mustChangePasswordFilter;
    private final LoginLockFilter loginLockFilter;
    private final LoginAttemptService loginAttemptService;
    private final UserManagementService userManagementService;
    private final RolePermissionService rolePermissionService;
    private final AuditService auditService;
    private final LoginCaptchaPolicy captchaPolicy;
    private final com.report.util.ClientIpResolver clientIpResolver;

    public SecurityConfig(MustChangePasswordFilter mustChangePasswordFilter,
                          LoginLockFilter loginLockFilter,
                          LoginAttemptService loginAttemptService,
                          UserManagementService userManagementService,
                          RolePermissionService rolePermissionService,
                          AuditService auditService,
                          LoginCaptchaPolicy captchaPolicy,
                          com.report.util.ClientIpResolver clientIpResolver) {
        this.mustChangePasswordFilter = mustChangePasswordFilter;
        this.loginLockFilter = loginLockFilter;
        this.loginAttemptService = loginAttemptService;
        this.userManagementService = userManagementService;
        this.rolePermissionService = rolePermissionService;
        this.auditService = auditService;
        this.captchaPolicy = captchaPolicy;
        this.clientIpResolver = clientIpResolver;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        // CSRF 令牌立即加载:Spring Security 6.1+ 默认延迟到首次访问才创建会话,
        // 登录页渲染到中段(CSRF 隐藏域)时响应已提交,新建会话会失败导致全新会话无法登录
        org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler csrfHandler =
            new org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler();
        csrfHandler.setCsrfRequestAttributeName(null);
        http
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .csrf(csrf -> csrf.csrfTokenRequestHandler(csrfHandler)) // 登录页携带 CSRF；API 写操作由前端 header 携带
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/login", "/login/captcha", "/static/**", "/css/**", "/js/**").permitAll()
                // ↑ /login/captcha 必须一并匿名放行：它在登录页的 <img> 里加载，
                //   此刻还没有会话。不放行的话被 302 到 /login，浏览器拿到的是一段 HTML
                //   而不是图片，和 /favicon.ico 当年一模一样的坏法：不报错，只是码出不来。
                // 站点图标必须放行：浏览器在**登录页**（未认证）就会去取它。
                //   不放行的话请求会被 302 到 /login，浏览器拿到的是一段 HTML 而不是图片，
                //   结果是「图标永远显示不出来」，而**页面与接口全部正常** ，
                //   这种坏法没有任何报错，只靠肉眼看标签页才能发现。
                //   （实测：不放行时 GET /favicon.ico → 302；放行后 → 200 image/x-icon）
                .requestMatchers("/favicon.ico").permitAll()
                // 版本信息：仅返回版本号/构建时间，无敏感内容；登录页底部需在未认证状态展示，故放行
                .requestMatchers("/api/version").permitAll()
                .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                // /actuator/prometheus 不再匿名放行：它会吐出 JVM 内存、Hikari 池名、
                // 各 URI 的调用次数与报表行数计数，整个内网都能不看登录页就读到。
                // 现在统一落到下面的 hasRole("ADMIN")；确有 Prometheus 采集需求时，
                // 由采集端带凭据访问，而不是反放开匿名读。
                .requestMatchers("/actuator/**").hasRole("ADMIN")
                .anyRequest().authenticated()
            )
            .formLogin(form -> form
                .loginPage("/login")
                .successHandler(loginSuccessHandler())
                .failureHandler(loginFailureHandler())
                .permitAll()
            )
            .logout(logout -> logout
                .logoutUrl("/logout")
                // 登出也要留痕：否则「工号 X 的账号在这一小时里查了 3 张报表」
                // 只剩一段没有终点的记录，无法界定一次会话的边界。
                .logoutSuccessHandler((request, response, authentication) -> {
                    if (authentication != null) {
                        auditService.audit("logout", java.util.Map.of(
                            "operator", authentication.getName(), "success", true));
                    }
                    response.sendRedirect(request.getContextPath() + "/login?logout");
                })
                .deleteCookies("BISESSIONID")
                .invalidateHttpSession(true)
                .permitAll()
            )
            .sessionManagement(session -> session
                .sessionAuthenticationStrategy(sessionAuthenticationStrategy())
                .maximumSessions(1)
                .sessionRegistry(sessionRegistry())
                .maxSessionsPreventsLogin(false)
                .expiredSessionStrategy(sessionExpiredStrategy())
            )
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint(unauthenticatedEntryPoint())
            )
            .headers(headers -> headers
                .frameOptions(frame -> frame.sameOrigin())
                .contentTypeOptions(Customizer.withDefaults())
                // 全部 JS/CSS/字体都是本地 vendor（echarts / remixicon），
                // cdn.jsdelivr.net 在这套内网里根本不可达：留着既与 README「零 CDN」矛盾，
                // 又是服务器一旦出网就能被利用的远端脚本注入跳板，故删除。
                // （原来这里还列着 xlsx，SheetJS 前端库，实测没有任何模板引用它、前端从没用过
                //   XLSX. 全局对象，导出走的是服务端 Apache POI，那份 944KB 的前端库已删掉。
                //   别照这段注释把它重新加回来。）
                // 'unsafe-inline' 暂留，index.html 有 62 处内联 onclick、login.html 有
                // 690 行内联 style，去掉它需要先把事件改成 affiliation.js 那套 data-act
                // 委托模式，属独立的后续项（XSS 本身已在渲染侧逐个转义封堵）。
                .contentSecurityPolicy(csp -> csp.policyDirectives(
                    "default-src 'self'; script-src 'self' 'unsafe-inline'; " +
                    "style-src 'self' 'unsafe-inline'; img-src 'self' data:; " +
                    "font-src 'self'; object-src 'none'; base-uri 'none'; frame-ancestors 'self'"))
            )
            .addFilterBefore(loginLockFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterAfter(mustChangePasswordFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    /**
     * 未认证（未登录 / 会话已失效）请求的统一入口点。
     *
     * <p>缺陷 L-5（与 M-2 同源）：原实现只调用了一次
     * {@code defaultAuthenticationEntryPointFor(HttpStatusEntryPoint(401), /api/**)}。
     * 而 Spring Security 在「只注册了一个映射」时，会直接把该映射的入口点当作
     * 全局入口点（忽略匹配器，见 ExceptionHandlingConfigurer#createDefaultEntryPoint），
     * 于是浏览器访问 {@code /} 等页面也会拿到空 body 的 401，而不是跳转登录页。
     *
     * <p>修复后语义：
     * <ul>
     *   <li>{@code /api/**} → 401 + JSON（前端 fetch 据此跳登录页）</li>
     *   <li>其余（页面/静态资源）→ 302 重定向到 {@code /login}</li>
     * </ul>
     */
    private AuthenticationEntryPoint unauthenticatedEntryPoint() {
        return (request, response, authException) ->
            respondUnauthenticated(request, response,
                "UNAUTHENTICATED", "未登录或登录状态已失效，请重新登录", "/login");
    }

    /**
     * 「没登录」与「会话被顶下线/过期」两条路径共用的响应写法。
     *
     * <p>为什么合并：原先两处各抄一遍「剥 contextPath → 按 {@code /api/} 前缀分流 →
     * 401 + JSON + no-store → 否则 302 到登录页」，只有错误码文案和跳转带不带
     * {@code ?expired} 不同。这类复制的实际坏处是将来只改到一处 ，
     * 比如给 JSON 补个字段、或把 {@code no-store} 漏掉，前端拿到的两种「未登录」
     * 行为就分叉了，而它们在前端是完全等价的（都要跳登录页）。
     *
     * <p>注意：两条路径都刻意返回 401 而非 403：前端 {@code apiFetch} 是按
     * {@code res.status === 401} 决定跳登录页的（见 app.js 的 401 分支），并用
     * {@code code === 'SESSION_EXPIRED'} 区分「被顶下线」与「从没登录过」，
     * 分别跳 {@code /login?expired} 与 {@code /login}。所以将来新增「未认证」响应路径时
     * 请复用本方法，别再抄一份，状态码或 code 写歪一处，前端就不会跳登录页了。
     *
     * @param apiCode    {@code /api/**} 返回的错误码（由本类内部以字面量传入，不含外部输入）
     * @param apiMessage {@code /api/**} 返回的提示文案
     * @param loginUrl   非 API 请求要跳的地址，不含 contextPath（如 {@code "/login?expired"}）
     */
    private void respondUnauthenticated(HttpServletRequest request, HttpServletResponse response,
                                        String apiCode, String apiMessage, String loginUrl)
            throws java.io.IOException {
        String ctx = request.getContextPath() != null ? request.getContextPath() : "";
        String uri = request.getRequestURI() != null ? request.getRequestURI() : "";
        String path = (!ctx.isEmpty() && uri.startsWith(ctx)) ? uri.substring(ctx.length()) : uri;

        if (path.startsWith("/api/")) {
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setContentType("application/json;charset=UTF-8");
            response.setHeader("Cache-Control", "no-store");
            response.getWriter().write(
                "{\"code\":\"" + apiCode + "\",\"message\":\"" + apiMessage + "\"}");
            return;
        }
        response.sendRedirect(ctx + loginUrl);
    }

    private AuthenticationSuccessHandler loginSuccessHandler() {
        return (request, response, authentication) -> {
            loginAttemptService.loginSucceeded(authentication.getName());
            // 审计：谁登录进来了。挂在成功处理器里而不是过滤器里，成功路径只有一条，
            // 不会像在 Filter 里那样和失败路径重复计数。
            auditService.audit("loginSuccess", java.util.Map.of(
                "operator", authentication.getName(), "success", true));
            // 可视化平台（数据大屏）落地页：
            // 大屏权限现已默认开启，若仍按「有权限即直达大屏」会把全部存量账号弹到大屏页，
            // 故改为仅当该账号被显式指定了「大屏号」（管理后台配置的大屏专用账号）时才直达 /screen，
            // 其余账号（含超管）一律回 `/`，由前端按权限点落到「侧边栏第一个可用的菜单」
            // （一个菜单都用不了时显示「暂无可用功能」页，见 app.js#chooseLandingPage）。
            String target = "/";
            try {
                String username = authentication.getName();
                if (userManagementService.hasExplicitScreenNo(username)
                    && rolePermissionService.hasScreenPermission(username)
                    && !userManagementService.mustChangePassword(username)) {
                    target = "/screen";
                }
            } catch (Exception e) {
                log.warn("登录跳转判断大屏号失败，回退到工作台: {}", e.getMessage());
            }
            response.sendRedirect(target);
        };
    }

    private AuthenticationFailureHandler loginFailureHandler() {
        return (request, response, exception) -> {
            String username = request.getParameter("username");
            loginAttemptService.loginFailed(username);
            // IP 维度也要记一笔：撞库的常见形状是「对每个工号只试一次」，
            // 只按工号计数的话每个账号都停在 1 次失败，永远触发不了任何阈值。
            loginAttemptService.noteLoginFailureFromIp(clientIpResolver.resolve(request));
            log.warn("登录失败: user={}", username);
            // 审计要记的是**被尝试的那个工号**：此时 SecurityContext 还是匿名，
            // AuditService 的自动补齐拿不到人，必须显式传 operator。
            // reason 只记异常类名，不记 message，Spring Security 的失败消息里
            // 可能带上用户输入，审计文件不该收用户输入。
            auditService.audit("loginFailed", java.util.Map.of(
                "operator", username == null ? "" : username.trim(),
                "success", false,
                "reason", exception.getClass().getSimpleName()));
            if (loginAttemptService.isLocked(username)) {
                response.sendRedirect("/login?locked&sec=" + loginAttemptService.remainingLockSeconds(username));
            } else {
                // 失败计数刚跨过阈值时，下一次渲染必须带上验证码框，判据复用 LoginCaptchaPolicy，
                // 不在这里重算，否则「页面没框、过滤器却要码」会变成用户无法自助的死循环。
                String cap = captchaPolicy.required(request, username) ? "&cap=1" : "";
                response.sendRedirect("/login?error" + cap);
            }
        };
    }

    @Bean
    public SessionAuthenticationStrategy sessionAuthenticationStrategy() {
        return new ChangeSessionIdAuthenticationStrategy();
    }

    /**
     * 会话注册表：显式声明成 bean，不再让 Spring Security 内部自建。
     *
     * <p>两个用途：① {@code maximumSessions(1)} 继续按它做同账号顶线（行为不变）；
     * ② 让 {@code PasswordResetEffects} 能在管理员重置口令后主动吊销该用户的旧会话 ，
     * 不显式声明就拿不到这个 bean（Spring Security 6 的 DSL 自建实例不对外暴露）。
     *
     * <p>被吊销的会话走已有的 {@link #sessionExpiredStrategy()}：{@code /api/**} 回
     * 401 + {@code code=SESSION_EXPIRED}、页面 302 到 {@code /login?expired}，
     * 前端 {@code apiFetch} 本来就能识别，不需要新增任何客户端逻辑。
     */
    @Bean
    public org.springframework.security.core.session.SessionRegistry sessionRegistry() {
        return new org.springframework.security.core.session.SessionRegistryImpl();
    }

    /**
     * 会话过期（同一账号在别处登录，本会话被顶下线）的响应策略。
     *
     * <p>缺陷 M-2：Spring Security 默认的 SessionInformationExpiredStrategy 会以
     * HTTP 200 + 纯文本 写出会话信息（"This session has been expired ..."），
     * 导致：① 前端 fetch 判断不出 401，页面静默失效、数据不再刷新；
     * ② 浏览器直接访问时用户看不到任何提示。
     *
     * <p>修复后统一语义：
     * <ul>
     *   <li>{@code /api/**} 请求 → 401 + JSON（code=SESSION_EXPIRED），前端据此跳登录页；</li>
     *   <li>页面/静态资源请求 → 302 重定向到 {@code /login?expired}，登录页给出明确提示。</li>
     * </ul>
     * 注：会话在进入本策略前已由 ConcurrentSessionFilter 失效（doLogout），此处只负责响应。
     */
    private SessionInformationExpiredStrategy sessionExpiredStrategy() {
        return event -> respondUnauthenticated(event.getRequest(), event.getResponse(),
            "SESSION_EXPIRED", "登录状态已过期，请重新登录", "/login?expired");
    }

    @Bean
    public HttpSessionEventPublisher httpSessionEventPublisher() {
        return new HttpSessionEventPublisher();
    }

    /**
     * 允许的跨源来源（逗号分隔）。默认空 = 不接受任何跨源。
     *
     * <p>早先这里是「10.*.*.* / 172.16-31.* / 192.168.* 整段内网 + allowCredentials(true)」，
     * 等于内网任意一台机器上的任意页面都能带着已登录 Cookie 读 {@code /api/**}。
     * 前端页面与接口同源，本就不需要 CORS；确需跨源（例如 Grafana 在浏览器端直接查询）时
     * 在 {@code app.cors.allowed-origins} 里显式列出源。
     */
    @Value("${app.cors.allowed-origins:}")
    private List<String> corsAllowedOrigins;

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(
            corsAllowedOrigins == null ? List.of() : corsAllowedOrigins);
        if (configuration.getAllowedOriginPatterns().isEmpty()) {
            log.info("未配置 app.cors.allowed-origins：不接受任何跨源请求（同源前端不受影响）");
        }
        configuration.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(Arrays.asList("*"));
        configuration.setAllowCredentials(true);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }

}
