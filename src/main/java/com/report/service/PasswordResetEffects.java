package com.report.service;

import com.report.config.LoginAttemptService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * 管理员重置 / 代设口令之后的两项收尾动作。
 *
 * <p>为什么单独成类而不是塞进 {@link UserManagementService}：这两件事一个属于登录限速、
 * 一个属于 Spring Security 的会话注册表，都与会话/登录基础设施有关，而
 * {@code SecurityConfig} 已经依赖 {@code UserManagementService}。反向注入会形成配置环，
 * 抽到这里的依赖关系就始终是单向的（控制器 → 本服务 → 基础设施）。
 *
 * <p>也不在两个控制器里各写一遍：重置与代设口令是两条入口（{@code /reset-password}
 * 与 {@code /password}），行为必须一致，这正是本仓库刚清理过的那类重复。
 */
@Service
public class PasswordResetEffects {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetEffects.class);

    private final LoginAttemptService loginAttemptService;
    private final SessionRegistry sessionRegistry;
    private final org.springframework.security.core.userdetails.UserDetailsService userDetailsService;

    public PasswordResetEffects(LoginAttemptService loginAttemptService,
                                SessionRegistry sessionRegistry,
                                org.springframework.security.core.userdetails.UserDetailsService userDetailsService) {
        this.loginAttemptService = loginAttemptService;
        this.sessionRegistry = sessionRegistry;
        this.userDetailsService = userDetailsService;
    }

    /**
     * 解除该账号的登录失败锁定，并吊销它已登录的会话。
     *
     * @param username 被重置口令的工号
     * @param request  发起操作的请求，管理员自己的会话不能被踢，否则给自己
     *                 改完口令会当场掉线（请求还在处理中）。会话 id 在这里统一取，
     *                 免得两个控制器各写一份同样的三行取值代码。
     */
    public void afterPasswordReset(String username, jakarta.servlet.http.HttpServletRequest request) {
        loginAttemptService.clearLock(username);

        jakarta.servlet.http.HttpSession actor = request == null ? null : request.getSession(false);
        String actorSessionId = actor == null ? null : actor.getId();

        int expired = 0;
        for (SessionInformation info : sessionsOf(username)) {
            if (actorSessionId != null && actorSessionId.equals(info.getSessionId())) {
                continue;
            }
            info.expireNow();
            expired++;
        }
        if (expired > 0) {
            // 注意：刻意不用 [AUDIT] 前缀：这个动作已经由调用方记了一条
            // （resetPassword / adminChangePassword），再来一条就是「一次操作两行台账」，
            // 正是本仓库此前清掉的那类重复。这里只留运维线索。
            // 也刻意不打印会话 id，它等同于可重放的凭据。
            log.info("重置口令后吊销了该账号的已登录会话: target={}, sessions={}", username, expired);
        }
    }

    /**
     * 取该工号当前登记的会话。
     *
     * <p>这里不能用 {@code getAllSessions(username, false)}：实测（见
     * {@code PasswordResetEffectsIntegrationTest} 所钉的行为）Spring Security 的
     * {@code SessionRegistry} 是以 **principal 对象**为键的，按工号字符串查永远返回空列表 ，
     * 代码不报错、日志也不响，只是踢会话彻底空转。必须先取出该用户的 {@code UserDetails}
     * 再查。
     *
     * <p>能这么查的前提：{@code User.equals/hashCode} 只比 用户名 + 权限，不含口令
     * （已实测：改完口令后新装载出来的 UserDetails 仍能命中同一条会话）。所以本方法在
     * 「口令已被改掉」之后调用依然有效；但若将来改成按角色查（角色变了 equals 就不等了），
     * 这条前提会被破坏，届时应改为自行维护 工号→会话id 的映射。
     */
    private java.util.List<SessionInformation> sessionsOf(String username) {
        try {
            UserDetails principal = userDetailsService.loadUserByUsername(username);
            return sessionRegistry.getAllSessions(principal, false);
        } catch (UsernameNotFoundException e) {
            return java.util.List.of();   // 账号已不存在，自然也没有会话可踢
        }
    }
}
