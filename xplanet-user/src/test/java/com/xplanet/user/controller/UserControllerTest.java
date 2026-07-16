package com.xplanet.user.controller;

import com.xplanet.common.auth.TokenService;
import com.xplanet.common.exception.BizException;
import com.xplanet.common.response.ErrorCode;
import com.xplanet.common.response.R;
import com.xplanet.user.entity.User;
import com.xplanet.user.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserControllerTest {

    @Mock
    private UserMapper userMapper;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private TokenService tokenService;

    @InjectMocks
    private UserController controller;

    @Test
    void issuesTokenWhenPasswordMatches() {
        User user = user();
        when(userMapper.selectOne(any())).thenReturn(user);
        when(passwordEncoder.matches("password", user.getPasswordHash())).thenReturn(true);
        when(tokenService.issue(1L)).thenReturn("signed-token");

        R<Map<String, Object>> response = controller.login(request("alice", "password"));

        assertEquals("signed-token", response.getData().get("token"));
        assertEquals(1L, response.getData().get("userId"));
    }

    @Test
    void rejectsWrongPasswordWithoutIssuingToken() {
        User user = user();
        when(userMapper.selectOne(any())).thenReturn(user);
        when(passwordEncoder.matches("wrong", user.getPasswordHash())).thenReturn(false);

        BizException error = assertThrows(BizException.class,
                () -> controller.login(request("alice", "wrong")));

        assertEquals(ErrorCode.USER_CREDENTIALS_INVALID.getCode(), error.getCode());
        verify(tokenService, never()).issue(1L);
    }

    @Test
    void usesSameCredentialErrorWhenUserDoesNotExist() {
        when(userMapper.selectOne(any())).thenReturn(null);
        when(passwordEncoder.matches(any(), any())).thenReturn(false);

        BizException error = assertThrows(BizException.class,
                () -> controller.login(request("missing", "password")));

        assertEquals(ErrorCode.USER_CREDENTIALS_INVALID.getCode(), error.getCode());
        verify(tokenService, never()).issue(1L);
    }

    private User user() {
        User user = new User();
        user.setId(1L);
        user.setUsername("alice");
        user.setNickname("Alice");
        user.setPasswordHash("{bcrypt}hash");
        return user;
    }

    private UserController.LoginRequest request(String username, String password) {
        UserController.LoginRequest request = new UserController.LoginRequest();
        request.setUsername(username);
        request.setPassword(password);
        return request;
    }
}
