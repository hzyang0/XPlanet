package com.xplanet.article.service;

import com.xplanet.api.vo.UserProfileVO;
import com.xplanet.article.client.UserServiceClient;
import com.xplanet.common.response.R;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserClientTest {

    @Mock
    private UserServiceClient userServiceClient;

    @InjectMocks
    private UserClient userClient;

    @Test
    void returnsAnonymousWithoutRemoteCallWhenUserIdIsNull() {
        assertEquals("匿名用户", userClient.getUserName(null));
        verify(userServiceClient, never()).getUser(null);
    }

    @Test
    void prefersNicknameAndCachesSuccessfulResponse() {
        UserProfileVO profile = profile("alice", "Alice");
        when(userServiceClient.getUser(1L)).thenReturn(R.ok(profile));

        assertEquals("Alice", userClient.getUserName(1L));
        assertEquals("Alice", userClient.getUserName(1L));
        verify(userServiceClient, times(1)).getUser(1L);
    }

    @Test
    void fallsBackToUsernameWhenNicknameIsBlank() {
        when(userServiceClient.getUser(2L)).thenReturn(R.ok(profile("bob", "  ")));

        assertEquals("bob", userClient.getUserName(2L));
    }

    @Test
    void degradesWhenRemoteCallFails() {
        when(userServiceClient.getUser(3L)).thenThrow(new IllegalStateException("timeout"));

        assertEquals("用户3", userClient.getUserName(3L));
        assertEquals("用户3", userClient.getUserName(3L));
        verify(userServiceClient, times(2)).getUser(3L);
    }

    private UserProfileVO profile(String username, String nickname) {
        UserProfileVO profile = new UserProfileVO();
        profile.setUsername(username);
        profile.setNickname(nickname);
        return profile;
    }
}
