package com.report.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.report.util.AtomicFileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 社区字典管理（data/communities.json）。
 * 初始 A/B/C/D/E/H；可在"社区管理"页增改停用；
 * 报表社区下拉、大屏社区按钮、工号数据权限多选均以此为准。
 * 代码创建后不可改（关联历史数据匹配）；只停用、不删除。
 */
@Service
public class CommunityService {

    private static final Logger log = LoggerFactory.getLogger(CommunityService.class);

    private final ObjectMapper objectMapper;

    @Value("${app.users.data-file:}")
    private String usersDataFile;

    private final List<Community> communities = new ArrayList<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public CommunityService(ObjectMapper objectMapper) {
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
        return Paths.get(dataDir, "communities.json");
    }

    private synchronized void load() {
        lock.writeLock().lock();
        try {
            communities.clear();
            Path path = file();
            if (Files.exists(path)) {
                List<Community> list = objectMapper.readValue(Files.readString(path),
                    new TypeReference<List<Community>>() {});
                communities.addAll(list);
            }
            // 兜底：保证 A-E,H 存在（新部署或字典被误删时）
            ensure("A", "A社区");
            ensure("B", "B社区");
            ensure("C", "C社区");
            ensure("D", "D社区");
            ensure("E", "E社区");
            ensure("H", "H社区");
            if (!Files.exists(path)) saveLocked();
            log.info("社区字典加载完成: {} 个社区", communities.size());
        } catch (Exception e) {
            log.error("加载社区字典失败", e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void ensure(String code, String name) {
        for (Community c : communities) {
            if (c.code.equalsIgnoreCase(code)) return;
        }
        Community c = new Community();
        c.code = code;
        c.name = name;
        c.enabled = true;
        communities.add(c);
    }

    /**
     * 序列化并落盘社区字典。
     *
     * <p>调用方必须已持有写锁，本方法不得再取读锁：ReentrantReadWriteLock 虽支持
     * 「持写锁再取读锁」的降级，但只要另一个线程正在排队等写锁，降级请求就会排到它后面，
     * 而持锁者正是它等待的对象，两个管理员同时改字典即永久挂死，此后所有社区请求全阻塞。
     * （{@code UserManagementService} 把落盘放在锁外就是为了避开同一个坑。）
     */
    private synchronized void saveLocked() {
        try {
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(communities);
            AtomicFileWriter.writeString(file(), json);
        } catch (IOException e) {
            log.error("保存社区字典失败", e);
        }
    }

    // ============ 查询 ============

    /** 全部社区（含停用，管理页用） */
    public List<Community> listAll() {
        lock.readLock().lock();
        try {
            List<Community> list = new ArrayList<>();
            for (Community c : communities) list.add(copy(c));
            return list;
        } finally {
            lock.readLock().unlock();
        }
    }

    /** 启用中的社区代码（下拉/大屏/权限多选用） */
    public List<String> enabledCodes() {
        lock.readLock().lock();
        try {
            List<String> codes = new ArrayList<>();
            for (Community c : communities) {
                if (c.enabled) codes.add(c.code);
            }
            return codes;
        } finally {
            lock.readLock().unlock();
        }
    }

    public Community getByCode(String code) {
        if (code == null) return null;
        lock.readLock().lock();
        try {
            for (Community c : communities) {
                if (c.code.equalsIgnoreCase(code.trim())) return copy(c);
            }
            return null;
        } finally {
            lock.readLock().unlock();
        }
    }

    // ============ 维护 ============

    public Community create(String code, String name) {
        if (code == null || !code.trim().matches("^[A-Za-z0-9]{1,4}$")) {
            throw new IllegalArgumentException("社区代码必须为1-4位字母或数字");
        }
        if (name == null || name.trim().isEmpty()) throw new IllegalArgumentException("社区名称不能为空");
        String cc = code.trim().toUpperCase();
        lock.writeLock().lock();
        try {
            for (Community c : communities) {
                if (c.code.equalsIgnoreCase(cc)) throw new IllegalArgumentException("社区代码已存在: " + cc);
            }
            Community c = new Community();
            c.code = cc;
            c.name = name.trim();
            c.enabled = true;
            communities.add(c);
            saveLocked();
            log.info("[AUDIT] action=createCommunity, code={}, name={}", cc, c.name);
            return copy(c);
        } finally {
            lock.writeLock().unlock();
        }
    }

    /** 修改名称/启停（代码不可改） */
    public Community update(String code, String name, Boolean enabled) {
        lock.writeLock().lock();
        try {
            Community c = null;
            for (Community x : communities) {
                if (x.code.equalsIgnoreCase(code)) { c = x; break; }
            }
            if (c == null) throw new IllegalArgumentException("社区不存在: " + code);
            if (name != null && !name.trim().isEmpty()) c.name = name.trim();
            if (enabled != null) c.enabled = enabled;
            saveLocked();
            log.info("[AUDIT] action=updateCommunity, code={}, name={}, enabled={}", c.code, c.name, c.enabled);
            return copy(c);
        } finally {
            lock.writeLock().unlock();
        }
    }

    private static Community copy(Community c) {
        Community n = new Community();
        n.code = c.code;
        n.name = c.name;
        n.enabled = c.enabled;
        return n;
    }

    // ============ 数据结构 ============

    public static class Community {
        public String code;
        public String name;
        public boolean enabled;
    }
}
