package com.report;

import com.report.config.LoginAttemptService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

class LoginAttemptServiceTest {

    private LoginAttemptService service;

    @BeforeEach
    void setUp() {
        service = new LoginAttemptService();
        ReflectionTestUtils.setField(service, "maxFailures", 3);
        ReflectionTestUtils.setField(service, "lockMinutes", 15);
    }

    @Test
    void locksAfterMaxFailures() {
        assertFalse(service.isLocked("alice"));
        service.loginFailed("alice");
        service.loginFailed("alice");
        assertFalse(service.isLocked("alice"));
        service.loginFailed("alice");
        assertTrue(service.isLocked("alice"));
        assertTrue(service.remainingLockSeconds("alice") > 0);
    }

    @Test
    void successClearsFailures() {
        service.loginFailed("bob");
        service.loginFailed("bob");
        service.loginSucceeded("bob");
        assertFalse(service.isLocked("bob"));
        service.loginFailed("bob");
        assertFalse(service.isLocked("bob"));
    }
}
