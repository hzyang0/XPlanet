package com.xplanet.article.ai;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
public class ArticleInternalTokenVerifier {

    private final String token;

    public ArticleInternalTokenVerifier(@Value("${ai.internal-token:}") String token) {
        this.token = token;
    }

    @PostConstruct
    void validate() {
        if (token == null || token.length() < 32) {
            throw new IllegalStateException("AGENT_INTERNAL_TOKEN must contain at least 32 characters");
        }
    }

    public void require(String presented) {
        byte[] expected = token.getBytes(StandardCharsets.UTF_8);
        byte[] actual = presented == null ? new byte[0] : presented.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, actual)) {
            throw new SecurityException("invalid internal token");
        }
    }
}
