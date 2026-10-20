package com.report.controller;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.SpringBootVersion;
import org.springframework.boot.info.BuildProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 版本信息接口。
 *
 * <p>用途：让部署现场能直接确认「当前跑的是哪个包」，避免仅凭 jar 文件名推断。
 * 版本号来自 Maven 构建时写入的 {@code META-INF/build-info.properties}
 * （由 {@code spring-boot-maven-plugin} 的 {@code build-info} goal 生成），
 * 因此与 pom 中的 {@code <version>} 严格一致，无需在代码或配置中重复维护。
 *
 * <ul>
 *   <li>{@code GET /api/version}，公开（无需登录），登录页底部也据此展示版本。</li>
 *   <li>{@code GET /actuator/info}，需 ADMIN，返回同类信息（供运维核对）。</li>
 * </ul>
 *
 * <p>说明：此处刻意不通过 {@code application.yml} 的 {@code @project.version@} 资源过滤取版本，
 * 因为该文件中的 JDBC URL 含 {@code jdbc:oracle:thin:@host} 形式的 {@code @} 符号，
 * 会与 Maven 的 {@code @...@} 过滤分隔符跨行错误配对，导致配置被破坏。
 */
@RestController
@RequestMapping("/api/version")
public class VersionController {

    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.of("Asia/Shanghai"));

    private final ObjectProvider<BuildProperties> buildPropertiesProvider;

    public VersionController(ObjectProvider<BuildProperties> buildPropertiesProvider) {
        this.buildPropertiesProvider = buildPropertiesProvider;
    }

    /**
     * 返回应用版本与构建信息。
     *
     * <p>当以 IDE 直接运行（未经 Maven 构建、无 build-info.properties）时，
     * {@code version} 回退为 {@code unknown}，接口本身仍正常返回，不抛异常。
     *
     * @return name / version / buildTime / springBoot / vendor / author，均为字符串，便于前端直接展示
     */
    @GetMapping
    public Map<String, Object> version() {
        Map<String, Object> info = new LinkedHashMap<>();

        BuildProperties bp = buildPropertiesProvider.getIfAvailable();
        String version = "unknown";
        String buildTime = "";
        if (bp != null) {
            version = bp.getVersion() == null ? "unknown" : bp.getVersion();
            Instant t = bp.getTime();
            if (t != null) {
                buildTime = TS_FMT.format(t);
            }
        }

        info.put("name", "HIS报表查询系统");
        info.put("version", version);
        info.put("buildTime", buildTime);
        info.put("springBoot", SpringBootVersion.getVersion());
        // 作者/归属：与 pom 的 <developers> 及 jar MANIFEST 的 Implementation-Vendor 保持一致
        info.put("vendor", "YAO");
        info.put("author", "YAO");
        return info;
    }
}
