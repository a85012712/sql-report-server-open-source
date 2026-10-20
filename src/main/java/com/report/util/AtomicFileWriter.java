package com.report.util;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 原子写文件：先写临时文件再 move，降低崩溃导致配置半写损坏的风险。
 * 临时文件名带随机后缀，避免并发写同一目标文件时互相覆盖临时文件。
 *
 * <p>临时文件在 move 前 {@code force(true)} 落盘：本机是普通硬件上的内网服务，
 * 断电是现实事件；没有 fsync 时 rename 可能先于数据落盘，重启后目标文件变成 0 字节。
 */
public final class AtomicFileWriter {

    private AtomicFileWriter() {}

    public static void writeString(Path path, String content) throws IOException {
        moveIntoTmp(path, content, StandardCopyOption.ATOMIC_MOVE, true);
    }

    /**
     * 目标已存在则失败的原子新建（保留 {@code CREATE_NEW} 的防覆盖语义）。
     * 用于导入报表：文件名冲突检测与写入之间仍有窗口，不能用 REPLACE_EXISTING。
     */
    public static void writeStringNew(Path path, String content) throws IOException {
        moveIntoTmp(path, content, StandardCopyOption.ATOMIC_MOVE, false);
    }

    private static void moveIntoTmp(Path path, String content,
                                    StandardCopyOption atomic, boolean replaceExisting) throws IOException {
        if (path == null) {
            throw new IllegalArgumentException("path 不能为空");
        }
        String text = content != null ? content : "";
        Path parent = path.getParent();
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent);
        }
        Path tmp = path.resolveSibling(path.getFileName().toString() + ".tmp." + UUID.randomUUID());
        try {
            try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                ByteBuffer buf = ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8));
                while (buf.hasRemaining()) {
                    ch.write(buf);
                }
                ch.force(true);
            }
            List<CopyOption> opts = new ArrayList<>();
            if (replaceExisting) opts.add(StandardCopyOption.REPLACE_EXISTING);
            opts.add(atomic);
            try {
                Files.move(tmp, path, opts.toArray(new CopyOption[0]));
            } catch (AtomicMoveNotSupportedException e) {
                opts.remove(opts.size() - 1);
                Files.move(tmp, path, opts.toArray(new CopyOption[0]));
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
