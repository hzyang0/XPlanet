package com.xplanet.common.auth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TokenServiceTest {

    private static final String SECRET_A = "0123456789abcdef0123456789abcdef";
    private static final String SECRET_B = "abcdef0123456789abcdef0123456789";

    @Test
    void issuesAndVerifiesSignedToken() {
        TokenService service = new TokenService(SECRET_A, 60, "xplanet-test");

        String token = service.issue(42L);

        assertEquals(42L, service.verify(token));
    }

    @Test
    void rejectsTokenSignedWithAnotherSecret() {
        TokenService issuer = new TokenService(SECRET_A, 60, "xplanet-test");
        TokenService verifier = new TokenService(SECRET_B, 60, "xplanet-test");

        assertNull(verifier.verify(issuer.issue(42L)));
    }

    @Test
    void rejectsMalformedToken() {
        TokenService service = new TokenService(SECRET_A, 60, "xplanet-test");

        assertNull(service.verify("not-a-jwt"));
        assertNull(service.verify(null));
    }

    @Test
    void rejectsWeakSecretAtStartup() {
        assertThrows(IllegalStateException.class,
                () -> new TokenService("too-short", 60, "xplanet-test"));
    }
}
