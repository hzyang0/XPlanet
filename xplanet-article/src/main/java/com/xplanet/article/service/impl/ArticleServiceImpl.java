package com.xplanet.article.service.impl;

import com.xplanet.api.dto.ArticleChangeMessage;
import com.xplanet.api.request.ArticlePublishRequest;
import com.xplanet.api.vo.ArticleDetailVO;
import com.xplanet.article.cache.ArticleCacheManager;
import com.xplanet.article.entity.Article;
import com.xplanet.article.mapper.ArticleMapper;
import com.xplanet.article.service.ArticleService;
import com.xplanet.common.constant.MqTopics;
import com.xplanet.common.exception.BizException;
import com.xplanet.common.response.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.BeanUtils;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 文章服务实现。
 *
 * <h3>缓存一致性策略(Cache Aside + 延迟双删 + Canal 兜底)</h3>
 *
 * <pre>
 * 写流程:
 *   1) DB 更新(事务内)
 *   2) 立即删 L1+L2 缓存
 *   3) 发 MQ 消息(本节点 invalidate 已完成,但其他实例 L1 还在 → MQ 广播让其他实例清 L1)
 *   4) 异步延迟 1s 再删一次 Redis(防止"读旧 → 写新 → 读旧的线程把旧值写回缓存"竞态)
 *
 * 兜底:
 *   Canal 监听 MySQL binlog,任何漏发的写操作都会被捕获并清缓存,
 *   保证最终一致性不依赖业务代码的正确性。
 * </pre>
 *
 * <h3>为什么需要 Canal 兜底?</h3>
 * <p>双删依赖业务代码"先更新 DB 再删缓存"必须完整执行。一旦:
 * 1) 业务代码漏写了删缓存(比如新人改代码);
 * 2) DDL 操作绕过应用直接改 DB;
 * 3) 后台脚本批量修改数据;
 * 这些场景下应用层双删失效。Canal 订阅 binlog 是最后一道防线。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ArticleServiceImpl implements ArticleService {

    private final ArticleMapper articleMapper;
    private final ArticleCacheManager cacheManager;
    private final RocketMQTemplate rocketMQTemplate;

    @Override
    public ArticleDetailVO getDetail(Long articleId) {
        if (articleId == null || articleId <= 0) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        ArticleDetailVO vo = cacheManager.get(articleId, this::loadFromDb);
        if (vo == null) {
            throw new BizException(ErrorCode.ARTICLE_NOT_FOUND);
        }
        return vo;
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public Long publish(Long authorId, ArticlePublishRequest req) {
        Article a = new Article();
        a.setAuthorId(authorId);
        a.setTitle(req.getTitle());
        a.setContent(req.getContent());
        a.setTags(req.getTags());
        a.setLikeCount(0L);
        a.setViewCount(0L);
        a.setDeleted(0);
        a.setCreateTime(LocalDateTime.now());
        a.setUpdateTime(LocalDateTime.now());
        articleMapper.insert(a);
        // 新建无需删缓存
        return a.getId();
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public void update(Long authorId, Long articleId, ArticlePublishRequest req) {
        Article exist = articleMapper.selectById(articleId);
        if (exist == null || exist.getDeleted() != 0) {
            throw new BizException(ErrorCode.ARTICLE_NOT_FOUND);
        }
        if (!exist.getAuthorId().equals(authorId)) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }

        exist.setTitle(req.getTitle());
        exist.setContent(req.getContent());
        exist.setTags(req.getTags());
        exist.setUpdateTime(LocalDateTime.now());
        articleMapper.updateById(exist);

        // 双删第一删 + 广播 MQ + 异步延迟二删
        afterMutation(articleId, "UPDATE");
    }

    @Transactional(rollbackFor = Exception.class)
    @Override
    public void delete(Long authorId, Long articleId) {
        Article exist = articleMapper.selectById(articleId);
        if (exist == null) {
            return;
        }
        if (!exist.getAuthorId().equals(authorId)) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        articleMapper.deleteById(articleId);
        afterMutation(articleId, "DELETE");
    }

    /**
     * 写后处理: 本地 + Redis 删除 + MQ 广播 + 异步二删。
     */
    private void afterMutation(Long articleId, String op) {
        cacheManager.invalidate(articleId);
        ArticleChangeMessage msg = ArticleChangeMessage.builder()
                .articleId(articleId)
                .op(op)
                .timestamp(System.currentTimeMillis())
                .build();
        rocketMQTemplate.convertAndSend(MqTopics.TOPIC_ARTICLE_CHANGE, msg);
        delayedSecondDelete(articleId);
    }

    /**
     * 延迟双删第二删。
     * 用 Async 简单实现; 生产建议用 RocketMQ 的延迟消息(message.setDelayTimeLevel(2)对应 5s)。
     */
    @Async("cacheTaskExecutor")
    public void delayedSecondDelete(Long articleId) {
        try {
            Thread.sleep(1000);
            cacheManager.invalidate(articleId);
            log.debug("second-delete done for articleId={}", articleId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 回源 DB,返回 VO(避免 entity 直接暴露) */
    private ArticleDetailVO loadFromDb(Long articleId) {
        Article a = articleMapper.selectById(articleId);
        if (a == null || a.getDeleted() != 0) return null;
        ArticleDetailVO vo = new ArticleDetailVO();
        BeanUtils.copyProperties(a, vo);
        // authorName 实际应该走 user 服务获取,这里 demo 简化
        vo.setAuthorName("user_" + a.getAuthorId());
        return vo;
    }
}
