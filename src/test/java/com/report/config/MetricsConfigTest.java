package com.report.config;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.config.MeterFilter;
import org.junit.jupiter.api.Test;

import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 守卫：{@code /actuator/prometheus} 是无需登录的（供 Prometheus 采集），
 * 因此它吐出的每一条标签都在「公开可读」的范围内 ，
 * Spring Boot 默认把服务器绝对路径塞进磁盘指标的 {@code path} 标签，必须剥掉。
 *
 * <p>这是「未认证可达面」审计的一条结论落地：公开的端点，标签值也必须是可以公开的。
 */
class MetricsConfigTest {

    private static Meter.Id id(String name, Tags tags) {
        return new Meter.Id(name, tags, "bytes", null, Meter.Type.GAUGE);
    }

    private static boolean hasTag(Meter.Id id, String key) {
        return StreamSupport.stream(id.getTags().spliterator(), false)
            .anyMatch(t -> key.equals(t.getKey()));
    }

    private static String tagValue(Meter.Id id, String key) {
        return StreamSupport.stream(id.getTags().spliterator(), false)
            .filter(t -> key.equals(t.getKey()))
            .map(Tag::getValue)
            .findFirst().orElse(null);
    }

    @Test
    void diskMeter_losesAbsolutePathTag_butKeepsTheMetricAndOtherTags() {
        final String absolutePath = "D:\\app\\deploy\\.";
        Meter.Id raw = id("disk.free",
            Tags.of("application", "sql-report-server", MetricsConfig.pathTagKey(), absolutePath));

        // 先证明「夹具真的带上了绝对路径」， 否则下面那条断言可能恒真（夹具本身就没这个标签）
        assertTrue(hasTag(raw, MetricsConfig.pathTagKey()),
            "夹具构造失败：磁盘指标本应带 path 标签");
        assertEquals(absolutePath, tagValue(raw, MetricsConfig.pathTagKey()),
            "夹具构造失败：path 标签值应为绝对路径");
        assertTrue(absolutePath.contains("\\"),
            "夹具构造失败：路径里应含 Windows 分隔符，用于证明「泄露的是真实文件系统路径」");

        MeterFilter filter = new MetricsConfig().diskPathTagScrubber();
        Meter.Id mapped = filter.map(raw);
        assertNotNull(mapped);

        assertFalse(hasTag(mapped, MetricsConfig.pathTagKey()),
            "绝对路径标签必须被剥掉，/actuator/prometheus 无需登录即可读取，"
                + "path=\"D:\\...\\deploy\\.\" 等于把部署目录公开");
        assertEquals("disk.free", mapped.getName(),
            "指标本身必须保留（只剥标签，不关指标）， 磁盘余量监控与告警不能因此失效");
        assertEquals("sql-report-server", tagValue(mapped, "application"),
            "其它标签必须原样保留");
    }

    @Test
    void diskMeterWithoutPathTag_isReturnedAsIs() {
        Meter.Id raw = id("disk.total", Tags.of("application", "sql-report-server"));
        Meter.Id mapped = new MetricsConfig().diskPathTagScrubber().map(raw);
        // 没有该标签时应原样返回（同一个对象），不做无谓重建
        assertSame(raw, mapped, "没有 path 标签时应原样返回");
    }

    @Test
    void nonDiskMeter_isNotTouched() {
        // HTTP 指标的 uri 标签是有价值的（运维要看哪个端点慢），不在净化范围内
        Meter.Id raw = id("http.server.requests",
            Tags.of("uri", "/api/admin/users/{username}", "method", "DELETE"));
        Meter.Id mapped = new MetricsConfig().diskPathTagScrubber().map(raw);
        assertSame(raw, mapped, "非 disk.* 指标必须完全不动");
    }

    @Test
    void filterOnlyTargetsDiskPrefix() {
        assertTrue(MetricsConfig.isDiskMeter(id("disk.free", Tags.empty())));
        assertTrue(MetricsConfig.isDiskMeter(id("disk.total", Tags.empty())));
        assertFalse(MetricsConfig.isDiskMeter(id("http.server.requests", Tags.empty())));
        assertFalse(MetricsConfig.isDiskMeter(id("hikaricp.connections", Tags.empty())));
        // 前缀必须是 "disk." 而不是 "disk"：否则会误伤恰好以 disk 开头的其它指标名
        assertFalse(MetricsConfig.isDiskMeter(id("diskspace.free", Tags.empty())));
    }
}
