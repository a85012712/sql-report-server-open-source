package com.report.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

@Service
public class GroupPermissionService {

    private static final Logger log = LoggerFactory.getLogger(GroupPermissionService.class);

    @Value("${app.data.group-config:./data/report-groups.json}")
    private String configFilePath;

    private final ObjectMapper objectMapper;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    private List<Map<String, Object>> groups = new ArrayList<>();
    private Map<String, String> reportGroupMap = new LinkedHashMap<>();
    private Map<String, String> reportCustomNames = new LinkedHashMap<>();
    private Set<String> hiddenReports = new LinkedHashSet<>();

    public GroupPermissionService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void init() { loadConfig(); }

    private void loadConfig() {
        Path path = Paths.get(configFilePath);
        if (!Files.exists(path)) {
            log.info("分组配置文件不存在，初始化默认: {}", configFilePath);
            initDefaultConfig();
            saveConfig();
            return;
        }
        try {
            String json = Files.readString(path);
            Map<String, Object> config = objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
            lock.writeLock().lock();
            try {
                Object g = config.get("groups");
                if (g instanceof List) groups = (List<Map<String, Object>>) g;
                Object a = config.get("assignments");
                if (a instanceof Map) { reportGroupMap = new LinkedHashMap<>(); ((Map<String, Object>) a).forEach((k, v) -> reportGroupMap.put(k, v.toString())); }
                Object n = config.get("customNames");
                if (n instanceof Map) { reportCustomNames = new LinkedHashMap<>(); ((Map<String, Object>) n).forEach((k, v) -> reportCustomNames.put(k, v.toString())); }
                Object h = config.get("hiddenReports");
                if (h instanceof Collection) { hiddenReports = new LinkedHashSet<>((Collection<String>) h); }
            } finally { lock.writeLock().unlock(); }
            log.info("加载分组配置: {} 个分组, {} 个报表分配", groups.size(), reportGroupMap.size());
        } catch (IOException e) {
            log.error("加载分组配置失败", e);
            initDefaultConfig();
        }
    }

    private void initDefaultConfig() {
        lock.writeLock().lock();
        try {
            groups = new ArrayList<>();
            groups.add(Map.of("id", "default", "name", "默认分组", "sort", 1));
            reportGroupMap = new LinkedHashMap<>();
            reportCustomNames = new LinkedHashMap<>();
        } finally { lock.writeLock().unlock(); }
    }

    /**
     * 保存配置到文件
     * Bug #2修复：原实现在读锁下执行I/O，且与写锁调用之间存在数据不一致窗口。
     * 新实现：在读锁内快速拷贝数据快照，然后在无锁状态下写文件。
     * 注意：此方法必须在写锁释放后调用（保证数据修改已完成），或在初始化时调用。
     */
    private void saveConfig() {
        // 在读锁内快速拷贝数据快照
        String json;
        lock.readLock().lock();
        try {
            Map<String, Object> config = new LinkedHashMap<>();
            config.put("groups", new ArrayList<>(groups));
            config.put("assignments", new LinkedHashMap<>(reportGroupMap));
            config.put("customNames", new LinkedHashMap<>(reportCustomNames));
            config.put("hiddenReports", new LinkedHashSet<>(hiddenReports));
            json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(config);
        } catch (Exception e) {
            log.error("序列化分组配置失败", e);
            return;
        } finally { lock.readLock().unlock(); }

        // 在无锁状态下执行文件I/O
        try {
            Path path = Paths.get(configFilePath);
            Path parent = path.getParent();
            if (parent != null && !Files.exists(parent)) Files.createDirectories(parent);
            Files.writeString(path, json, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) { log.error("保存分组配置失败", e); }
    }

    public List<Map<String, Object>> getAllGroups() {
        lock.readLock().lock();
        try { return new ArrayList<>(groups); }
        finally { lock.readLock().unlock(); }
    }

    public void createGroup(String id, String name, int sort) {
        if (id == null || !id.matches("^[a-zA-Z0-9_]+$")) throw new IllegalArgumentException("分组ID只能包含字母、数字和下划线");
        if (name == null || name.trim().isEmpty()) throw new IllegalArgumentException("分组名称不能为空");
        lock.writeLock().lock();
        try {
            if (groups.stream().anyMatch(g -> id.equals(g.get("id")))) throw new IllegalArgumentException("分组ID已存在");
            Map<String, Object> group = new LinkedHashMap<>();
            group.put("id", id); group.put("name", name); group.put("sort", sort);
            groups.add(group);
        } finally { lock.writeLock().unlock(); }
        saveConfig();
        log.info("[AUDIT] action=createGroup, id={}, name={}", id, name);
    }

    public void updateGroup(String id, String name, Integer sort) {
        lock.writeLock().lock();
        try {
            Map<String, Object> group = groups.stream().filter(g -> id.equals(g.get("id"))).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("分组不存在"));
            if (name != null) group.put("name", name);
            if (sort != null) group.put("sort", sort);
        } finally { lock.writeLock().unlock(); }
        saveConfig();
    }

