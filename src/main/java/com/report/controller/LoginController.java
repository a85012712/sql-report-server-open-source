package com.report.controller;

import com.report.config.LoginCaptchaPolicy;
import com.report.service.CaptchaService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 登录页与登录验证码。
 *
 * <p>原来 {@code /login} 是 {@code WebConfig} 里一行 {@code addViewController} 的纯视图路由，
 * 现在改成这里的两个读接口：渲染时需要问一句「这台终端/这个工号现在要不要验证码」，
 * 而 ViewController 拿不到 model，也没法注入依赖。路径与行为（{@code permitAll}、表单字段）都没变，
 * {@code formLogin().loginPage("/login")} 仍然指向同一个 URL。
 *
 * <p>注意：本目录的注释里不要写出 Mapping 注解的字面量：
 * {@code tools/check-docs-consistency.mjs} 数端点是对 controller 目录整体做正则，
 * 一句注释里的字面量会被当成一个真端点，把文档里的「N 个端点」悄悄顶高一个 ，
 * 加这个类时就把 87 数成了 88（注释已改、解析器也已加剥注释，两侧都修）。
 */
@Controller
public class LoginController {

    private final LoginCaptchaPolicy captchaPolicy;
    private final CaptchaService captchaService;

    public LoginController(LoginCaptchaPolicy captchaPolicy, CaptchaService captchaService) {
        this.captchaPolicy = captchaPolicy;
        this.captchaService = captchaService;
    }

    @GetMapping("/login")
    public String loginPage(HttpServletRequest request, Model model) {
        // 还不知道工号，先按来源 IP 判；工号维度由失败重定向带上 ?cap=1 显式传回来
        // （见 SecurityConfig 的失败处理器）。两个入口的判据都走 LoginCaptchaPolicy，不在这里重算。
        model.addAttribute("captchaRequired",
            captchaPolicy.requiredByClientIp(request) || "1".equals(request.getParameter("cap")));
        return "login";
    }

    /**
     * 出一张算术式验证码图片：每次调用都是一道新题，并覆盖 Cookie 里的 token。
     *
     * <p>答案只存在服务端内存（{@link CaptchaService}），既不写进 HTML、也不藏在图片文件名或
     * 任何一个客户端可见的字段里，页面上只有一个 {@code <img>}。
     */
    @GetMapping("/login/captcha")
    public ResponseEntity<byte[]> captcha() {
        CaptchaService.Challenge c = captchaService.issue();

        // Path=/login：只在登录这一段路径上带，API 请求不必跟着多一个 cookie。
        // 按 RFC 6265 的路径匹配，/login 与 /login/captcha 都能收到它。
        // 用 ResponseCookie 而不是 servlet Cookie + 手写 Set-Cookie：后者要把容器
        // (Tomcat Rfc6265CookieProcessor) 的属性拼法自己扛一遍，SameSite 这类扩展属性最容易写歪。
        ResponseCookie cookie = ResponseCookie.from(CaptchaService.COOKIE_NAME, c.token())
            .httpOnly(true)
            .path("/login")
            .maxAge(java.time.Duration.ofSeconds(300))
            .sameSite("Lax")
            .build();

        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.IMAGE_PNG);
        h.setCacheControl(CacheControl.noStore());
        h.add(HttpHeaders.SET_COOKIE, cookie.toString());
        return new ResponseEntity<>(c.png(), h, org.springframework.http.HttpStatus.OK);
    }
}
