package com.report.config;

import com.report.util.SecretCrypto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;

import java.util.ArrayList;
import java.util.List;

/**
 * 用户详情与密码编码器独立配置，避免与 SecurityFilterChain / Filter 循环依赖。
 *
 * <p>口令来源（环境变量 {@code ADMIN_PASSWORD} / {@code app.security.users}）支持
 * {@code enc:} 前缀的 AES-GCM 密文，密钥取自 {@code APP_CRYPTO_KEY}（见 SecretCrypto）。
 * 明文值仍然兼容。
 */
@Configuration
public class UserDetailsConfig {

    private static final Logger log = LoggerFactory.getLogger(UserDetailsConfig.class);

    @Value("${app.security.admin.username:admin}")
    private String adminUsername;

    @Value("${app.security.admin.password:}")
    private String adminPassword;

    @Value("${app.security.users:}")
    private String extraUsersConfig;

    /** 密钥缺失时为 no-op（decryptIfNeeded 对非 enc: 值原样返回） */
    private final SecretCrypto crypto = SecretCrypto.fromEnv();

    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    public InMemoryUserDetailsManager userDetailsService(PasswordEncoder passwordEncoder) {
        List<org.springframework.security.core.userdetails.UserDetails> users = new ArrayList<>();

        if (adminPassword == null || adminPassword.isEmpty()) {
            log.error("管理员密码未配置！请设置环境变量 ADMIN_PASSWORD");
            throw new IllegalStateException("管理员密码未配置，系统无法启动。请设置环境变量 ADMIN_PASSWORD");
        }

        String adminPlain;
        try {
            adminPlain = crypto.decryptIfNeeded(adminPassword);
        } catch (Exception e) {
            log.error("管理员密码解密失败，请检查 APP_CRYPTO_KEY 是否与加密时一致", e);
            throw new IllegalStateException("管理员密码解密失败（ADMIN_PASSWORD 为 enc: 密文但密钥不匹配）", e);
        }

        users.add(User.withUsername(adminUsername)
            .password(passwordEncoder.encode(adminPlain))
            .roles("ADMIN")
            .build());

        if (extraUsersConfig != null && !extraUsersConfig.trim().isEmpty()) {
            for (String userConfig : extraUsersConfig.split(",")) {
                if (userConfig == null || userConfig.trim().isEmpty()) continue;
                String[] parts = userConfig.trim().split(":", 3);
                if (parts.length >= 2) {
                    String uname = parts[0].trim();
                    String upass;
                    try {
                        upass = crypto.decryptIfNeeded(parts[1].trim());
                    } catch (Exception e) {
                        log.error("附加用户 {} 的密码解密失败，已跳过", uname);
                        continue;
                    }
                    String urole = parts.length >= 3 ? parts[2].trim().toUpperCase() : "USER";
                    if (uname.isEmpty() || upass == null || upass.length() < 8) continue;
                    users.add(User.withUsername(uname)
                        .password(passwordEncoder.encode(upass))
                        .roles(urole)
                        .build());
                }
            }
        }
        return new InMemoryUserDetailsManager(users);
    }
}
