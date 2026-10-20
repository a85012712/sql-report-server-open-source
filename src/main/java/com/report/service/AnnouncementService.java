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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 信息发布栏（主页公告）：data/announcements.json。
 * 管理员发布更新内容/通知/注意事项，全员在工作台查看。
 * 字段：id/title/content/type(update|notice|warning)/publisher/publishTime/pinned/enabled
 */
@Service
public class AnnouncementService {

    private static final Logger log = LoggerFactory.getLogger(AnnouncementService.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final ObjectMapper objectMapper;

    @Value("${app.users.data-file:}")
    private String usersDataFile;

    private final List<Announcement> items = new ArrayList<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    public AnnouncementService(ObjectMapper objectMapper) {
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
        return Paths.get(dataDir, "announcements.json");
    }

    private synchronized void load() {
        lock.writeLock().lock();
        try {
            items.clear();
            Path path = file();
            if (Files.exists(path)) {
                List<Announcement> list = objectMapper.readValue(Files.readString(path),
                    new TypeReference<List<Announcement>>() {});
                items.addAll(list);
            }
            log.info("信息发布栏加载完成: {} 条", items.size());
        } catch (Exception e) {
            log.error("加载信息发布栏失败", e);
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
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(items);
            AtomicFileWriter.writeString(file(), json);
        } catch (IOException e) {
            log.error("保存信息发布栏失败", e);
        }
    }

    /** 全员可见：启用中的公告，置顶优先、时间倒序 */
    public List<Announcement> listVisible() {
        lock.readLock().lock();
        try {
            List<Announcement> out = new ArrayList<>();
            for (Announcement a : items) {
                if (a.enabled) out.add(copy(a));
            }
            out.sort(Comparator.comparing((Announcement a) -> !a.pinned)
                .thenComparing(a -> a.publishTime == null ? "" : a.publishTime, Comparator.reverseOrder()));
            return out;
        } finally {
            lock.readLock().unlock();
        }
    }

    /** 管理视图：全部（含停用） */
    public List<Announcement> listAll() {
        lock.readLock().lock();
        try {
            List<Announcement> out = new ArrayList<>();
            for (Announcement a : items) out.add(copy(a));
            out.sort(Comparator.comparing((Announcement a) -> !a.pinned)
                .thenComparing(a -> a.publishTime == null ? "" : a.publishTime, Comparator.reverseOrder()));
            return out;
        } finally {
            lock.readLock().unlock();
        }
    }

    public Announcement create(String title, String content, String type, boolean pinned, String publisher) {
        if (title == null || title.trim().isEmpty()) throw new IllegalArgumentException("标题不能为空");
        if (content == null || content.trim().isEmpty()) throw new IllegalArgumentException("内容不能为空");
        Announcement a = new Announcement();
        a.id = String.valueOf(System.currentTimeMillis());
        a.title = title.trim();
        a.content = content.trim();
        a.type = normalizeType(type);
        a.pinned = pinned;
        a.enabled = true;
        a.publisher = publisher;
        a.publishTime = LocalDateTime.now().format(TS);
        lock.writeLock().lock();
        try {
            items.add(a);
            saveLocked();
        } finally {
            lock.writeLock().unlock();
        }
        log.info("[AUDIT] action=createAnnouncement, id={}, title={}, publisher={}", a.id, a.title, publisher);
        return copy(a);
    }

    public Announcement update(String id, String title, String content, String type,
                               Boolean pinned, Boolean enabled) {
        lock.writeLock().lock();
        try {
            Announcement a = find(id);
            if (a == null) throw new IllegalArgumentException("公告不存在");
            if (title != null && !title.trim().isEmpty()) a.title = title.trim();
            if (content != null && !content.trim().isEmpty()) a.content = content.trim();
            if (type != null) a.type = normalizeType(type);
            if (pinned != null) a.pinned = pinned;
            if (enabled != null) a.enabled = enabled;
            saveLocked();
            log.info("[AUDIT] action=updateAnnouncement, id={}", id);
            return copy(a);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void delete(String id) {
        lock.writeLock().lock();
        try {
            boolean removed = items.removeIf(a -> a.id.equals(id));
            if (!removed) throw new IllegalArgumentException("公告不存在");
            saveLocked();
            log.info("[AUDIT] action=deleteAnnouncement, id={}", id);
        } finally {
            lock.writeLock().unlock();
        }
    }

    private Announcement find(String id) {
        for (Announcement a : items) {
            if (a.id.equals(id)) return a;
        }
        return null;
    }

    private static String normalizeType(String type) {
        if ("notice".equals(type) || "warning".equals(type)) return type;
        return "update";
    }

    private static Announcement copy(Announcement a) {
        Announcement n = new Announcement();
        n.id = a.id;
        n.title = a.title;
        n.content = a.content;
        n.type = a.type;
        n.publisher = a.publisher;
        n.publishTime = a.publishTime;
        n.pinned = a.pinned;
        n.enabled = a.enabled;
        return n;
    }

    public static class Announcement {
        public String id;
        public String title;
        public String content;
        public String type;        // update=系统更新 notice=通知 warning=注意事项
        public String publisher;
        public String publishTime;
        public boolean pinned;
        public boolean enabled;
    }
}
