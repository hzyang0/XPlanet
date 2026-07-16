package com.xplanet.article.projection;

import com.xplanet.article.mapper.ArticleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 在一个数据库事务中聚合持久化增量、更新文章计数并标记事件。 */
@Service
@RequiredArgsConstructor
public class LikeCountProjectionService {

    private final LikeCountDeltaMapper deltaMapper;
    private final ArticleMapper articleMapper;

    @Transactional
    public int flushBatch(int batchSize) {
        List<LikeCountDelta> pending = deltaMapper.lockPending(batchSize);
        if (pending.isEmpty()) {
            return 0;
        }

        Map<Long, List<LikeCountDelta>> byArticle = pending.stream()
                .collect(Collectors.groupingBy(
                        LikeCountDelta::getArticleId, LinkedHashMap::new, Collectors.toList()));

        for (Map.Entry<Long, List<LikeCountDelta>> entry : byArticle.entrySet()) {
            List<Long> ids = entry.getValue().stream().map(LikeCountDelta::getId).toList();
            long delta = entry.getValue().stream().mapToLong(LikeCountDelta::getDelta).sum();
            if (delta == 0) {
                markApplied(ids);
                continue;
            }

            int updated = articleMapper.incrLikeCount(entry.getKey(), delta);
            if (updated == 1) {
                markApplied(ids);
            } else {
                int rejected = deltaMapper.markRejected(
                        ids, "article missing or like count would become negative");
                if (rejected != ids.size()) {
                    throw new IllegalStateException("failed to reject all like projection events");
                }
            }
        }
        return pending.size();
    }

    private void markApplied(List<Long> ids) {
        if (deltaMapper.markApplied(ids) != ids.size()) {
            throw new IllegalStateException("failed to mark all like projection events as applied");
        }
    }
}
