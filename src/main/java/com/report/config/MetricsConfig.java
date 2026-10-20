package com.report.config;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.config.MeterFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 指标（Micrometer）标签净化。
 *
 * <p>缺陷现场：{@code GET /actuator/prometheus} 在 {@link SecurityConfig} 里是
 * {@code permitAll}（有意决策：供 Prometheus 采集器抓取，无表单登录能力）。
 * 但 Spring Boot 的磁盘指标 {@code DiskSpaceMetrics} 会把被测路径作为 {@code path} 标签吐出来，
 * 于是这个无需登录的端点直接泄露了服务器的绝对路径：
 *
 * <pre>
 * disk_free_bytes{application="sql-report-server",path="D:\\app\\."} 9.7E10
 * </pre>
 *
 * 该路径对 Prometheus 而言**毫无用处**（它随部署机器变化，是构建期/运行期环境细节），
 * 却把部署目录结构暴露给了任何能访问该端口的人，属于**无意泄露**，
 * 与「prometheus 公开」这个有意决策是两件事，故单独修掉。
 *
 * <p>修法：只剥掉 {@code disk.*} 指标的 {@code path} 标签，指标本身保留
 * （磁盘余量照样能监控、照样能告警），因此不损失任何监控能力。
 *
 * <p>为什么是「剥标签」而不是「关掉磁盘指标」：关掉会丢掉磁盘告警能力；
 * 剥标签只丢掉一个对采集端无意义的字符串。
 *
 * <p>已知取舍：若将来同一实例上监控多个挂载点，去掉 {@code path} 后这些时间序列会撞名。
 * 届时改为「保留标签、把值替换成稳定的逻辑名」（如 {@code data} / {@code logs}）即可 ，
 * 重点是**不要让标签值等于真实绝对路径**。
 */
@Configuration
public class MetricsConfig {

    /** 需要净化标签的指标名前缀（Spring Boot 磁盘指标：disk.free / disk.total）。 */
    private static final String DISK_PREFIX = "disk.";

    /** 要从磁盘指标上剥掉的标签名。 */
    private static final String PATH_TAG = "path";

    @Bean
    public MeterFilter diskPathTagScrubber() {
        return new MeterFilter() {
            @Override
            public Meter.Id map(Meter.Id id) {
                if (!id.getName().startsWith(DISK_PREFIX)) return id;
                List<Tag> kept = id.getTags().stream()
                    .filter(t -> !PATH_TAG.equals(t.getKey()))
                    .toList();
                // 没有该标签就原样返回，避免无谓地重建 Meter.Id
                return kept.size() == id.getTags().size() ? id : id.replaceTags(kept);
            }
        };
    }

    /** 供测试直接断言「哪些指标会被净化」，避免测试自己抄一份前缀常量。 */
    static boolean isDiskMeter(Meter.Id id) {
        return id.getName().startsWith(DISK_PREFIX);
    }

    static String pathTagKey() {
        return PATH_TAG;
    }
}
