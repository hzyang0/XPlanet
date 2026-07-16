package com.xplanet.article.projection;

import com.xplanet.article.mapper.ArticleMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LikeCountProjectionServiceTest {

    @Mock
    private LikeCountDeltaMapper deltaMapper;

    @Mock
    private ArticleMapper articleMapper;

    @InjectMocks
    private LikeCountProjectionService projectionService;

    @Test
    void shouldReturnImmediatelyWhenNoPendingEventExists() {
        when(deltaMapper.lockPending(100)).thenReturn(List.of());

        assertThat(projectionService.flushBatch(100)).isZero();

        verify(articleMapper, never()).incrLikeCount(9L, 1L);
    }

    @Test
    void shouldAggregateByArticleAndApplyZeroSumWithoutArticleUpdate() {
        LikeCountDelta add = delta(1L, 9L, 1);
        LikeCountDelta cancel = delta(2L, 9L, -1);
        LikeCountDelta anotherAdd = delta(3L, 10L, 1);
        when(deltaMapper.lockPending(100)).thenReturn(List.of(add, cancel, anotherAdd));
        when(articleMapper.incrLikeCount(10L, 1L)).thenReturn(1);
        when(deltaMapper.markApplied(List.of(1L, 2L))).thenReturn(2);
        when(deltaMapper.markApplied(List.of(3L))).thenReturn(1);

        assertThat(projectionService.flushBatch(100)).isEqualTo(3);

        verify(deltaMapper).markApplied(List.of(1L, 2L));
        verify(articleMapper, never()).incrLikeCount(9L, 0L);
        verify(articleMapper).incrLikeCount(10L, 1L);
        verify(deltaMapper).markApplied(List.of(3L));
    }

    @Test
    void shouldRejectProjectionWhenArticleCannotAcceptDelta() {
        LikeCountDelta delta = delta(4L, 11L, -1);
        when(deltaMapper.lockPending(100)).thenReturn(List.of(delta));
        when(articleMapper.incrLikeCount(11L, -1L)).thenReturn(0);
        when(deltaMapper.markRejected(
                List.of(4L), "article missing or like count would become negative")).thenReturn(1);

        assertThat(projectionService.flushBatch(100)).isEqualTo(1);

        verify(deltaMapper).markRejected(
                List.of(4L), "article missing or like count would become negative");
    }

    @Test
    void shouldFailBatchWhenNotEveryLockedEventIsMarked() {
        LikeCountDelta delta = delta(5L, 12L, 1);
        when(deltaMapper.lockPending(100)).thenReturn(List.of(delta));
        when(articleMapper.incrLikeCount(12L, 1L)).thenReturn(1);
        when(deltaMapper.markApplied(List.of(5L))).thenReturn(0);

        assertThatThrownBy(() -> projectionService.flushBatch(100))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("failed to mark all like projection events as applied");
    }

    private LikeCountDelta delta(Long id, Long articleId, long value) {
        LikeCountDelta delta = new LikeCountDelta();
        delta.setId(id);
        delta.setEventId("event-" + id);
        delta.setArticleId(articleId);
        delta.setDelta(value);
        return delta;
    }
}
