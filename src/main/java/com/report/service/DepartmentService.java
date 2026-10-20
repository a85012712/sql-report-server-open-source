package com.report.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.report.util.AtomicFileWriter;
import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 科室字典管理（data/departments.json）。
 * 支持手动维护 + 「从 HIS 导入」（读 HIS 科室表 kesxx：编码/名称/联系人/电话）。
 * 幂等导入：按编码更新名称/联系人/电话，不覆盖本地已设置的社区归属与启停状态。
 * 编码创建后不可改；只停用、不删除。本期科室仅做管理与工号绑定，报表按科室过滤为二期。
 */
@Service
public class DepartmentService {

    private static final Logger log = LoggerFactory.getLogger(DepartmentService.class);

    private final DataSourceService dataSourceService;
    private final CommunityService communityService;
    private final ObjectMapper objectMapper;

    @Value("${app.users.data-file:}")
    private String usersDataFile;

    /** 「从HIS导入」的源表查询。列顺序：科室编码/科室名称/联系人/联系电话。 */
    @Value("${app.his-import.department-sql:SELECT dept_code, dept_name, contact, phone FROM his_department ORDER BY dept_code}")
    private String departmentSql;

    private final List<Department> departments = new ArrayList<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public DepartmentService(DataSourceService dataSourceService, CommunityService communityService,
                             ObjectMapper objectMapper) {
        this.dataSourceService = dataSourceService;
        this.communityService = communityService;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void init() {
        load();
    }

    private Path file() {
        String appHome = System.getProperty("APP_HOME", System.getProperty("user.dir"));
        String dataDir = usersDataFile != null && !usersDataFile.isEmpty()
            ? Paths.get(usersDataFile).getParent().toString()
            : Paths.get(appHome, "data").toString();
        return Paths.get(dataDir, "departments.json");
    }

    private synchronized void load() {
        lock.writeLock().lock();
        try {
            departments.clear();
            Path path = file();
            if (Files.exists(path)) {
                List<Department> list = objectMapper.readValue(Files.readString(path),
                    new TypeReference<List<Department>>() {});
                departments.addAll(list);
                log.info("科室字典加载完成: {} 个科室", departments.size());
            } else {
                log.info("科室字典为空，可从 HIS 导入或手动维护: {}", path.toAbsolutePath());
            }
        } catch (Exception e) {
            log.error("加载科室字典失败", e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /**
     * 序列化并落盘。
     *
     * <p>调用方必须已持有写锁，本方法不得再取读锁：只要有另一个线程在排队等写锁，
     * 「持写锁再取读锁」的降级就会排到它后面，而持锁者正是它等待的对象，永久挂死。
     */
    private synchronized void saveLocked() {
        try {
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(departments);
            AtomicFileWriter.writeString(file(), json);
        } catch (IOException e) {
            log.error("保存科室字典失败", e);
        }
    }

    // ============ 查询 ============

    public List<Department> listAll() {
        lock.readLock().lock();
        try {
            List<Department> list = new ArrayList<>();
            for (Department d : departments) list.add(copy(d));
            return list;
        } finally {
            lock.readLock().unlock();
        }
    }

    public List<Department> listEnabled() {
        List<Department> all = listAll();
        List<Department> out = new ArrayList<>();
        for (Department d : all) if (d.enabled) out.add(d);
        return out;
    }

    // ============ 维护 ============

    public Department create(String code, String name, String category, String community,
                             String contact, String phone) {
        validateCode(code);
        String cc = code.trim();
        lock.writeLock().lock();
        try {
            for (Department d : departments) {
                if (d.code.equalsIgnoreCase(cc)) throw new IllegalArgumentException("科室编码已存在: " + cc);
            }
            Department d = new Department();
            d.code = cc;
            fill(d, name, category, community, contact, phone);
            d.enabled = true;
            departments.add(d);
            saveLocked();
            log.info("[AUDIT] action=createDepartment, code={}, name={}", cc, d.name);
            return copy(d);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public Department update(String code, String name, String category, String community,
                             String contact, String phone, Boolean enabled) {
        lock.writeLock().lock();
        try {
            Department d = null;
            for (Department x : departments) {
                if (x.code.equalsIgnoreCase(code)) { d = x; break; }
            }
            if (d == null) throw new IllegalArgumentException("科室不存在: " + code);
            fill(d, name, category, community, contact, phone);
            if (enabled != null) d.enabled = enabled;
            saveLocked();
            log.info("[AUDIT] action=updateDepartment, code={}, name={}, enabled={}", d.code, d.name, d.enabled);
            return copy(d);
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void fill(Department d, String name, String category, String community,
                      String contact, String phone) {
        if (name != null && !name.trim().isEmpty()) d.name = name.trim();
        if (category != null) d.category = category.trim().isEmpty() ? null : category.trim();
        if (community != null) d.community = community.trim().isEmpty() ? null : community.trim().toUpperCase();
        if (contact != null) d.contact = contact.trim().isEmpty() ? null : contact.trim();
        if (phone != null) d.phone = phone.trim().isEmpty() ? null : phone.trim();
    }

    private void validateCode(String code) {
        if (code == null || !code.trim().matches("^[A-Za-z0-9_-]{1,20}$")) {
            throw new IllegalArgumentException("科室编码必须为1-20位字母/数字/下划线/中划线");
        }
    }

    // ============ HIS 导入 ============

    /**
     * 从 HIS 科室表导入（源表见 {@code app.his-import.department-sql}）。
     * 幂等：按编码更新名称/联系人/电话；本地已设置的社区归属与启停状态不覆盖；
     * 所属社区按名称首字符匹配社区字典。
     * @return [新增数, 更新数]
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public int[] importFromHis() throws Exception {
        DataSource ds = dataSourceService.getDataSource(null);
        List<Department> his = new ArrayList<>();
        String sql = departmentSql;
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Department d = new Department();
                d.code = rs.getString(1);
                d.name = rs.getString(2);
                d.contact = rs.getString(3);
                d.phone = rs.getString(4);
                if (d.code == null || d.code.isBlank()) continue;
                his.add(d);
            }
        }

        int added = 0, updated = 0;
        lock.writeLock().lock();
        try {
            for (Department h : his) {
                Department exist = null;
                for (Department x : departments) {
                    if (x.code.equalsIgnoreCase(h.code.trim())) { exist = x; break; }
                }
                if (exist == null) {
                    Department d = new Department();
                    d.code = h.code.trim();
                    d.name = h.name != null ? h.name.trim() : d.code;
                    d.contact = h.contact;
                    d.phone = h.phone;
                    d.enabled = true;
                    d.community = guessCommunity(d.name);
                    departments.add(d);
                    added++;
                } else {
                    // 幂等更新：只同步 HIS 侧字段，不动本地社区归属/启停
                    if (h.name != null && !h.name.isBlank()) exist.name = h.name.trim();
                    if (h.contact != null && !h.contact.isBlank()) exist.contact = h.contact.trim();
                    if (h.phone != null && !h.phone.isBlank()) exist.phone = h.phone.trim();
                    if (exist.community == null || exist.community.isBlank()) {
                        exist.community = guessCommunity(exist.name);
                    }
                    updated++;
                }
            }
            saveLocked();
        } finally {
            lock.writeLock().unlock();
        }
        log.info("[AUDIT] action=importDepartmentsFromHis, total={}, added={}, updated={}", his.size(), added, updated);
        return new int[]{added, updated};
    }

    /** 名称首字符在社区字典内 → 作为所属社区（如 A药房→A）；否则 null */
    private String guessCommunity(String name) {
        if (name == null || name.isEmpty()) return null;
        String first = name.substring(0, 1).toUpperCase();
        return communityService.getByCode(first) != null ? first : null;
    }

    private static Department copy(Department d) {
        Department n = new Department();
        n.code = d.code;
        n.name = d.name;
        n.category = d.category;
        n.community = d.community;
        n.contact = d.contact;
        n.phone = d.phone;
        n.enabled = d.enabled;
        return n;
    }

    // ============ 数据结构 ============

    public static class Department {
        public String code;
        public String name;
        public String category;   // 临床科室/医辅科室/行政科室/其他科室
        public String community;  // 所属社区代码
        public String contact;
        public String phone;
        public boolean enabled;
    }
}
