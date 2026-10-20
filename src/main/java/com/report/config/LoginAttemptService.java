package com.report.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 登录失败锁定：同一用户名在时间窗口内失败次数超限则暂时拒绝登录。
 *
 * <p>记录条数有上限：此前只在「读到」时才清理过期项，用随机用户名刷登录可以让
 * {@code attempts} 无界增长（内网服务不重启就是慢性内存泄漏）。
 */
@Service
public class LoginAttemptService {

    private static final Logger log = LoggerFactory.getLogger(LoginAttemptService.class);

    /** 锁定记录上限，超过即清理已过期项；仍不够则整表清空（宁可放行一次登录，不可 OOM）。 */
    private static final int MAX_TRACKED = 10_000;

    /** IP 登录尝试的固定窗口长度。 */
    private static final long IP_WINDOW_MILLIS = 60_000L;

    /**
     * IP 失败计数的窗口长度。和上面那个「尝试」窗口刻意分开：
     * 尝试窗口是限速用的（每分钟重置），失败窗口是决定「这台终端要不要开始填验证码」的，
     * 一分钟就衰减等于没有。取 5 分钟，共用终端上两个人各打错一次就触发，
     * 但也只在 5 分钟内触发，不会把一个科室永久变成要验证码。
     */
    private static final long IP_FAIL_WINDOW_MILLIS = 5 * 60_000L;

    private final ConcurrentHashMap<String, Attempt> attempts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, IpWindow> ipWindows = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, IpFailures> ipFailures = new ConcurrentHashMap<>();

    @Value("${app.security.login.max-failures:5}")
    private int maxFailures;

    @Value("${app.security.login.lock-minutes:15}")
    private int lockMinutes;

    /** 单 IP 每分钟允许的登录尝试次数（表单登录不走 /api/** 限流拦截器，故在此单独限速）。 */
    @Value("${app.security.login.per-ip-per-minute:10}")
    private int maxPerIpPerWindow;

    /**
     * 失败几次之后开始要求验证码。撞库的真实形状是「对每个工号只试一次初始口令」，
     * 单工号计数永远到不了 {@code maxFailures}，此前只能靠 IP 限速兜着；
     * 这条阈值补的是「慢速脚本一小时试 540 个工号全程零打扰」那个洞。
     * {@code <= 0} 表示整个验证码功能关闭。
     */
    @Value("${app.security.login.captcha-after-failures:2}")
    private int captchaAfterFailures;

    /** 验证码功能的总开关（{@code captcha-after-failures <= 0} 即关闭）。 */
    public boolean isCaptchaEnabled() {
        return captchaAfterFailures > 0;
    }

    /**
     * 是否需要先填验证码才对这次登录放行。
     *
     * <p>两个维度取「或」：工号维度管「盯着一个账号试」，IP 维度管「一个密码试一片账号」。
     * 回环豁免不在这里判（那是「谁在问」而不是「状态如何」），由 {@code LoginCaptchaPolicy} 统一判，
     * 免得页面渲染和过滤器两处各写一遍、将来漂移。
     */
    public boolean isCaptchaRequired(String username, String clientIp) {
        if (captchaAfterFailures <= 0) return false;
        if (username != null && !username.isBlank()) {
            Attempt a = attempts.get(normalize(username));
            if (a != null && a.failures.get() >= captchaAfterFailures) return true;
        }
        if (clientIp != null && !clientIp.isBlank()) {
            IpFailures f = ipFailures.get(clientIp);
            if (f != null && System.currentTimeMillis() - f.windowStart <= IP_FAIL_WINDOW_MILLIS
                && f.count.get() >= captchaAfterFailures) {
                return true;
            }
        }
        return false;
    }

    /** 只按来源 IP 判（登录页刚打开、还不知道工号时用它决定要不要画出验证码框）。 */
    public boolean isCaptchaRequiredForIp(String clientIp) {
        return isCaptchaRequired(null, clientIp);
    }

    /**
     * 记一次来自某 IP 的登录失败，喂给 {@link #isCaptchaRequired} 的 IP 维度。
     *
     * <p>和用户名维度一样只在失败时累加；成功登录不清它（否则持有任意一个有效账号的人
     * 可以拿自己的正常登录把这台终端的失败记录一笔抹掉）。
     */
    public void noteLoginFailureFromIp(String clientIp) {
        if (clientIp == null || clientIp.isBlank()) return;
        long now = System.currentTimeMillis();
        ipFailures.compute(clientIp, (k, existing) -> {
            if (existing == null || now - existing.windowStart > IP_FAIL_WINDOW_MILLIS) {
                IpFailures fresh = new IpFailures(now);
                fresh.count.incrementAndGet();
                return fresh;
            }
            existing.count.incrementAndGet();
            return existing;
        });
        if (ipFailures.size() > MAX_TRACKED) {
            ipFailures.entrySet().removeIf(e ->
                now - e.getValue().windowStart > IP_FAIL_WINDOW_MILLIS);
        }
    }

