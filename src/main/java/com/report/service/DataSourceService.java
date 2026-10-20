package com.report.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.report.model.DataSourceInfo;
import com.report.util.AtomicFileWriter;
import com.report.util.SecretCrypto;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import javax.sql.DataSource;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class DataSourceService {

    private static final Logger log = LoggerFactory.getLogger(DataSourceService.class);

    private static final Set<String> VALID_DRIVERS = Set.of(
        "oracle.jdbc.OracleDriver",
        "com.mysql.cj.jdbc.Driver",
        "org.postgresql.Driver",
        "com.microsoft.sqlserver.jdbc.SQLServerDriver",
        "org.h2.Driver"
    );

    @Value("${app.datasource.config-file:./data/datasources.json}")
    private String configFile;

    @Value("${app.datasource.default-id:default}")
    private volatile String defaultId;

    /** 与 application.yml spring.datasource 对齐，作为默认池回退（排除 AutoConfig 后仍可读配置） */
    @Value("${spring.datasource.url:}")
    private String springDsUrl;

    @Value("${spring.datasource.username:}")
    private String springDsUsername;

    @Value("${spring.datasource.password:}")
    private String springDsPassword;

    @Value("${spring.datasource.driver-class-name:oracle.jdbc.OracleDriver}")
    private String springDsDriver;

    @Value("${spring.datasource.hikari.maximum-pool-size:25}")
    private int hikariMaxPool;

    @Value("${spring.datasource.hikari.minimum-idle:5}")
    private int hikariMinIdle;

    @Value("${report.datasource-check-on-startup:true}")
    private boolean checkOnStartup;

    private final ObjectMapper om = new ObjectMapper();
    private final Map<String, DataSourceInfo> dsMap = new ConcurrentHashMap<>();
    private final Map<String, HikariDataSource> pools = new ConcurrentHashMap<>();
    private final SecretCrypto crypto = SecretCrypto.fromEnv();

    @PostConstruct
    void init() {
        loadConfig();
        initPools();
        if (crypto.isEnabled()) {
            log.info("数据源密码加密已启用 (APP_CRYPTO_KEY)");
        } else {
            log.warn("未配置 APP_CRYPTO_KEY，数据源密码将以明文或 ${ENV} 形式落盘");
        }
        if (checkOnStartup) {
            checkDefaultConnectivity();
        }
    }

    /**
     * 启动时探测默认数据源；失败仅打错误日志并抛异常，避免带着坏池对外服务。
     */
    private void checkDefaultConnectivity() {
        try {
            DataSource ds = getDataSource(null);
            try (Connection c = ds.getConnection()) {
                if (!c.isValid(5)) {
                    throw new IllegalStateException("默认数据源连接无效");
                }
            }
            log.info("默认数据源连通性检查通过: id={}", defaultId);
        } catch (Exception e) {
            log.error("默认数据源连通性检查失败: {}", e.getMessage());
            throw new IllegalStateException("默认数据源不可用，请检查 DB_URL/账号密码或 datasources.json: " + e.getMessage(), e);
        }
    }

    @PreDestroy
    void destroy() {
        for (var e : pools.entrySet()) {
            try { e.getValue().close(); } catch (Exception x) { /* ignore */ }
        }
        pools.clear();
    }

    private void initPools() {
        for (var e : dsMap.entrySet()) {
            try {
                pools.put(e.getKey(), mkPool(e.getValue()));
            } catch (Exception x) {
                log.warn("初始化连接池失败: {} - {}", e.getKey(), x.getMessage());
                // 默认源失败时，用 spring.datasource 配置兜底重建（测试 H2 / 本地无 env 场景）
                if (defaultId.equals(e.getKey()) && springDsUrl != null && !springDsUrl.isBlank()) {
                    try {
                        DataSourceInfo d = e.getValue();
                        d.setUrl(springDsUrl);
                        d.setUsername(springDsUsername);
                        d.setPassword(springDsPassword);
                        d.setDriverClassName(springDsDriver);
                        d.setType(guessType(springDsDriver));
                        pools.put(e.getKey(), mkPool(d));
                        log.info("已用 spring.datasource 重建默认数据源连接池");
                    } catch (Exception x2) {
                        log.error("重建默认数据源仍失败", x2);
                    }
                }
            }
        }
        if (!pools.containsKey(defaultId) && springDsUrl != null && !springDsUrl.isBlank()) {
            DataSourceInfo d = dsMap.computeIfAbsent(defaultId, id -> {
                DataSourceInfo n = new DataSourceInfo();
                n.setId(id);
                n.setName("default");
                return n;
            });
            d.setUrl(springDsUrl);
            d.setUsername(springDsUsername);
            d.setPassword(springDsPassword);
            d.setDriverClassName(springDsDriver);
            d.setType(guessType(springDsDriver));
            pools.put(defaultId, mkPool(d));
        }
    }

    private HikariDataSource mkPool(DataSourceInfo d) {
        HikariConfig c = new HikariConfig();
        c.setPoolName("DS-" + d.getId());
        c.setJdbcUrl(resolveEnvVar(d.getUrl()));
        c.setUsername(resolveEnvVar(d.getUsername()));
        c.setPassword(resolveSecret(d.getPassword()));
        c.setDriverClassName(d.getDriverClassName() != null && !d.getDriverClassName().isEmpty()
            ? d.getDriverClassName()
            : "oracle.jdbc.OracleDriver");
        // application.yml 里真正被读走的只有这两项；其余池参数在本方法里硬编码（见 yml 注释）
        c.setMaximumPoolSize(Math.max(1, hikariMaxPool));
        c.setMinimumIdle(Math.max(0, Math.min(hikariMinIdle, hikariMaxPool)));
        c.setMaxLifetime(600000);
        c.setLeakDetectionThreshold(120000);
        String t = d.getType();
        String driver = d.getDriverClassName() != null ? d.getDriverClassName().toLowerCase() : "";
        boolean oracleLike = (t == null || t.equalsIgnoreCase("oracle")) && !driver.contains("h2");
        c.setConnectionTestQuery(oracleLike ? "SELECT 1 FROM DUAL" : "SELECT 1");
        if (oracleLike) {
            /* DBLink 半死链路下 setQueryTimeout 打断不了 socket 读：一条挂死的查询会长期占住
             * 连接，池子（默认 25 个）耗尽后连管理界面都打不开。TCP 层必须自己超时。
             * 单位毫秒，是 Oracle 薄驱动的连接属性，不能给 H2 设，故只在 oracleLike 下加。 */
            c.addDataSourceProperty("oracle.net.CONNECT_TIMEOUT", "20000");
            c.addDataSourceProperty("oracle.jdbc.ReadTimeout", "300000");
        }
        return new HikariDataSource(c);
    }

    /**
     * 获取数据源连接池。
     * @param id null/空 = 使用默认数据源；指定 id 不存在时直接抛错（禁止静默回退到默认库）
     */
    public DataSource getDataSource(String id) {
        String resolvedId = (id == null || id.isEmpty()) ? defaultId : id;
        HikariDataSource p = pools.get(resolvedId);
        if (p == null) {
            throw new IllegalArgumentException("数据源不存在或连接池未初始化: " + resolvedId);
        }
        return p;
    }

    /** 是否存在已初始化的连接池 */
    /**
     * 解密 + 环境变量解析后的密码。
     *
     * <p>顺序不可颠倒：占位符本身可能解析出 {@code enc:} 密文
     * （例如 .env 中 {@code DB_PASSWORD=enc:...}，datasources.json 中 {@code ${DB_PASSWORD}}），
     * 因此必须先解析 {@code ${ENV}} 再做解密。
     */
    private String resolveSecret(String raw) {
        return crypto.decryptIfNeeded(resolveEnvVar(raw));
    }

    /**
     * 主密钥缺失时拒绝把字面口令落盘。
     *
     * <p>{@link SecretCrypto#encryptIfNeeded} 在 key 为 null 时是「原样返回」的
     * （只有一条启动期 WARN），于是界面上新建/改密的数据源口令会以**明文**写进
     * datasources.json。{@code ${ENV}} 引用与 {@code enc:} 密文不受影响。
     */
    private void assertSecretStorable(String password) {
        if (crypto.isEnabled()) return;
        if (password == null || password.isEmpty()) return;
        if (password.startsWith("enc:") || password.contains("${")) return;
        throw new IllegalArgumentException(
            "未启用主密钥（APP_CRYPTO_KEY / secret.key），已拒绝以明文保存数据源口令。"
                + "请配置密钥，或把口令写成 ${ENV_NAME} 形式由环境变量提供。");
    }

    private void loadConfig() {
        File f = new File(configFile);
        if (!f.exists()) {
            mkDefault();
            saveCfg();
            return;
        }
        try {
            Map<String, Object> r = om.readValue(f, new TypeReference<Map<String, Object>>() {});
            if (r.containsKey("datasources")) {
                String sid = (String) r.get("defaultId");
                if (sid != null && !sid.isEmpty()) defaultId = sid;
                List<DataSourceInfo> ls = om.convertValue(r.get("datasources"), new TypeReference<List<DataSourceInfo>>() {});
                for (DataSourceInfo d : ls) {
                    normalizeLoadedPassword(d);
                    dsMap.put(d.getId(), d);
                }
            } else {
                throw new IOException("Invalid format");
            }
        } catch (Exception e) {
            try {
                List<DataSourceInfo> ls = om.readValue(f, new TypeReference<List<DataSourceInfo>>() {});
                for (DataSourceInfo d : ls) {
                    normalizeLoadedPassword(d);
                    dsMap.put(d.getId(), d);
                }
            } catch (IOException x) {
                mkDefault();
            }
        }
        if (!dsMap.containsKey(defaultId)) mkDefault();
    }

    /** 加载时解密 enc: 密文到内存明文，连接池使用解密后的值 */
    private void normalizeLoadedPassword(DataSourceInfo d) {
        if (d == null || d.getPassword() == null) return;
        try {
            String plain = crypto.decryptIfNeeded(d.getPassword());
            d.setPassword(plain);
        } catch (Exception e) {
            log.error("数据源密码解密失败: id={}", d.getId(), e);
        }
    }

    private void mkDefault() {
        DataSourceInfo d = new DataSourceInfo();
        d.setId(defaultId);
        d.setName("HIS");
        d.setType(guessType(springDsDriver));
        // 优先环境变量占位；若无 env 则直接用 spring.datasource（测试/本地 H2 场景）
        String url = firstNonBlank(System.getenv("DB_URL"), System.getProperty("DB_URL"), springDsUrl, "${DB_URL}");
        String user = firstNonBlank(System.getenv("DB_USERNAME"), System.getProperty("DB_USERNAME"), springDsUsername, "${DB_USERNAME}");
        String pass = firstNonBlank(System.getenv("DB_PASSWORD"), System.getProperty("DB_PASSWORD"), springDsPassword, "${DB_PASSWORD}");
        d.setUrl(url);
        d.setUsername(user);
        d.setPassword(pass);
        d.setDriverClassName(firstNonBlank(springDsDriver, "oracle.jdbc.OracleDriver"));
        d.setActive(true);
        dsMap.put(defaultId, d);
    }

    private static String firstNonBlank(String... vals) {
        if (vals == null) return null;
        for (String v : vals) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    private static String guessType(String driver) {
        if (driver == null) return "oracle";
        String d = driver.toLowerCase();
        if (d.contains("h2")) return "h2";
        if (d.contains("mysql")) return "mysql";
        if (d.contains("postgresql")) return "postgresql";
        if (d.contains("sqlserver")) return "sqlserver";
        return "oracle";
    }

    private synchronized void saveCfg() {
        try {
            List<Map<String, Object>> list = new ArrayList<>();
            for (DataSourceInfo d : dsMap.values()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", d.getId());
                m.put("name", d.getName());
                m.put("type", d.getType());
                m.put("url", d.getUrl());
                m.put("username", d.getUsername());
                // 落盘加密（${ENV} 引用保持明文占位；已是 enc: 不重复加密）
                m.put("password", crypto.encryptIfNeeded(d.getPassword()));
                m.put("driverClassName", d.getDriverClassName());
                m.put("active", d.isActive());
                m.put("description", d.getDescription());
                m.put("createTime", d.getCreateTime());
                m.put("updateTime", d.getUpdateTime());
                list.add(m);
            }
            Map<String, Object> c = new HashMap<>();
            c.put("defaultId", defaultId);
            c.put("datasources", list);
            String json = om.writerWithDefaultPrettyPrinter().writeValueAsString(c);
            AtomicFileWriter.writeString(Path.of(configFile), json);
        } catch (IOException e) {
            log.error("保存数据源配置失败", e);
        }
    }

    public List<DataSourceInfo> getAll() {
        return new ArrayList<>(dsMap.values());
    }

    public DataSourceInfo getById(String id) {
        return dsMap.get(id);
    }

    public DataSourceInfo add(DataSourceInfo d) {
        if (d.getId() == null || d.getId().isEmpty()) {
            d.setId(UUID.randomUUID().toString().substring(0, 8));
        }
        if (dsMap.containsKey(d.getId())) {
            throw new IllegalArgumentException("ID已存在");
        }
        // ID 会回显到前端 onclick 属性中，必须限制为安全字符集（存储型 XSS 源头防护）
        if (!d.getId().matches("^[a-zA-Z0-9_-]{1,50}$")) {
            throw new IllegalArgumentException("数据源ID只能包含字母、数字、下划线和短横线，长度1-50");
        }
        d.setCreateTime(System.currentTimeMillis());
        assertSecretStorable(d.getPassword());
        dsMap.put(d.getId(), d);
        saveCfg();
        try {
            pools.put(d.getId(), mkPool(d));
        } catch (Exception x) {
            log.warn("创建连接池失败: {}", d.getId(), x);
        }
        return d;
    }

    public DataSourceInfo update(String id, DataSourceInfo d) {
        DataSourceInfo existing = dsMap.get(id);
        if (existing == null) throw new IllegalArgumentException("数据源不存在");
        d.setId(id);
        d.setUpdateTime(System.currentTimeMillis());
        // 未传密码或为遮罩值时保留原密码，避免更新后连接失效
        String newPwd = d.getPassword();
        boolean clientSuppliedSecret = newPwd != null && !newPwd.isEmpty()
            && !newPwd.chars().allMatch(c -> c == '*');
        if (clientSuppliedSecret) {
            assertSecretStorable(newPwd);
        } else {
            d.setPassword(existing.getPassword());
        }
        // 保留创建时间
        if (d.getCreateTime() <= 0) {
            d.setCreateTime(existing.getCreateTime());
        }
        dsMap.put(id, d);
        saveCfg();
        HikariDataSource op = pools.remove(id);
        if (op != null) try { op.close(); } catch (Exception x) { /* ignore */ }
        try {
            pools.put(id, mkPool(d));
        } catch (Exception x) {
            log.warn("重建连接池失败: {}", id, x);
        }
        return d;
    }

    public boolean delete(String id) {
        if (defaultId.equals(id)) throw new IllegalArgumentException("不能删除默认数据源");
        if (dsMap.remove(id) != null) {
            saveCfg();
            HikariDataSource p = pools.remove(id);
            if (p != null) try { p.close(); } catch (Exception x) { /* ignore */ }
            return true;
        }
        return false;
    }

    public Map<String, Object> testConnection(String id) {
        DataSourceInfo d = dsMap.get(id);
        if (d == null) throw new IllegalArgumentException("数据源不存在");
        return testConn(d);
    }

    public Map<String, Object> testConn(DataSourceInfo d) {
        Map<String, Object> r = new HashMap<>();
        try {
            if (!VALID_DRIVERS.contains(d.getDriverClassName())) {
                r.put("success", false);
                r.put("message", "不支持的驱动: " + d.getDriverClassName());
                return r;
            }
            Class.forName(d.getDriverClassName());
            try (Connection c = DriverManager.getConnection(
                    resolveEnvVar(d.getUrl()),
                    resolveEnvVar(d.getUsername()),
                    resolveSecret(d.getPassword()))) {
                r.put("success", true);
                r.put("message", "连接成功");
            }
        } catch (Exception e) {
            r.put("success", false);
            r.put("message", e.getMessage());
        }
        return r;
    }

    /**
     * 解析环境变量引用 ${VAR} 或 ${VAR:default}（支持串中多处占位）
     */
    private String resolveEnvVar(String v) {
        if (v == null || !v.contains("${")) return v;
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < v.length()) {
            int start = v.indexOf("${", i);
            if (start < 0) {
                out.append(v, i, v.length());
                break;
            }
            out.append(v, i, start);
            int end = v.indexOf('}', start + 2);
            if (end < 0) {
                out.append(v, start, v.length());
                break;
            }
            String expr = v.substring(start + 2, end);
            String name;
            String defaultVal = "";
            int colon = expr.indexOf(':');
            if (colon >= 0) {
                name = expr.substring(0, colon);
                defaultVal = expr.substring(colon + 1);
            } else {
                name = expr;
            }
            String resolved = System.getenv(name);
            if (resolved == null) resolved = System.getProperty(name);
            if (resolved == null) resolved = defaultVal;
            out.append(resolved);
            i = end + 1;
        }
        return out.toString();
    }

    public void setDefault(String id) {
        if (!dsMap.containsKey(id)) throw new IllegalArgumentException("数据源不存在");
        this.defaultId = id;
        saveCfg();
    }

    public String getDefaultId() {
        return defaultId;
    }
}
