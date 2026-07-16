package com.xplanet.interaction.client;

import com.xplanet.common.exception.BizException;
import com.xplanet.common.response.ErrorCode;
import com.xplanet.common.response.R;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ArticleClientTest {

    private final ArticleServiceClient serviceClient = mock(ArticleServiceClient.class);
    private final ArticleClient articleClient = new ArticleClient(serviceClient);

    @Test
    void shouldReturnRemoteExistenceResult() {
        when(serviceClient.exists(9L)).thenReturn(R.ok(true));
        when(serviceClient.exists(10L)).thenReturn(R.ok(false));

        assertThat(articleClient.existsActive(9L)).isTrue();
        assertThat(articleClient.existsActive(10L)).isFalse();
    }

    @Test
    void shouldFailClosedWhenArticleServiceIsUnavailable() {
        when(serviceClient.exists(9L)).thenThrow(new RuntimeException("connection refused"));

        assertThatThrownBy(() -> articleClient.existsActive(9L))
                .isInstanceOf(BizException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.ARTICLE_SERVICE_UNAVAILABLE.getCode());
    }

    @Test
    void shouldFailClosedForMalformedResponse() {
        when(serviceClient.exists(9L)).thenReturn(R.ok(null));

        assertThatThrownBy(() -> articleClient.existsActive(9L))
                .isInstanceOf(BizException.class)
                .extracting("code")
                .isEqualTo(ErrorCode.ARTICLE_SERVICE_UNAVAILABLE.getCode());
    }
}