    public boolean isLocked(String username) {
        if (username == null || username.isBlank()) return false;
        Attempt a = attempts.get(normalize(username));
        if (a == null) return false;
        if (a.lockUntilMs > 0 && System.currentTimeMillis() < a.lockUntilMs) {
            return true;
        }
        if (a.lockUntilMs > 0 && System.currentTimeMillis() >= a.lockUntilMs) {
            attempts.remove(normalize(username));
        }
        return false;
    }

    public void loginSucceeded(String username) {
        if (username == null) return;
        attempts.remove(normalize(username));
    }

    /**
     * 主动解除某账号的登录失败锁定。
     *
     * <p>给「管理员重置口令」用：用户往往是连错几次被锁 + 又忘了密码才来找管理员的，
     * 而锁定只在登录成功或 {@code lock-minutes} 到期后才自然恢复。不解除的话，
     * 重置接口返回成功、界面也报成功，用户却照样登不进去，只能再等最长 15 分钟，
     * 而且没人知道要等多久（现象会被归因成"重置没生效"）。
     */
    public void clearLock(String username) {
        if (username == null || username.isBlank()) return;
        attempts.remove(normalize(username));
    }

    public void loginFailed(String username) {
        if (username == null || username.isBlank()) return;
        String key = normalize(username);
        Attempt a = attempts.compute(key, (k, existing) -> {
            if (existing == null || (existing.lockUntilMs > 0 && System.currentTimeMillis() >= existing.lockUntilMs)) {
                return new Attempt();
            }
            return existing;
        });
        int n = a.failures.incrementAndGet();
        if (n >= maxFailures) {
            a.lockUntilMs = System.currentTimeMillis() + lockMinutes * 60_000L;
        }
        if (attempts.size() > MAX_TRACKED) pruneExpired();
    }

    /** 清掉锁定期已满、且窗口内已无有效失败计数的记录。 */
    private void pruneExpired() {
        long now = System.currentTimeMillis();
        attempts.entrySet().removeIf(e ->
            e.getValue().lockUntilMs > 0 && now >= e.getValue().lockUntilMs);
        if (attempts.size() > MAX_TRACKED) {
            int overflow = attempts.size() - MAX_TRACKED;
            attempts.keySet().stream().limit(overflow).forEach(attempts::remove);
            log.warn("登录失败记录仍超过 {} 条，已按插入顺序丢弃 {} 条（异常高频登录尝试？）",
                MAX_TRACKED, overflow);
        }
    }

    public long remainingLockSeconds(String username) {
        Attempt a = attempts.get(normalize(username));
        if (a == null || a.lockUntilMs <= 0) return 0;
        long sec = (a.lockUntilMs - System.currentTimeMillis() + 999) / 1000;
        return Math.max(0, sec);
    }

    /**
     * 记一次来自某 IP 的登录尝试，并回答「这个 IP 一分钟内还能不能再试」。
     *
     * <p>账号锁定只按用户名计数，挡不住同一口令对 4 位数字工号段的遍历；
     * 而限流拦截器只挂在 {@code /api/**} 上，表单登录走的是 Security 过滤器、
     * 根本进不了拦截器，于是 {@code POST /login} 此前完全不限速。
     */
    public boolean noteLoginAttempt(String clientIp) {
        if (clientIp == null || clientIp.isBlank()) return true;
        long now = System.currentTimeMillis();
        long windowStart = now - (now % IP_WINDOW_MILLIS);
        IpWindow w = ipWindows.compute(clientIp, (k, existing) ->
            (existing == null || existing.windowStart != windowStart)
                ? new IpWindow(windowStart) : existing);
        boolean allowed = w.count.incrementAndGet() <= maxPerIpPerWindow;
        if (ipWindows.size() > MAX_TRACKED) {
            ipWindows.entrySet().removeIf(e -> e.getValue().windowStart != windowStart);
        }
        return allowed;
    }

    private static final class IpWindow {
        final long windowStart;
        final AtomicInteger count = new AtomicInteger(0);
        IpWindow(long windowStart) { this.windowStart = windowStart; }
    }

    private static final class IpFailures {
        final long windowStart;
        final AtomicInteger count = new AtomicInteger(0);
        IpFailures(long windowStart) { this.windowStart = windowStart; }
    }

    private static String normalize(String username) {
        return username.trim().toLowerCase();
    }

    private static final class Attempt {
        final AtomicInteger failures = new AtomicInteger(0);
        volatile long lockUntilMs;
    }
}
