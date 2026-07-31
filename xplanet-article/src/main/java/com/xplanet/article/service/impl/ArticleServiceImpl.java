package com.xplanet.article.service.impl;

import com.xplanet.api.request.ArticlePublishRequest;
import com.xplanet.api.vo.ArticleDetailVO;
import com.xplanet.api.vo.ArticleListItemVO;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xplanet.article.cache.ArticleCacheManager;
import com.xplanet.article.entity.Article;
import com.xplanet.article.mapper.ArticleMapper;
import com.xplanet.article.outbox.ArticleChangeOutboxMapper;
import com.xplanet.article.service.ArticleService;
import com.xplanet.article.service.UserClient;
import com.xplanet.common.exception.BizException;
import com.xplanet.common.response.ErrorCode;
import com.xplanet.common.response.PageResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 文章服务实现。
 *
 * <h3>缓存一致性策略:Cache Aside + 延迟双删 + MQ 广播</h3>
 *
 * <pre>
 * 写流程:
 *   1) 事务内更新 DB
 *   2) 删 L1 + L2 缓存(第一删)
 *   3) 同一事务写入立即和延迟两条缓存失效 Outbox 事件
 *   4) relay 在提交后可靠发送 MQ 广播,所有实例清 L1 和共享 L2
 * </pre>
 *
 * <h3>几个关键设计点</h3>
 * <ul>
 *   <li><b>为什么删缓存而不是更新缓存</b>:并发写下更新缓存会脏;且写少读多时更新缓存浪费。</li>
 *   <li><b>为什么要第二删</b>:防止 T1(读 miss → 查到旧值)与 T2(写 + 删缓存)交叉时,
 *       T1 把旧值写回缓存。延迟 1s 再删一次可覆盖该窗口。</li>
 *   <li><b>为什么使用 Outbox</b>:DB 变更和待发送事件同事务提交，MQ 故障或实例崩溃后可恢复；
 *       延迟事件通过 next_retry_time 调度，不占用业务线程 sleep。</li>
 *   <li><b>多实例 L1 一致</b>:L1 是进程内 Caffeine,靠 MQ 广播(BROADCASTING)让每个实例都清自己的 L1。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ArticleServiceImpl implements ArticleService {

    private final ArticleMapper articleMapper;
    private final ArticleCacheManager cacheManager;
    private final ArticleChangeOutboxMapper changeOutboxMapper;
    private final UserClient userClient;

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

    @Override
    public boolean existsActive(Long articleId) {
        if (articleId == null || articleId <= 0) {
            return false;
        }
        return articleMapper.existsActive(articleId);
    }

    @Override
    public PageResult<ArticleListItemVO> list(int pageNum, int pageSize) {
        // 防御:页码、页大小合法化
        if (pageNum < 1) pageNum = 1;
        if (pageSize < 1 || pageSize > 50) pageSize = 10;

        // 只查未删除的,按创建时间倒序
        Page<Article> page = new Page<>(pageNum, pageSize);
        LambdaQueryWrapper<Article> wrapper = new LambdaQueryWrapper<Article>()
                .eq(Article::getDeleted, 0)
                .orderByDesc(Article::getCreateTime);
        Page<Article> result = articleMapper.selectPage(page, wrapper);

        List<ArticleListItemVO> items = result.getRecords().stream()
                .map(this::toListItem)
                .collect(Collectors.toList());

        return PageResult.of(items, result.getTotal(), pageNum, pageSize);
    }

    private ArticleListItemVO toListItem(Article a) {
        ArticleListItemVO vo = new ArticleListItemVO();
        vo.setId(a.getId());
        vo.setAuthorId(a.getAuthorId());
        vo.setAuthorName(userClient.getUserName(a.getAuthorId()));
        vo.setTitle(a.getTitle());
        // 摘要:正文前 80 字
        String content = a.getContent() == null ? "" : a.getContent();
        vo.setSummary(content.length() > 80 ? content.substring(0, 80) + "..." : content);
        vo.setTags(a.getTags());
        vo.setLikeCount(a.getLikeCount());
        vo.setViewCount(a.getViewCount());
        vo.setCreateTime(a.getCreateTime());
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
        appendCacheInvalidations(articleId, "UPDATE");
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
        appendCacheInvalidations(articleId, "DELETE");
    }

    private void appendCacheInvalidations(Long articleId, String operation) {
        if (changeOutboxMapper.insertEvent(
                UUID.randomUUID().toString(), articleId, operation, 0) != 1) {
            throw new IllegalStateException("failed to persist immediate cache invalidation");
        }
        if (changeOutboxMapper.insertEvent(
                UUID.randomUUID().toString(), articleId, operation, 1) != 1) {
            throw new IllegalStateException("failed to persist delayed cache invalidation");
        }
    }

    /** 回源 DB,返回 VO(避免 entity 直接暴露) */
    private ArticleDetailVO loadFromDb(Long articleId) {
        Article a = articleMapper.selectById(articleId);
        if (a == null || a.getDeleted() != 0) return null;
        ArticleDetailVO vo = new ArticleDetailVO();
        BeanUtils.copyProperties(a, vo);
        // 通过 UserClient 获取作者名；远端 user 服务不可用时由客户端返回兜底值。
        vo.setAuthorName(userClient.getUserName(a.getAuthorId()));
        return vo;
    }
}
