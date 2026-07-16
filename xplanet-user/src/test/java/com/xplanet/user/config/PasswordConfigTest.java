package com.xplanet.user.config;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PasswordConfigTest {

    private static final String DEMO_HASH =
            "{bcrypt}$2a$10$dXJ3SW6G7P50lGmMkkmwe.20cQQubK3.HZWzG3YB1tlRy.fqvM/BG";

    @Test
    void demoSeedHashMatchesDocumentedPassword() {
        PasswordEncoder encoder = new PasswordConfig().passwordEncoder();

        assertTrue(encoder.matches("password", DEMO_HASH));
        assertFalse(encoder.matches("wrong", DEMO_HASH));
    }
}
