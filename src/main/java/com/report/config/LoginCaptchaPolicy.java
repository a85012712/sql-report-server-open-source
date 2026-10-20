package com.report.config;

import com.report.util.ClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 「这一次登录要不要先填验证码」的唯一判据。
 *
 * <p>为什么要单拆一个 bean：登录页（决定要不要画出验证码框）和 {@code LoginLockFilter}
 * （决定要不要真的校验）必须给出完全相同的答案。两处各写一遍的话，
 * 漂移出来的症状是「页面上没有验证码框，提交却一直被拒」， 用户只能干瞪眼，
 * 而这恰好是本仓库反复栽过的「清单写两份必然漂移」那一类。
 *
 * <p>注意：回环豁免有一条硬前提：{@code captcha-exempt-loopback=true}（默认）时，
 * 从 {@code 127.0.0.1} 进来的请求不要求验证码，理由和既有的 IP 限速豁免一致 ，
 * 本机自检与 {@code tools/} 下十几个门禁探针都从回环连续登录临时账号。
 * 判据取 TCP 对端地址（{@link ClientIpResolver#peerAddr}），不受任何请求头影响、不可伪造。
 * 但如果哪天 BI 前面挂了同一台机器上的反向代理（nginx 与 jar 同机），
 * 所有请求的对端都会变成 127.0.0.1，这层防护就静默归零。那种部署必须显式设成 false。
 */
@Component
public class LoginCaptchaPolicy {

    private final LoginAttemptService loginAttemptService;
    private final ClientIpResolver clientIpResolver;

    @Value("${app.security.login.captcha-exempt-loopback:true}")
    private boolean exemptLoopback;

    public LoginCaptchaPolicy(LoginAttemptService loginAttemptService,
                              ClientIpResolver clientIpResolver) {
        this.loginAttemptService = loginAttemptService;
        this.clientIpResolver = clientIpResolver;
    }

    /** 带工号的判定（POST /login 用：工号维度和 IP 维度取或）。 */
    public boolean required(HttpServletRequest request, String username) {
        if (!loginAttemptService.isCaptchaEnabled()) return false;
        if (exemptLoopback && clientIpResolver.isLoopback(request)) return false;
        return loginAttemptService.isCaptchaRequired(username, clientIpResolver.resolve(request));
    }

    /** 只看来源 IP 的判定（登录页刚打开、还不知道工号时用）。 */
    public boolean requiredByClientIp(HttpServletRequest request) {
        return required(request, null);
    }
}
