package com.report.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.security.SecureRandom;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 算术式登录验证码：服务端出「3 + 7 = ?」的图片，正确答案只留在服务端内存里。
 *
 * <p>用纯 JDK（AWT + ImageIO）画，不引第三方验证码库：这套构建是离线 Maven
 * （{@code mvn -o}），为一个验证码往 fat jar 里再塞一个 2MB 依赖、多一条供应链面，不值。
 *
 * <p>一次性：一道题只允许校验一次，对错都立即作废。这是这套防护的要点 ，
 * 否则脚本解一次码就能拿它去试几千个口令。作废后前端必须重新取图
 * （{@code GET /login/captcha} 每次都出一道新题并覆盖 Cookie 里的旧 token）。
 */
@Service
public class CaptchaService {

    private static final Logger log = LoggerFactory.getLogger(CaptchaService.class);

    /** 承载 token 的 Cookie 名。HttpOnly + Path=/login，脚本读不到、也不会跟着其它请求外带。 */
    public static final String COOKIE_NAME = "BILCAPTCHA";

    /** 一道题的有效期：够把题看清、输完、点一次提交。 */
    private static final long TTL_MILLIS = 5 * 60_000L;

    /** 容量上限：刷 {@code GET /login/captcha} 同样会让表无界增长，超限先清过期、仍超则丢最旧。 */
    private static final int MAX_TRACKED = 10_000;

    private static final int WIDTH = 150;
    private static final int HEIGHT = 44;

    private final SecureRandom random = new SecureRandom();
    private final ConcurrentHashMap<String, Entry> store = new ConcurrentHashMap<>();

    /** 一道题：token 写进 Cookie，png 是图片字节。 */
    public record Challenge(String token, byte[] png) {}

    private record Entry(int answer, long createdAt) {}

    /**
     * 出一道新题。
     *
     * <p>画图失败（JVM 缺 PNG writer 等）时抛 {@code IllegalStateException} 让接口回 500 ，
     * 不能「画不出来就当没这回事」，过滤器那边已经在要求验证码了，那样放行等于开一个静默绕过口。
     */
    public Challenge issue() {
        int a = 1 + random.nextInt(9);
        int b = 1 + random.nextInt(9);
        String text;
        int answer;
        switch (random.nextInt(3)) {
            case 0 -> {
                answer = a + b;
                text = a + " + " + b + " = ?";
            }
            case 1 -> {
                // 保证不为负：终端屏上「-4」的负号容易看漏，白涨答错率
                int hi = Math.max(a, b), lo = Math.min(a, b);
                answer = hi - lo;
                text = hi + " - " + lo + " = ?";
            }
            default -> {
                a = 2 + random.nextInt(8);
                b = 2 + random.nextInt(8);
                answer = a * b;
                // 用 ASCII 的 x 而不是「×」：整串文字保持纯 ASCII，就不依赖任何字形是否装了在这台机器上。
                // 生产是另一台机器，而这里画不出来的后果不是「难看」，是**那 1/3 的人根本登不进系统**。
                text = a + " x " + b + " = ?";
            }
        }

        String token = newToken();
        store.put(token, new Entry(answer, System.currentTimeMillis()));
        if (store.size() > MAX_TRACKED) prune();

        return new Challenge(token, draw(text));
    }

    /**
     * 校验答案，无论对错都先作废这道题。
     *
     * <p>{@code remove} 的原子性顺带解决并发：两个请求拿同一个 token 同时提交，
     * 只有一个能取到答案，另一个读到 null 直接算错。
     *
     * @return 题目此前有效且答案正确
     */
    public boolean verify(String token, String input) {
        if (token == null || token.isBlank() || input == null) return false;
        Entry e = store.remove(token);
        if (e == null) return false;
        if (System.currentTimeMillis() - e.createdAt() > TTL_MILLIS) return false;
        String s = input.trim();
        if (s.isEmpty() || s.length() > 4) return false;
        try {
            return Integer.parseInt(s) == e.answer();
        } catch (NumberFormatException notNumeric) {
            return false;
        }
    }

