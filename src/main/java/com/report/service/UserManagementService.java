package com.report.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@Service
public class UserManagementService {

    public static final String DEFAULT_PASSWORD = "666999";
    private static final Logger log = LoggerFactory.getLogger(UserManagementService.class);

    private final InMemoryUserDetailsManager userDetailsManager;
    private final PasswordEncoder passwordEncoder;
    private final ObjectMapper objectMapper;
    private final Map<String, UserRecord> userRecords = new ConcurrentHashMap<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    @Value("${app.security.admin.username:admin}")
    private String adminUsername;

    @Value("${app.users.data-file:}")
    private String usersDataFile;

    public UserManagementService(InMemoryUserDetailsManager userDetailsManager,
                                  PasswordEncoder passwordEncoder,
                                  ObjectMapper objectMapper) {
        this.userDetailsManager = userDetailsManager;
        this.passwordEncoder = passwordEncoder;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void init() {
        // 记录管理员用户
        userRecords.put(adminUsername, new UserRecord(adminUsername, "ADMIN", true));

        // 如果未配置数据文件路径，使用相对于jar包的路径
        if (usersDataFile == null || usersDataFile.isEmpty()) {
            String appHome = System.getProperty("APP_HOME", System.getProperty("user.dir"));
            usersDataFile = Paths.get(appHome, "data", "users.json").toString();
            log.info("使用默认用户数据文件路径: {}", usersDataFile);
        }

        // 加载持久化的用户数据
        loadUsersFromDisk();
    }

    /**
     * 从磁盘加载用户数据
     */
    private void loadUsersFromDisk() {
        Path path = Paths.get(usersDataFile);
        if (!Files.exists(path)) {
            log.info("用户数据文件不存在，跳过加载: {}", path.toAbsolutePath());
            return;
        }

        try {
            String json = Files.readString(path);
            List<UserRecord> records = objectMapper.readValue(json, new TypeReference<List<UserRecord>>() {});
            log.info("从文件读取到 {} 条用户记录: {}", records.size(), path.toAbsolutePath());

            int restored = 0;
            lock.writeLock().lock();
            try {
                for (UserRecord record : records) {
                    // 跳过管理员（已在init中记录，密码由SecurityConfig管理）
                    if (record.username.equals(adminUsername)) {
                        log.debug("跳过管理员用户: {}", record.username);
                        continue;
                    }

                    // 跳过已存在的用户
                    if (userRecords.containsKey(record.username)) {
                        log.debug("跳过已存在用户: {}", record.username);
                        continue;
                    }

                    // 恢复用户到内存
                    if (record.encodedPassword != null && !record.encodedPassword.isEmpty()) {
                        UserDetails user = User.withUsername(record.username)
                            .password(record.encodedPassword)
                            .roles(record.role)
                            .build();
                        userDetailsManager.createUser(user);
                        userRecords.put(record.username, record);
                        restored++;
                        log.info("恢复用户: {}, 角色: {}, 分组: {}", record.username, record.role, record.groups);
                    } else {
                        log.warn("跳过无密码用户: {}", record.username);
                    }
                }
            } finally {
                lock.writeLock().unlock();
            }

            log.info("用户持久化加载完成: 恢复{}个用户, 共{}个用户(含管理员)", restored, userRecords.size());
        } catch (IOException e) {
            log.error("加载用户数据失败: {}", path.toAbsolutePath(), e);
        } catch (Exception e) {
            log.error("解析用户数据失败", e);
        }
    }

    /**
     * 保存用户数据到磁盘
     */
    private void saveUsersToDisk() {
        Path path = Paths.get(usersDataFile);

        lock.writeLock().lock();
        try {
            // 确保目录存在
            Path parent = path.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }

            // 排除管理员（密码由SecurityConfig管理，不持久化）
            List<UserRecord> records = userRecords.values().stream()
                .filter(r -> !r.isAdmin)
                .collect(java.util.stream.Collectors.toList());

            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(records);
            Files.writeString(path, json, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            log.debug("用户数据已保存到: {}, 共{}个用户", path.toAbsolutePath(), records.size());
        } catch (IOException e) {
            log.error("保存用户数据失败: {}", usersDataFile, e);
        } catch (Exception e) {
            log.error("序列化用户数据失败", e);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean userExists(String username) {
        return userDetailsManager.userExists(username);
    }

    /**
     * 创建用户
     */
    public void createUser(String username, String name, String role) {
        if (username == null || !username.matches("^[a-zA-Z0-9]{3,20}$")) {
            throw new IllegalArgumentException("工号必须为3-20位字母或数字");
        }
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("姓名不能为空");
        }

        // 校验角色
        if (!"USER".equals(role) && !"ADMIN".equals(role)) {
            throw new IllegalArgumentException("角色只能是 USER 或 ADMIN");
        }

        String encodedPassword = passwordEncoder.encode(DEFAULT_PASSWORD);
        UserDetails newUser = User.withUsername(username)
            .password(encodedPassword)
            .roles(role)
            .build();

        userDetailsManager.createUser(newUser);

        // 记录用户信息（包含加密后的密码用于持久化）
        UserRecord record = new UserRecord(username, role, false);
        record.name = name.trim();
        record.encodedPassword = encodedPassword;
        record.mustChangePassword = true;
        userRecords.put(username, record);

        // 同步保存到磁盘
        saveUsersToDisk();

        log.info("[AUDIT] action=createUser, username={}, name={}, role={}", username, name, role);
    }

    /**
     * 修改密码
     */
    public void changePassword(String username, String newPassword) {
        if (newPassword == null || newPassword.length() < 6) {
            throw new IllegalArgumentException("密码长度不能少于6位");
        }

        String encodedPassword = passwordEncoder.encode(newPassword);
        UserDetails existing = userDetailsManager.loadUserByUsername(username);

        User.UserBuilder builder = User.withUsername(username)
            .password(encodedPassword)
            .roles(userRecords.getOrDefault(username, new UserRecord(username, "USER", false)).role);

        userDetailsManager.updateUser(builder.build());

        // 更新记录
        UserRecord record = userRecords.get(username);
        if (record != null) {
            record.encodedPassword = encodedPassword;
            record.mustChangePassword = false;
            saveUsersToDisk();
        }

        log.info("[AUDIT] action=changePassword, username={}", username);
    }

    /**
     * 修改角色
     */
    public void changeRole(String username, String newRole) {
        if (!"USER".equals(newRole) && !"ADMIN".equals(newRole)) {
            throw new IllegalArgumentException("角色只能是 USER 或 ADMIN");
        }

        UserDetails existing = userDetailsManager.loadUserByUsername(username);

        User.UserBuilder builder = User.withUsername(username)
            .password(existing.getPassword())
            .roles(newRole);

        userDetailsManager.updateUser(builder.build());

        // 更新记录
        UserRecord record = userRecords.get(username);
        if (record != null) {
            record.role = newRole;
            saveUsersToDisk();
        }

        log.info("[AUDIT] action=changeRole, username={}, newRole={}", username, newRole);
    }
    /**
     * 用户自己修改密码（首次登录）
     */
    public void changeOwnPassword(String username, String oldPassword, String newPassword) {
        UserDetails user = userDetailsManager.loadUserByUsername(username);
        if (!passwordEncoder.matches(oldPassword, user.getPassword())) {
            throw new IllegalArgumentException("当前密码不正确");
        }
        if (newPassword == null || newPassword.length() < 6) {
            throw new IllegalArgumentException("新密码长度不能少于6位");
        }
        String ep = passwordEncoder.encode(newPassword);
        User.UserBuilder b = User.withUsername(username).password(ep)
            .roles(userRecords.getOrDefault(username, new UserRecord(username, "USER", false)).role);
        userDetailsManager.updateUser(b.build());
        UserRecord r = userRecords.get(username);
        if (r != null) {
            r.encodedPassword = ep;
            r.mustChangePassword = false;
            saveUsersToDisk();
        }
        log.info("[AUDIT] action=changeOwnPassword, username={}", username);
    }

    /**
     * 判断用户是否需要修改密码
     */
    public boolean mustChangePassword(String username) {
        UserRecord r = userRecords.get(username);
        return r != null && r.mustChangePassword;
    }

    /**
     * 设置用户是否需要修改密码
     */
    public void setMustChangePassword(String username, boolean mustChange) {
        UserRecord r = userRecords.get(username);
        if (r != null) {
            r.mustChangePassword = mustChange;
            saveUsersToDisk();
        }
    }

    /**
     * 获取用户姓名
     */
    public String getUserName(String username) {
        UserRecord r = userRecords.get(username);
        return r != null ? r.name : null;
    }

    /**
     * 获取下一个可用的4位工号
     */
    public String getNextId() {
        java.util.Set<String> used = new java.util.HashSet<>();
        for (String key : userRecords.keySet()) {
            if (key.length() == 4 && key.matches("[0-9]+")) used.add(key);
        }
        for (int i = 1; i <= 9999; i++) {
            String id = String.valueOf(i);
            while (id.length() < 4) id = "0" + id;
            if (!used.contains(id)) return id;
        }
        return null;
    }


    /**
     * 删除用户
     */
    public void deleteUser(String username) {
        // 不允许删除管理员
        UserRecord record = userRecords.get(username);
        if (record != null && record.isAdmin) {
            throw new IllegalArgumentException("不能删除管理员用户");
        }

        userDetailsManager.deleteUser(username);
        userRecords.remove(username);

        // 保存到磁盘
        saveUsersToDisk();

        log.info("[AUDIT] action=deleteUser, username={}", username);
    }

    /**
     * 列出所有用户
     */
    public List<Map<String, Object>> listUsers() {
        List<Map<String, Object>> users = new ArrayList<>();

        lock.readLock().lock();
        try {
            for (UserRecord record : userRecords.values()) {
                Map<String, Object> map = new LinkedHashMap<>();
                map.put("username", record.username);
                map.put("name", record.name != null ? record.name : "");
                map.put("role", record.role);
                map.put("mustChangePassword", record.mustChangePassword);
                map.put("isAdmin", record.isAdmin);
                map.put("groups", record.groups != null ? record.groups : Collections.emptyList());
                users.add(map);
            }
        } finally {
            lock.readLock().unlock();
        }

        return users;
    }

    /**
     * 获取用户的可访问分组列表
     */
    public List<String> getUserGroups(String username) {
        UserRecord record = userRecords.get(username);
        if (record == null) return Collections.emptyList();
        if (record.isAdmin) return null; // null表示全部
        return record.groups != null ? record.groups : Collections.singletonList("default");
    }

    /**
     * 设置用户的可访问分组
     */
    public void setUserGroups(String username, List<String> groups) {
        UserRecord record = userRecords.get(username);
        if (record == null) {
            throw new IllegalArgumentException("用户不存在: " + username);
        }
        record.groups = groups;
        saveUsersToDisk();
        log.info("[AUDIT] action=setUserGroups, username={}, groups={}", username, groups);
    }

    /**
     * 用户记录（用于持久化）
     */
    public static class UserRecord {
        public String username;
        public String name;
        public String role;
        public boolean mustChangePassword;
        public boolean isAdmin;
        public String encodedPassword;  // 仅用于持久化
        public List<String> groups;     // 可访问的报表分组ID列表，null或空表示全部

        public UserRecord() {}

        public UserRecord(String username, String role, boolean isAdmin) {
            this.username = username;
            this.role = role;
            this.isAdmin = isAdmin;
        }
    }
}
