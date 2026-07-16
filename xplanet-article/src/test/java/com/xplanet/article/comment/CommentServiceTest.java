package com.xplanet.article.comment;

import com.xplanet.api.request.CommentPublishRequest;
import com.xplanet.article.entity.Article;
import com.xplanet.article.mapper.ArticleMapper;
import com.xplanet.article.service.UserClient;
import com.xplanet.common.exception.BizException;
import com.xplanet.common.response.ErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CommentServiceTest {

    @Mock
    private CommentMapper commentMapper;

    @Mock
    private UserClient userClient;

    @Mock
    private ArticleMapper articleMapper;

    @InjectMocks
    private CommentService commentService;

    @Test
    void shouldPublishTopLevelCommentForExistingArticle() {
        when(articleMapper.selectById(1L)).thenReturn(article(1L));
        when(commentMapper.insert(any(Comment.class))).thenAnswer(invocation -> {
            Comment comment = invocation.getArgument(0);
            comment.setId(10L);
            return 1;
        });

        Long id = commentService.publish(2L, request(1L, 0L));

        assertThat(id).isEqualTo(10L);
        ArgumentCaptor<Comment> captor = ArgumentCaptor.forClass(Comment.class);
        verify(commentMapper).insert(captor.capture());
        assertThat(captor.getValue().getParentId()).isZero();
        assertThat(captor.getValue().getContent()).isEqualTo("comment");
        verify(commentMapper, never()).selectById(any());
    }

    @Test
    void shouldPublishReplyOnlyWhenParentIsTopLevelAndBelongsToArticle() {
        when(articleMapper.selectById(1L)).thenReturn(article(1L));
        when(commentMapper.selectById(5L)).thenReturn(comment(5L, 1L, 0L, 0));
        when(commentMapper.insert(any(Comment.class))).thenAnswer(invocation -> {
            Comment comment = invocation.getArgument(0);
            comment.setId(11L);
            return 1;
        });

        assertThat(commentService.publish(2L, request(1L, 5L))).isEqualTo(11L);
    }

    @Test
    void shouldRejectCommentForMissingArticle() {
        when(articleMapper.selectById(99L)).thenReturn(null);

        assertBizError(() -> commentService.publish(2L, request(99L, 0L)), ErrorCode.ARTICLE_NOT_FOUND);
        verify(commentMapper, never()).insert(any());
    }

    @Test
    void shouldRejectParentFromAnotherArticle() {
        when(articleMapper.selectById(1L)).thenReturn(article(1L));
        when(commentMapper.selectById(5L)).thenReturn(comment(5L, 2L, 0L, 0));

        assertBizError(() -> commentService.publish(2L, request(1L, 5L)), ErrorCode.COMMENT_PARENT_INVALID);
        verify(commentMapper, never()).insert(any());
    }

    @Test
    void shouldRejectReplyToReplyToKeepTwoLevelModel() {
        when(articleMapper.selectById(1L)).thenReturn(article(1L));
        when(commentMapper.selectById(6L)).thenReturn(comment(6L, 1L, 5L, 0));

        assertBizError(() -> commentService.publish(2L, request(1L, 6L)), ErrorCode.COMMENT_PARENT_INVALID);
        verify(commentMapper, never()).insert(any());
    }

    @Test
    void shouldRejectDeletedParent() {
        when(articleMapper.selectById(1L)).thenReturn(article(1L));
        when(commentMapper.selectById(5L)).thenReturn(comment(5L, 1L, 0L, 1));

        assertBizError(() -> commentService.publish(2L, request(1L, 5L)), ErrorCode.COMMENT_PARENT_INVALID);
        verify(commentMapper, never()).insert(any());
    }

    private void assertBizError(Runnable action, ErrorCode errorCode) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BizException.class)
                .extracting("code")
                .isEqualTo(errorCode.getCode());
    }

    private CommentPublishRequest request(Long articleId, Long parentId) {
        CommentPublishRequest request = new CommentPublishRequest();
        request.setArticleId(articleId);
        request.setParentId(parentId);
        request.setContent("  comment  ");
        return request;
    }

    private Article article(Long articleId) {
        Article article = new Article();
        article.setId(articleId);
        article.setDeleted(0);
        return article;
    }

    private Comment comment(Long id, Long articleId, Long parentId, int deleted) {
        Comment comment = new Comment();
        comment.setId(id);
        comment.setArticleId(articleId);
        comment.setParentId(parentId);
        comment.setDeleted(deleted);
        return comment;
    }
}
