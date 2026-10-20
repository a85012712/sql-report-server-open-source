import com.report.util.SecretCrypto;

import java.util.Base64;
import java.security.SecureRandom;

/**
 * 凭据加解密维护工具（AES-256-GCM，与运行时使用同一份 SecretCrypto 实现，保证互通）。
 *
 * <p>典型用途：首次部署生成密钥、密钥轮换、把明文口令转为 enc: 密文。
 *
 * <h3>编译与运行（在项目根目录执行）</h3>
 * <pre>
 * javac -encoding UTF-8 -d target/tools src/main/java/com/report/util/SecretCrypto.java tools/SecretKeyTool.java
 *
 * :: 1) 生成新的 APP_CRYPTO_KEY（写入 secret.key）
 * java -cp target/tools SecretKeyTool gen
 *
 * :: 2) 用指定密钥加密口令，输出可直接粘贴到 .env 的 KEY=enc:... 行
 * java -cp target/tools SecretKeyTool enc &lt;APP_CRYPTO_KEY&gt; DB_PASSWORD=真实口令 ADMIN_PASSWORD=真实口令
 *
 * :: 3) 自检：确认密钥能解开已有密文
 * java -cp target/tools SecretKeyTool dec &lt;APP_CRYPTO_KEY&gt; enc:xxxxxxxx
 * </pre>
 *
 * <p>生产机通常只有交付包、没有源码树：把上面两条 javac 的产物
 * （{@code SecretKeyTool.class} 与 {@code com/report/util/SecretCrypto.class}）连同本目录一起
 * 放到部署目录，即可 {@code java -cp tools SecretKeyTool gen}。
 * 本类不参与打包，可执行 jar 里没有它。
 */
public class SecretKeyTool {

    public static void main(String[] args) {
        if (args.length == 0) {
            usage();
            return;
        }
        switch (args[0]) {
            case "gen" -> {
                byte[] raw = new byte[32];
                new SecureRandom().nextBytes(raw);
                System.out.println("APP_CRYPTO_KEY=" + Base64.getEncoder().encodeToString(raw));
            }
            case "enc" -> {
                if (args.length < 3) { usage(); return; }
                SecretCrypto c = new SecretCrypto(args[1]);
                for (int i = 2; i < args.length; i++) {
                    int eq = args[i].indexOf('=');
                    if (eq <= 0) {
                        System.err.println("跳过非法参数（应为 NAME=值）: " + args[i]);
                        continue;
                    }
                    System.out.println(args[i].substring(0, eq) + "="
                        + c.encryptIfNeeded(args[i].substring(eq + 1)));
                }
            }
            case "dec" -> {
                if (args.length < 3) { usage(); return; }
                SecretCrypto c = new SecretCrypto(args[1]);
                for (int i = 2; i < args.length; i++) {
                    try {
                        System.out.println(c.decryptIfNeeded(args[i]));
                    } catch (Exception e) {
                        System.err.println("解密失败: " + e.getMessage());
                    }
                }
            }
            default -> usage();
        }
    }

    private static void usage() {
        System.out.println("用法:");
        System.out.println("  SecretKeyTool gen");
        System.out.println("  SecretKeyTool enc <APP_CRYPTO_KEY> NAME=明文 [NAME=明文 ...]");
        System.out.println("  SecretKeyTool dec <APP_CRYPTO_KEY> enc:密文 [enc:密文 ...]");
    }
}