    /**
     * 只给测试用的答案窥视口：读出某道题的正确答案，不消费它。
     *
     * <p>存在的唯一理由：集成测试要验证「填对了就能登进去」这条正向路径，而答案按设计
     * 只存在服务端内存里，客户端可见的东西（图片字节、Cookie、HTML）一个都不该有它。
     *
     * <p>注意：绝对不要把它接到任何 Controller / 接口上，也不要在生产代码路径里调用 ，
     * 那等于把整层验证码防护原地作废。它只在进程内可达，没有任何 HTTP 面暴露它。
     */
    public Integer peekAnswerForTest(String token) {
        if (token == null) return null;
        Entry e = store.get(token);
        if (e == null || System.currentTimeMillis() - e.createdAt() > TTL_MILLIS) return null;
        return e.answer();
    }

    private void prune() {
        long now = System.currentTimeMillis();
        store.entrySet().removeIf(e -> now - e.getValue().createdAt() > TTL_MILLIS);
        if (store.size() > MAX_TRACKED) {
            int overflow = store.size() - MAX_TRACKED;
            store.keySet().stream().limit(overflow).forEach(store::remove);
            log.warn("验证码记录清理后仍超过 {} 条，已丢弃 {} 条（异常高频取码？）", MAX_TRACKED, overflow);
        }
    }

    private String newToken() {
        byte[] buf = new byte[18];
        random.nextBytes(buf);
        StringBuilder sb = new StringBuilder(buf.length * 2);
        for (byte x : buf) {
            sb.append(Character.forDigit((x >> 4) & 0xF, 16)).append(Character.forDigit(x & 0xF, 16));
        }
        return sb.toString();
    }

    private byte[] draw(String text) {
        BufferedImage img = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(new Color(245, 247, 251));
            g.fillRect(0, 0, WIDTH, HEIGHT);
            for (int i = 0; i < 5; i++) {
                g.setColor(randomColor(150, 210));
                g.drawLine(random.nextInt(WIDTH), random.nextInt(HEIGHT),
                    random.nextInt(WIDTH), random.nextInt(HEIGHT));
            }
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 26));
            java.awt.FontMetrics fm = g.getFontMetrics();
            int x = Math.max(4, (WIDTH - fm.stringWidth(text)) / 2);
            int baseY = (HEIGHT - fm.getHeight()) / 2 + fm.getAscent();
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                int cw = fm.charWidth(c);
                if (c != ' ') {
                    AffineTransform before = g.getTransform();
                    double rot = (random.nextInt(21) - 10) / 100.0;   // ±0.1 rad：看得清，不好自动识别
                    g.rotate(rot, x + cw / 2.0, baseY - fm.getAscent() / 2.0);
                    g.setColor(randomColor(20, 90));
                    g.drawString(String.valueOf(c), x, baseY + random.nextInt(5) - 2);
                    g.setTransform(before);
                }
                x += cw;
            }
            for (int i = 0; i < 120; i++) {
                g.setColor(randomColor(120, 220));
                g.fillRect(random.nextInt(WIDTH), random.nextInt(HEIGHT), 1, 1);
            }
        } finally {
            g.dispose();
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try {
            if (!ImageIO.write(img, "png", bos)) {
                throw new IllegalStateException("当前 JVM 没有可用的 PNG writer，无法生成验证码");
            }
        } catch (java.io.IOException e) {
            throw new IllegalStateException("验证码图片写出失败: " + e.getMessage(), e);
        }
        return bos.toByteArray();
    }

    private Color randomColor(int min, int max) {
        int span = max - min + 1;
        return new Color(min + random.nextInt(span), min + random.nextInt(span), min + random.nextInt(span));
    }
}
