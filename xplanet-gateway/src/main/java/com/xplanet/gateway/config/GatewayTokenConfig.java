package com.xplanet.gateway.config;

import com.xplanet.common.auth.TokenService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GatewayTokenConfig {

    @Bean
    public TokenService gatewayTokenService(
            @Value("${security.token.secret:}") String secret,
            @Value("${security.token.expiration-seconds:86400}") long expirationSeconds,
            @Value("${security.token.issuer:xplanet}") String issuer) {
        return new TokenService(secret, expirationSeconds, issuer);
    }
}
