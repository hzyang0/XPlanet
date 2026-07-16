package com.xplanet.interaction.service;

import com.xplanet.common.exception.BizException;
import com.xplanet.interaction.persistence.LikeOutboxMapper;
import com.xplanet.interaction.persistence.LikeRelationMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LikeServiceTest {

    @Mock
    private LikeRelationMapper relationMapper;

    @Mock
    private LikeOutboxMapper outboxMapper;

    @InjectMocks
    private LikeService likeService;

    @Test
    void shouldCreateRelationAndOutboxForFirstLike() {
        when(relationMapper.reactivate(1L, 9L)).thenReturn(0);
        when(relationMapper.insertLikedIfAbsent(1L, 9L)).thenReturn(1);
        when(outboxMapper.insertEvent(anyString(), eq(1L), eq(9L), eq(1))).thenReturn(1);

        assertThat(likeService.like(1L, 9L)).isTrue();

        verify(outboxMapper).insertEvent(anyString(), eq(1L), eq(9L), eq(1));
    }

    @Test
    void shouldReactivateCanceledRelationWithoutInsert() {
        when(relationMapper.reactivate(1L, 9L)).thenReturn(1);
        when(outboxMapper.insertEvent(anyString(), eq(1L), eq(9L), eq(1))).thenReturn(1);

        assertThat(likeService.like(1L, 9L)).isTrue();

        verify(relationMapper, never()).insertLikedIfAbsent(1L, 9L);
        verify(outboxMapper).insertEvent(anyString(), eq(1L), eq(9L), eq(1));
    }

    @Test
    void shouldNotWriteOutboxForDuplicateLike() {
        when(relationMapper.reactivate(1L, 9L)).thenReturn(0);
        when(relationMapper.insertLikedIfAbsent(1L, 9L)).thenReturn(0);

        assertThat(likeService.like(1L, 9L)).isFalse();

        verify(outboxMapper, never()).insertEvent(anyString(), eq(1L), eq(9L), eq(1));
    }

    @Test
    void shouldWriteNegativeDeltaOnlyWhenCancelChangesState() {
        when(relationMapper.cancelIfLiked(1L, 9L)).thenReturn(1);
        when(outboxMapper.insertEvent(anyString(), eq(1L), eq(9L), eq(-1))).thenReturn(1);

        assertThat(likeService.cancel(1L, 9L)).isTrue();

        verify(outboxMapper).insertEvent(anyString(), eq(1L), eq(9L), eq(-1));
    }

    @Test
    void shouldTreatRepeatedCancelAsSuccessfulNoOp() {
        when(relationMapper.cancelIfLiked(1L, 9L)).thenReturn(0);

        assertThat(likeService.cancel(1L, 9L)).isTrue();

        verify(outboxMapper, never()).insertEvent(anyString(), eq(1L), eq(9L), eq(-1));
    }

    @Test
    void shouldFailTransactionBoundaryWhenOutboxInsertIsMissing() {
        when(relationMapper.reactivate(1L, 9L)).thenReturn(1);
        when(outboxMapper.insertEvent(anyString(), eq(1L), eq(9L), eq(1))).thenReturn(0);

        assertThatThrownBy(() -> likeService.like(1L, 9L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("failed to persist like outbox event");
    }

    @Test
    void shouldRejectInvalidIdentityOrArticle() {
        assertThatThrownBy(() -> likeService.like(null, 9L)).isInstanceOf(BizException.class);
        assertThatThrownBy(() -> likeService.like(1L, 0L)).isInstanceOf(BizException.class);
        verify(outboxMapper, never()).insertEvent(anyString(), eq(1L), eq(9L), eq(1));
    }
}
