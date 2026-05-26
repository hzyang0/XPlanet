package com.xplanet.article.service.impl;

import com.xplanet.api.dto.ArticleChangeMessage;
import com.xplanet.api.request.ArticlePublishRequest;
import com.xplanet.api.vo.ArticleDetailVO;
import com.xplanet.article.cache.ArticleCacheManager;
import com.xplanet.article.cache.CacheDelayTask;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;

/**
 * 文章服务实现。
 *
 * <h3>缓存一致性策略:Cache Aside + 延迟双删 + MQ 广播</h3>
 *
 * <pre>
 * 写流程:
 *   1) 事务内更新 DB
 *   2) 删 L1 + L2 缓存(第一删)
 *   3) 事务提交后:
 *      - 发 MQ 广播消息,通知其他实例清各自的 L1 本地缓存
 *      - 触发异步延迟第二删,杀掉「读旧值线程在第一删后又写回缓存」的竞态
 * </pre>
 *
 * <h3>几个关键设计点</h3>
 * <ul>
 *   <li><b>为什么删缓存而不是更新缓存</b>:并发写下更新缓存会脏;且写少读多时更新缓存浪费。</li>
 *   <li><b>为什么要第二删</b>:防止 T1(读 miss → 查到旧值)与 T2(写 + 删缓存)交叉时,
 *       T1 把旧值写回缓存。延迟 1s 再删一次可覆盖该窗口。</li>
 *   <li><b>为什么放在事务提交后</b>:若在事务内删缓存/发 MQ,事务尚未提交时其他请求回源会读到旧数据;
 *       且第二删的 sleep 若在事务内会拉长事务、占用连接。用事务同步回调保证提交后执行。</li>
 *   <li><b>多实例 L1 一致</b>:L1 是进程内 Caffeine,靠 MQ 广播(BROADCASTING)让每个实例都清自己的 L1。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ArticleServiceImpl implements ArticleService {

    private final ArticleMapper articleMapper;
    private final ArticleCacheManager cacheManager;
    private final RocketMQTemplate rocketMQTemplate;
    private final CacheDelayTask cacheDelayTask;

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

        // 第一删(事务内,先让本节点缓存失效)
        cacheManager.invalidate(articleId);
        // 第二删 + 广播延迟到事务提交后
        afterCommit(articleId, "UPDATE");
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
        cacheManager.invalidate(articleId);
        afterCommit(articleId, "DELETE");
    }

    /**
     * 注册事务提交后的回调:发 MQ 广播 + 触发异步延迟第二删。
     * <p>用事务同步保证这些动作只在数据库真正提交后执行,避免读到未提交数据,
     * 也避免把异步任务的耗时算进事务。
     */
    private void afterCommit(Long articleId, String op) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publishAndSecondDelete(articleId, op);
                }
            });
        } else {
            // 无事务上下文(理论上不会发生,兜底)
            publishAndSecondDelete(articleId, op);
        }
    }

    private void publishAndSecondDelete(Long articleId, String op) {
        ArticleChangeMessage msg = ArticleChangeMessage.builder()
                .articleId(articleId)
                .op(op)
                .timestamp(System.currentTimeMillis())
                .build();
        rocketMQTemplate.convertAndSend(MqTopics.TOPIC_ARTICLE_CHANGE, msg);
        // 跨 Bean 调用,@Async 生效,真正异步执行
        cacheDelayTask.secondDelete(articleId);
    }

    /** 回源 DB,返回 VO(避免 entity 直接暴露) */
    private ArticleDetailVO loadFromDb(Long articleId) {
        Article a = articleMapper.selectById(articleId);
        if (a == null || a.getDeleted() != 0) return null;
        ArticleDetailVO vo = new ArticleDetailVO();
        BeanUtils.copyProperties(a, vo);
        // authorName 实际应走 user 服务获取,这里 demo 简化
        vo.setAuthorName("user_" + a.getAuthorId());
        return vo;
    }
}
