package com.xplanet.common.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class RateLimitAspectTest {

    @Test
    void shouldIgnoreForwardedForByDefault() {
        RateLimitAspect aspect = new RateLimitAspect(mock(StringRedisTemplate.class), false);
        MockHttpServletRequest request = request("10.0.0.8", "203.0.113.10");

        assertThat(aspect.resolveClientIp(request)).isEqualTo("10.0.0.8");
    }

    @Test
    void shouldUseFirstForwardedAddressWhenTrustedProxyIsEnabled() {
        RateLimitAspect aspect = new RateLimitAspect(mock(StringRedisTemplate.class), true);
        MockHttpServletRequest request = request("10.0.0.8", "203.0.113.10, 10.0.0.2");

        assertThat(aspect.resolveClientIp(request)).isEqualTo("203.0.113.10");
    }

    @Test
    void shouldFallBackToRemoteAddressWhenForwardedForIsBlank() {
        RateLimitAspect aspect = new RateLimitAspect(mock(StringRedisTemplate.class), true);
        MockHttpServletRequest request = request("10.0.0.8", "   ");

        assertThat(aspect.resolveClientIp(request)).isEqualTo("10.0.0.8");
    }

    @Test
    void shouldReturnUnknownWhenRemoteAddressIsMissing() {
        RateLimitAspect aspect = new RateLimitAspect(mock(StringRedisTemplate.class), false);
        MockHttpServletRequest request = request(null, null);

        assertThat(aspect.resolveClientIp(request)).isEqualTo("unknown");
    }

    private MockHttpServletRequest request(String remoteAddress, String forwardedFor) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddress);
        if (forwardedFor != null) {
            request.addHeader("X-Forwarded-For", forwardedFor);
        }
        return request;
    }
}
