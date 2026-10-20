package com.report.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SecretCryptoTest {

    @Test
    void encryptDecryptRoundTrip() {
        SecretCrypto crypto = new SecretCrypto("unit-test-key-please-change");
        assertTrue(crypto.isEnabled());
        String plain = "demo-secret-password";
        String enc = crypto.encryptIfNeeded(plain);
        assertNotNull(enc);
        assertTrue(enc.startsWith(SecretCrypto.PREFIX));
        assertNotEquals(plain, enc);
        assertEquals(plain, crypto.decryptIfNeeded(enc));
    }

    @Test
    void envPlaceholderNotEncrypted() {
        SecretCrypto crypto = new SecretCrypto("unit-test-key");
        String env = "${DB_PASSWORD}";
        assertEquals(env, crypto.encryptIfNeeded(env));
    }

    @Test
    void alreadyEncryptedNotDoubleEncrypted() {
        SecretCrypto crypto = new SecretCrypto("unit-test-key");
        String once = crypto.encryptIfNeeded("abc12345");
        assertEquals(once, crypto.encryptIfNeeded(once));
    }

    @Test
    void disabledPassthrough() {
        SecretCrypto crypto = new SecretCrypto("");
        assertFalse(crypto.isEnabled());
        assertEquals("plain", crypto.encryptIfNeeded("plain"));
        assertEquals("plain", crypto.decryptIfNeeded("plain"));
    }

    @Test
    void decryptWithoutKeyFails() {
        SecretCrypto withKey = new SecretCrypto("k1");
        String enc = withKey.encryptIfNeeded("secret");
        SecretCrypto noKey = new SecretCrypto(null);
        assertThrows(IllegalStateException.class, () -> noKey.decryptIfNeeded(enc));
    }
}
