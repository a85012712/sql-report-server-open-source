package com.report.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class AtomicFileWriterTest {

    @TempDir
    Path tempDir;

    @Test
    void writeCreatesFileAtomically() throws Exception {
        Path target = tempDir.resolve("users.json");
        AtomicFileWriter.writeString(target, "{\"ok\":true}");
        assertTrue(Files.exists(target));
        assertEquals("{\"ok\":true}", Files.readString(target, StandardCharsets.UTF_8));
        assertFalse(Files.exists(tempDir.resolve("users.json.tmp")));
    }

    @Test
    void overwriteExisting() throws Exception {
        Path target = tempDir.resolve("cfg.json");
        AtomicFileWriter.writeString(target, "v1");
        AtomicFileWriter.writeString(target, "v2");
        assertEquals("v2", Files.readString(target));
    }
}
