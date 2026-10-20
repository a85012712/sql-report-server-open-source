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
import java.util.Collections;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 人员字典管理（data/personnel.json）。
 * 源头：HIS 人员表（表名/列名由 {@code app.his-import.personnel-sql} 配置，
 * 默认查询列顺序固定为：工号、姓名、性别、职称、所属科室编码、电话、拼音码、在用标志）。
 * 「从HIS导入」幂等：按工号更新姓名/性别/职称/电话/拼音码/所属科室，不覆盖本地启停状态。
 * 本期人员字典仅做管理与科室归属匹配；与登录工号/数据权限的联动为二期。
 */
@Service
public class PersonnelService {

    private static final Logger log = LoggerFactory.getLogger(PersonnelService.class);

    private final DataSourceService dataSourceService;
    private final ObjectMapper objectMapper;

    @Value("${app.users.data-file:}")
    private String usersDataFile;

    /** 「从HIS导入」的源表查询。列顺序：工号/姓名/性别/职称/科室编码/电话/拼音码/在用标志。 */
    @Value("${app.his-import.personnel-sql:SELECT staff_no, staff_name, gender, title, dept_code, phone, pinyin, in_use FROM his_staff ORDER BY staff_no}")
    private String personnelSql;

    private final List<Person> personnel = new ArrayList<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public PersonnelService(DataSourceService dataSourceService, ObjectMapper objectMapper) {
        this.dataSourceService = dataSourceService;
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
        return Paths.get(dataDir, "personnel.json");
    }

    private synchronized void load() {
        lock.writeLock().lock();
        try {
            personnel.clear();
            Path path = file();
            if (Files.exists(path)) {
                List<Person> list = objectMapper.readValue(Files.readString(path),
                    new TypeReference<List<Person>>() {});
                personnel.addAll(list);
                log.info("人员字典加载完成: {} 人", personnel.size());
            } else {
                log.info("人员字典为空，可从 HIS 导入: {}", path.toAbsolutePath());
            }
        } catch (Exception e) {
            log.error("加载人员字典失败", e);
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
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(personnel);
            AtomicFileWriter.writeString(file(), json);
        } catch (IOException e) {
            log.error("保存人员字典失败", e);
        }
    }

    // ============ 查询 ============

    public List<Person> listAll() {
        lock.readLock().lock();
        try {
            List<Person> list = new ArrayList<>();
            for (Person d : personnel) list.add(copy(d));
            return list;
        } finally {
            lock.readLock().unlock();
        }
    }

    /**
     * 下一个可用人员工号（与用户工号规则一致：优先补数字空缺，无空缺则接在最大号后）。
     * 例：现有 1-100 中 90-95 缺失 → 返回 0090；无空缺 → 返回最大号+1（保持位数补零）。
     */
    public String getNextCode() {
        List<Integer> nums = new ArrayList<>();
        int width = 4;
        for (Person d : personnel) {
            String c = d.code;
            if (c != null && c.matches("\\d+")) {
                nums.add(Integer.parseInt(c));
                width = Math.max(width, c.length());
            }
        }
        if (nums.isEmpty()) {
            return String.format("%0" + width + "d", 1);
        }
        Collections.sort(nums);
        for (int i = 1; i < nums.size(); i++) {
            if (nums.get(i) - nums.get(i - 1) > 1) {
                return String.format("%0" + width + "d", nums.get(i - 1) + 1);
            }
        }
        return String.format("%0" + width + "d", nums.get(nums.size() - 1) + 1);
    }

    // ============ 维护 ============