    public void deleteGroup(String id) {
        if ("default".equals(id)) throw new IllegalArgumentException("不能删除默认分组");
        lock.writeLock().lock();
        try {
            if (!groups.removeIf(g -> id.equals(g.get("id")))) throw new IllegalArgumentException("分组不存在");
            reportGroupMap.replaceAll((rid, gid) -> id.equals(gid) ? "default" : gid);
        } finally { lock.writeLock().unlock(); }
        saveConfig();
        log.info("[AUDIT] action=deleteGroup, id={}", id);
    }

    public String getReportGroup(String reportId) {
        lock.readLock().lock();
        try { return reportGroupMap.getOrDefault(reportId, "default"); }
        finally { lock.readLock().unlock(); }
    }

    public void setReportGroup(String reportId, String groupId) {
        lock.writeLock().lock();
        try {
            if (groups.stream().noneMatch(g -> groupId.equals(g.get("id")))) throw new IllegalArgumentException("分组不存在");
            reportGroupMap.put(reportId, groupId);
        } finally { lock.writeLock().unlock(); }
        saveConfig();
        log.info("[AUDIT] action=setReportGroup, report={}, group={}", reportId, groupId);
    }

    public void setReportGroups(Map<String, String> assignments) {
        lock.writeLock().lock();
        try { reportGroupMap.putAll(assignments); }
        finally { lock.writeLock().unlock(); }
        saveConfig();
    }

    public String getCustomName(String reportId) {
        lock.readLock().lock();
        try { return reportCustomNames.get(reportId); }
        finally { lock.readLock().unlock(); }
    }

    public void setCustomName(String reportId, String customName) {
        lock.writeLock().lock();
        try {
            if (customName == null || customName.trim().isEmpty()) reportCustomNames.remove(reportId);
            else reportCustomNames.put(reportId, customName.trim());
        } finally { lock.writeLock().unlock(); }
        saveConfig();
        log.info("[AUDIT] action=setCustomName, report={}, name={}", reportId, customName);
    }

    public List<String> filterReportsByGroups(Collection<String> allReportIds, List<String> userGroups) {
        if (userGroups == null) return new ArrayList<>(allReportIds);
        lock.readLock().lock();
        try {
            return allReportIds.stream().filter(rid -> {
                String group = reportGroupMap.getOrDefault(rid, "default");
                return userGroups.contains(group);
            }).collect(Collectors.toList());
        } finally { lock.readLock().unlock(); }
    }

    public Map<String, Object> getFullConfig() {
        lock.readLock().lock();
        try {
            Map<String, Object> config = new LinkedHashMap<>();
            config.put("groups", groups);
            config.put("assignments", reportGroupMap);
            config.put("customNames", reportCustomNames);
            config.put("hiddenReports", hiddenReports);
            return config;
        } finally { lock.readLock().unlock(); }
    }

    public boolean isReportHidden(String reportId) {
        lock.readLock().lock();
        try { return hiddenReports.contains(reportId); }
        finally { lock.readLock().unlock(); }
    }

    public void setReportHidden(String reportId, boolean hidden) {
        lock.writeLock().lock();
        try {
            if (hidden) hiddenReports.add(reportId);
            else hiddenReports.remove(reportId);
        } finally { lock.writeLock().unlock(); }
        saveConfig();
        log.info("[AUDIT] action=setReportHidden, report={}, hidden={}", reportId, hidden);
    }

    public void removeReport(String reportId) {
        lock.writeLock().lock();
        try {
            reportGroupMap.remove(reportId);
            reportCustomNames.remove(reportId);
            hiddenReports.remove(reportId);
        } finally { lock.writeLock().unlock(); }
        saveConfig();
        log.info("[AUDIT] action=removeReportConfig, report={}", reportId);
    }
}