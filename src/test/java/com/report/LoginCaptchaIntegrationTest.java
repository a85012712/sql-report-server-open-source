package com.report;

import com.report.config.LoginAttemptService;
import com.report.service.CaptchaService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 登录验证码（连错才要 + 算术式图片）的行为契约。
 *
 * <p>注意：全部非回环用例都用 {@code 192.0.2.x} 这类合成来源地址，且每个用例各用一个：
 * 失败计数是按 IP 累积的（5 分钟窗口），共用地址会让用例互相污染。
 * MockMvc 默认的 {@code 127.0.0.1} 走的是回环豁免，所以正向路径的收口登录故意留在默认地址上做
 *，那同时也在断言「错误验证码没能把账号锁掉」。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LoginCaptchaIntegrationTest {

    private static final String ADMIN = "admin";
    private static final String ADMIN_PW = "AdminTest1";
    private static final String BAD_PW = "wrong-password-xx";

    @Autowired private MockMvc mockMvc;
    @Autowired private LoginAttemptService attemptService;
    @Autowired private CaptchaService captchaService;

    @BeforeEach
    void resetAccountCounters() {
        // 工号维度的失败计数在用例之间是共享的（同一个 ApplicationContext），
        // 上一个用例留下的 2 次失败会让本用例第一次登录就要求验证码。
        attemptService.clearLock(ADMIN);
    }

    /** 把来源地址换成一个内网局域网地址，回环豁免走的是 TCP 对端，改头没用，只能改对端。 */
    private static RequestPostProcessor from(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    private static String tokenFrom(MvcResult result) {
        String setCookie = result.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertNotNull(setCookie, "取码接口必须下发验证码 Cookie");
        String prefix = CaptchaService.COOKIE_NAME + "=";
        int i = setCookie.indexOf(prefix);
        assertTrue(i >= 0, "Set-Cookie 里要有验证码 token：" + setCookie);
        int start = i + prefix.length();
        int end = setCookie.indexOf(';', start);
        return end < 0 ? setCookie.substring(start) : setCookie.substring(start, end);
    }

    /** 从非回环地址连错两次，把这台终端/这个工号推进「要验证码」状态。 */
    private void failTwiceFrom(String ip) throws Exception {
        for (int n = 0; n < 2; n++) {
            mockMvc.perform(post("/login").with(csrf()).with(from(ip))
                    .param("username", ADMIN).param("password", BAD_PW))
                .andExpect(unauthenticated());
        }
    }

    private String issueToken(String ip) throws Exception {
        MvcResult r = mockMvc.perform(get("/login/captcha").with(from(ip))).andExpect(status().isOk()).andReturn();
        return tokenFrom(r);
    }

    @Test
    @DisplayName("回环来源永不要求验证码：这是 14 个门禁探针能照常登录的前提")
    void loopback_neverNeedsCaptcha() throws Exception {
        failTwiceFrom("127.0.0.1");
        mockMvc.perform(post("/login").with(csrf())
                .param("username", ADMIN).param("password", ADMIN_PW))
            .andExpect(authenticated().withUsername(ADMIN));
    }

    @Test
    @DisplayName("新客户端首登不打扰：没失败记录时不校验验证码、也不画验证码框")
    void freshClient_firstLoginNeedsNoCaptcha() throws Exception {
        String ip = "192.0.2.21";
        MvcResult page = mockMvc.perform(get("/login").with(from(ip)))
            .andExpect(status().isOk())
            // 断言里用 name="captcha"（表单元件），不用 captchaImg，页面底部那段「换一张」脚本
            // 无条件引用 captchaImg 这个 id，用它当判据会让「没渲染」也测成「渲染了」。
            .andExpect(content().string(org.hamcrest.Matchers.not(
                org.hamcrest.Matchers.containsString("name=\"captcha\""))))
            .andReturn();
        assertFalse(page.getResponse().getHeaderNames().stream()
            .anyMatch(h -> h.equalsIgnoreCase(HttpHeaders.SET_COOKIE)), "首屏不该顺带下发验证码 Cookie");

        mockMvc.perform(post("/login").with(csrf()).with(from(ip))
                .param("username", ADMIN).param("password", ADMIN_PW))
            .andExpect(authenticated().withUsername(ADMIN));
    }

    @Test
    @DisplayName("连错 2 次之后：口令对但不带验证码 → 拒（防撞库的那一刀就落在这）")
    void afterTwoFailures_correctPasswordWithoutCaptcha_isRejected() throws Exception {
        String ip = "192.0.2.42";
        failTwiceFrom(ip);
        mockMvc.perform(post("/login").with(csrf()).with(from(ip))
                .param("username", ADMIN).param("password", ADMIN_PW))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/login?caperr=1&cap=1"))
            .andExpect(unauthenticated());
    }

    @Test
    @DisplayName("连错 2 次之后：带上正确验证码 → 放行")
    void afterTwoFailures_correctCaptchaLogsIn() throws Exception {
        String ip = "192.0.2.63";
        failTwiceFrom(ip);
        String token = issueToken(ip);
        Integer answer = captchaService.peekAnswerForTest(token);
        assertNotNull(answer, "测试窥视口应能读到答案（它不经过任何 HTTP 面）");

        mockMvc.perform(post("/login").with(csrf()).with(from(ip))
                .cookie(new jakarta.servlet.http.Cookie(CaptchaService.COOKIE_NAME, token))
                .param("username", ADMIN).param("password", ADMIN_PW)
                .param("captcha", String.valueOf(answer)))
            .andExpect(authenticated().withUsername(ADMIN));
    }

    @Test
    @DisplayName("一道题只能用一次：解对了也不能拿去连着试第二个口令")
    void captchaIsOneTimeUse() throws Exception {
        String ip = "192.0.2.84";
        failTwiceFrom(ip);
        String token = issueToken(ip);
        Integer answer = captchaService.peekAnswerForTest(token);
        assertNotNull(answer);

        // 第一次：验证码对、口令错，走正常认证失败，但这道题已经被消费掉
        mockMvc.perform(post("/login").with(csrf()).with(from(ip))
                .cookie(new jakarta.servlet.http.Cookie(CaptchaService.COOKIE_NAME, token))
                .param("username", ADMIN).param("password", BAD_PW)
                .param("captcha", String.valueOf(answer)))
            .andExpect(unauthenticated());

        // 第二次：拿同一道题、同一份 Cookie 再试正确口令，必须被拒
        mockMvc.perform(post("/login").with(csrf()).with(from(ip))
                .cookie(new jakarta.servlet.http.Cookie(CaptchaService.COOKIE_NAME, token))
                .param("username", ADMIN).param("password", ADMIN_PW)
                .param("captcha", String.valueOf(answer)))
            .andExpect(redirectedUrl("/login?caperr=1&cap=1"))
            .andExpect(unauthenticated());
    }

    @Test
    @DisplayName("乱发错码不记到工号头上：否则知道一个工号就能把同事锁 15 分钟")
    void wrongCaptchaSpam_doesNotLockTheAccount() throws Exception {
        String ip = "192.0.2.105";
        // 先把这台终端推进「要验证码」状态，不这么做的话过滤器根本不校验验证码，
        // 那 5 次带错码的 POST 会拿着正确口令直接登进去（第一版用例就是这么错的）。
        failTwiceFrom(ip);
        for (int n = 0; n < 5; n++) {
            String token = issueToken(ip);
            mockMvc.perform(post("/login").with(csrf()).with(from(ip))
                    .cookie(new jakarta.servlet.http.Cookie(CaptchaService.COOKIE_NAME, token))
                    .param("username", ADMIN).param("password", ADMIN_PW)
                    .param("captcha", "-999"))
                .andExpect(unauthenticated());
        }
        assertFalse(attemptService.isLocked(ADMIN), "错码不该累加成账号锁定");
        // 被骚扰的人随后自己登录，不该因为别人乱发错码而被卡住
        mockMvc.perform(post("/login").with(csrf())
                .param("username", ADMIN).param("password", ADMIN_PW))
            .andExpect(authenticated().withUsername(ADMIN));
    }

    @Test
    @DisplayName("验证码接口：匿名可取、PNG、no-store、答案不落任何客户端可见处")
    void captchaEndpoint_isAnonymousPngNoStoreAndLeaksNothing() throws Exception {
        String ip = "192.0.2.126";
        MvcResult r = mockMvc.perform(get("/login/captcha").with(from(ip)))
            .andExpect(status().isOk())
            .andExpect(header().string(HttpHeaders.CONTENT_TYPE, org.hamcrest.Matchers.containsString("image/png")))
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, org.hamcrest.Matchers.containsString("no-store")))
            .andReturn();

        String token = tokenFrom(r);
        Integer answer = captchaService.peekAnswerForTest(token);
        assertNotNull(answer);
        // 答案空间只有 0~81，看着不大，但一道题只能用一次，穷举就得每次重新取图、
        // 重新吃一遍 IP 限速与失败计数，这才是这层真正抬高的成本。
        assertTrue(answer >= 0 && answer <= 81, "答案应落在算术式取值范围内，实际=" + answer);

        String setCookie = r.getResponse().getHeader(HttpHeaders.SET_COOKIE);
        assertTrue(setCookie.contains("HttpOnly"), "token Cookie 必须 HttpOnly：" + setCookie);
        assertTrue(setCookie.contains("Path=/login"), "token Cookie 要限在 /login 路径下：" + setCookie);
        assertTrue(setCookie.contains("SameSite=Lax"), "要与会话 Cookie 的 SameSite 口径一致：" + setCookie);
        // token 必须是一段不透明的随机十六进制，它本身不编码答案。
        // （原先写成 setCookie.contains(String.valueOf(answer))：答案是 0~81 的小整数，
        //  36 位十六进制里蒙到 "24" 这种子串的概率高得很，那条断言是个掷骰子的假警报。）
        assertTrue(token.matches("[0-9a-f]{36}"), "token 应是不透明随机串：" + token);
        assertFalse(setCookie.contains("answer="), "Set-Cookie 里不该有答案字段：" + setCookie);

        byte[] png = r.getResponse().getContentAsByteArray();
        assertTrue(png.length > 200, "PNG 不该是空图");
        assertEquals(0x89, png[0] & 0xFF);
        assertEquals('P', png[1]);
        assertEquals('N', png[2]);
        assertEquals('G', png[3]);
        String body = new String(png, java.nio.charset.StandardCharsets.ISO_8859_1);
        assertFalse(body.contains("answer="), "图片字节里不该夹答案字段");

        // 页面本身也没有答案：把登录页正文捞出来，答案不该以任何形式出现在里面
        String page = mockMvc.perform(get("/login").with(from(ip)).param("cap", "1"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        assertTrue(page.contains("name=\"captcha\""), "带 cap=1 时必须渲染验证码框");
        assertFalse(page.contains("peekAnswer"), "页面里不该出现任何服务端答案字段");
    }

    @Test
    @DisplayName("页面渲不渲染验证码框，与过滤器校不校验，必须同一个判据")
    void pageAndFilterAgreeOnRequirement() throws Exception {
        String ip = "192.0.2.147";
        mockMvc.perform(get("/login").with(from(ip)))
            .andExpect(content().string(org.hamcrest.Matchers.not(
                org.hamcrest.Matchers.containsString("name=\"captcha\""))));

        failTwiceFrom(ip);

        // 失败重定向回来的地址带 cap=1；同时 IP 维度也已经到位，两条判据这里应当一致
        mockMvc.perform(get("/login").with(from(ip)).param("error", "").param("cap", "1"))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"captcha\"")));
    }
}