    public Person create(String code, String name, String gender, String title,
                         String department, String phone) {
        validateCode(code);
        String cc = code.trim();
        lock.writeLock().lock();
        try {
            for (Person d : personnel) {
                if (d.code.equalsIgnoreCase(cc)) throw new IllegalArgumentException("人员工号已存在: " + cc);
            }
            Person d = new Person();
            d.code = cc;
            fill(d, name, gender, title, department, phone);
            d.enabled = true;
            personnel.add(d);
            saveLocked();
            log.info("[AUDIT] action=createPerson, code={}, name={}", cc, d.name);
            return copy(d);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public Person update(String code, String name, String gender, String title,
                         String department, String phone, Boolean enabled) {
        lock.writeLock().lock();
        try {
            Person d = null;
            for (Person x : personnel) {
                if (x.code.equalsIgnoreCase(code)) { d = x; break; }
            }
            if (d == null) throw new IllegalArgumentException("人员不存在: " + code);
            fill(d, name, gender, title, department, phone);
            if (enabled != null) d.enabled = enabled;
            saveLocked();
            log.info("[AUDIT] action=updatePerson, code={}, name={}, enabled={}", d.code, d.name, d.enabled);
            return copy(d);
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void fill(Person d, String name, String gender, String title,
                      String department, String phone) {
        if (name != null && !name.trim().isEmpty()) d.name = name.trim();
        if (gender != null) d.gender = gender.trim().isEmpty() ? null : gender.trim();
        if (title != null) d.title = title.trim().isEmpty() ? null : title.trim();
        if (department != null) d.department = department.trim().isEmpty() ? null : department.trim().toUpperCase();
        if (phone != null) d.phone = phone.trim().isEmpty() ? null : phone.trim();
    }

    private void validateCode(String code) {
        if (code == null || !code.trim().matches("^[A-Za-z0-9_-]{1,20}$")) {
            throw new IllegalArgumentException("人员工号必须为1-20位字母/数字/下划线/中划线");
        }
    }

    // ============ HIS 导入 ============

    /**
     * 从 HIS 人员表导入（源表见 {@code app.his-import.personnel-sql}）。
     * 幂等：按工号更新姓名/性别/职称/电话/拼音码/所属科室；不覆盖本地启停状态。
     * @return [新增数, 更新数]
     */
    public int[] importFromHis() throws Exception {
        DataSource ds = dataSourceService.getDataSource(null);
        List<Person> his = new ArrayList<>();
        String sql = personnelSql;
        try (Connection conn = ds.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                Person d = new Person();
                d.code = rs.getString(1);
                d.name = rs.getString(2);
                d.gender = rs.getString(3);
                d.title = rs.getString(4);
                d.department = rs.getString(5);
                d.phone = rs.getString(6);
                d.pinyin = rs.getString(7);
                String inuse = rs.getString(8);
                d.enabled = "√".equals(inuse);
                if (d.code == null || d.code.isBlank()) continue;
                his.add(d);
            }
        }

        int added = 0, updated = 0;
        lock.writeLock().lock();
        try {
            for (Person h : his) {
                Person exist = null;
                for (Person x : personnel) {
                    if (x.code.equalsIgnoreCase(h.code.trim())) { exist = x; break; }
                }
                if (exist == null) {
                    Person d = new Person();
                    d.code = h.code.trim();
                    d.name = h.name != null ? h.name.trim() : d.code;
                    d.gender = h.gender;
                    d.title = h.title;
                    d.department = h.department;
                    d.phone = h.phone;
                    d.pinyin = h.pinyin;
                    d.enabled = h.enabled;
                    personnel.add(d);
                    added++;
                } else {
                    // 幂等更新：HIS 侧字段（含所属科室），保留本地启停状态
                    if (h.name != null && !h.name.isBlank()) exist.name = h.name.trim();
                    exist.gender = h.gender;
                    exist.title = h.title;
                    exist.department = h.department;
                    if (h.phone != null && !h.phone.isBlank()) exist.phone = h.phone.trim();
                    exist.pinyin = h.pinyin;
                    updated++;
                }
            }
            saveLocked();
        } finally {
            lock.writeLock().unlock();
        }
        log.info("[AUDIT] action=importPersonnelFromHis, total={}, added={}, updated={}", his.size(), added, updated);
        return new int[]{added, updated};
    }

    private static Person copy(Person d) {
        Person n = new Person();
        n.code = d.code;
        n.name = d.name;
        n.gender = d.gender;
        n.title = d.title;
        n.department = d.department;
        n.phone = d.phone;
        n.pinyin = d.pinyin;
        n.enabled = d.enabled;
        return n;
    }

    // ============ 数据结构 ============

    public static class Person {
        public String code;        // 工号（BIANM）
        public String name;        // 姓名（XINGM）
        public String gender;      // 性别（XINGB）
        public String title;       // 职称（ZHIC）
        public String department;  // 所属科室编码（SUOSKS，对应科室字典）
        public String phone;       // 联系电话（LIANXDH）
        public String pinyin;      // 拼音码（PINYM）
        public boolean enabled;
    }
}
