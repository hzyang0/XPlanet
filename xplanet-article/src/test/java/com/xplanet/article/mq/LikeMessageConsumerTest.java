package com.xplanet.article.mq;

import com.xplanet.api.dto.LikeMessage;
import com.xplanet.article.projection.LikeCountDeltaMapper;
import com.xplanet.common.util.JsonUtil;
import org.apache.rocketmq.common.message.MessageExt;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LikeMessageConsumerTest {

    private final LikeCountDeltaMapper deltaMapper = mock(LikeCountDeltaMapper.class);
    private final LikeMessageConsumer consumer = new LikeMessageConsumer(deltaMapper);

    @Test
    void shouldPersistConfirmedDeltaUsingEventId() {
        LikeMessage event = event("event-1", 1);
        when(deltaMapper.insertIgnore("event-1", 9L, 1)).thenReturn(1);

        consumer.onMessage(message(event));

        verify(deltaMapper).insertIgnore("event-1", 9L, 1);
    }

    @Test
    void shouldAcceptDuplicateAsIdempotentNoOp() {
        LikeMessage event = event("event-1", 1);
        when(deltaMapper.insertIgnore("event-1", 9L, 1)).thenReturn(0);

        consumer.onMessage(message(event));

        verify(deltaMapper).insertIgnore("event-1", 9L, 1);
    }

    @Test
    void shouldRejectInvalidDelta() {
        assertThatThrownBy(() -> consumer.onMessage(message(event("event-2", 2))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid like event");
    }

    private LikeMessage event(String eventId, int delta) {
        return LikeMessage.builder()
                .actionId(eventId)
                .userId(7L)
                .articleId(9L)
                .delta(delta)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    private MessageExt message(LikeMessage event) {
        MessageExt message = new MessageExt();
        message.setBody(JsonUtil.toJson(event).getBytes(StandardCharsets.UTF_8));
        return message;
    }
}
