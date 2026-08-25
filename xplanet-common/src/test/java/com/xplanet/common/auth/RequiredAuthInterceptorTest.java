package com.xplanet.common.auth;

import com.xplanet.common.exception.BizException;
import com.xplanet.common.response.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RequiredAuthInterceptorTest {

    @AfterEach
    void clearContext() {
        UserContext.clear();
    }

    @Test
    void shouldAuthenticateGetRequestAndPopulateContext() {
        TokenService tokenService = mock(TokenService.class);
        when(tokenService.verify("valid-token")).thenReturn(7L);
        RequiredAuthInterceptor interceptor = new RequiredAuthInterceptor(tokenService);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/article/1");
        request.addHeader("Authorization", "Bearer valid-token");

        assertThat(interceptor.preHandle(request, new MockHttpServletResponse(), new Object())).isTrue();
        assertThat(UserContext.getUserId()).isEqualTo(7L);

        interceptor.afterCompletion(request, new MockHttpServletResponse(), new Object(), null);
        assertThat(UserContext.getUserId()).isNull();
    }

    @Test
    void shouldRejectUnauthenticatedGetRequest() {
        TokenService tokenService = mock(TokenService.class);
        RequiredAuthInterceptor interceptor = new RequiredAuthInterceptor(tokenService);

        assertThatThrownBy(() -> interceptor.preHandle(
                new MockHttpServletRequest("GET", "/api/article/1"),
                new MockHttpServletResponse(), new Object()))
                .isInstanceOf(BizException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.USER_NOT_LOGIN.getCode());
    }
}
