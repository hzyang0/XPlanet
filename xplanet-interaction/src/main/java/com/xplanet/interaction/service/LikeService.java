package com.xplanet.interaction.service;

import com.xplanet.common.exception.BizException;
import com.xplanet.common.response.ErrorCode;
import com.xplanet.interaction.client.ArticleClient;
import com.xplanet.interaction.persistence.LikeOutboxMapper;
import com.xplanet.interaction.persistence.LikeRelationMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 点赞业务状态机。
 *
 * <p>用户/文章关系是事实源。状态真实变化时，在同一个本地事务中写入 Outbox；
 * 因此接口成功意味着状态和待投递事件都已持久化，而不是仅写入 Redis 后碰运气发送 MQ。
 */
@Service
@RequiredArgsConstructor
public class LikeService {

    private final LikeRelationMapper relationMapper;
    private final LikeOutboxMapper outboxMapper;
    private final ArticleClient articleClient;

    /** 返回 true 表示本次从未点赞/已取消变为已点赞，false 表示重复点赞。 */
    @Transactional
    public boolean like(Long userId, Long articleId) {
        validate(userId, articleId);
        if (!articleClient.existsActive(articleId)) {
            throw new BizException(ErrorCode.ARTICLE_NOT_FOUND);
        }

        int changed = relationMapper.reactivate(userId, articleId);
        if (changed == 0) {
            changed = relationMapper.insertLikedIfAbsent(userId, articleId);
        }
        if (changed == 0) {
            return false;
        }

        appendOutbox(userId, articleId, 1);
        return true;
    }

    /** 取消操作保持幂等；无论是否发生状态变化都返回 true。 */
    @Transactional
    public boolean cancel(Long userId, Long articleId) {
        validate(userId, articleId);

        if (relationMapper.cancelIfLiked(userId, articleId) > 0) {
            appendOutbox(userId, articleId, -1);
        }
        return true;
    }

    private void appendOutbox(Long userId, Long articleId, int delta) {
        int inserted = outboxMapper.insertEvent(UUID.randomUUID().toString(), userId, articleId, delta);
        if (inserted != 1) {
            throw new IllegalStateException("failed to persist like outbox event");
        }
    }

    private void validate(Long userId, Long articleId) {
        if (userId == null || articleId == null || userId <= 0 || articleId <= 0) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
    }
}
