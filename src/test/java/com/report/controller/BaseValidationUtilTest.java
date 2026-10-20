package com.report.controller;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BaseValidationUtilTest {

    @Test
    void passwordTooShort() {
        assertTrue(BaseValidationUtil.validatePassword("Ab1").isPresent());
        assertTrue(BaseValidationUtil.validatePassword("Abcdef1").isPresent()); // 7
    }

    @Test
    void passwordNeedLetterAndDigit() {
        assertTrue(BaseValidationUtil.validatePassword("abcdefgh").isPresent());
        assertTrue(BaseValidationUtil.validatePassword("12345678").isPresent());
    }

    @Test
    void passwordOk() {
        assertTrue(BaseValidationUtil.validatePassword("Abcdef12").isEmpty());
        assertTrue(BaseValidationUtil.validatePassword("passw0rd").isEmpty());
    }

    @Test
    void usernameRules() {
        assertTrue(BaseValidationUtil.validateUsername("ab").isPresent());
        assertTrue(BaseValidationUtil.validateUsername("9061").isEmpty());
        assertTrue(BaseValidationUtil.validateUsername("user_01").isEmpty());
        assertTrue(BaseValidationUtil.validateUsername("a-b").isPresent());
    }

    @Test
    void roleRules() {
        assertTrue(BaseValidationUtil.validateRole("USER").isEmpty());
        assertTrue(BaseValidationUtil.validateRole("ADMIN").isEmpty());
        assertTrue(BaseValidationUtil.validateRole("GUEST").isPresent());
    }
}
